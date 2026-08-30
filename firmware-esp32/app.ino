#include <Arduino.h>
#include <DHT.h>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// =========================
// BLE - UUIDs do contrato firmware <-> app
// (devem ser IDENTICOS no app Android)
// =========================
#define SERVICE_UUID        "6532737a-6c11-44b4-8512-5f85353f28c2"
#define CHARACTERISTIC_UUID "ee8656c2-b9e2-4289-bdca-8cfd416626c0"

BLEServer* pServer = nullptr;
BLECharacteristic* pCharacteristic = nullptr;
bool bleConectado = false;
bool bleConectadoAnterior = false;

// =========================
// Identificacao do equipamento
// =========================
const char* EQUIPAMENTO_ID = "esp32_01";

// =========================
// DHT22 (tenta leitura real; cai em fallback simulado se nao responder)
// =========================
#define DHTPIN 4
#define DHTTYPE DHT22
DHT dht(DHTPIN, DHTTYPE);
unsigned long ultimaLeituraDHT = 0;
const unsigned long INTERVALO_DHT = 2000;
float ultimaTemp = NAN, ultimaUmid = NAN;

// =========================
// Fallback simulado do DHT22 (sensor nao conectado/falhando)
// Motivo: mesmo padrao dos outros sensores mockados - ate o fisico chegar/funcionar
// =========================
float gerarTemperaturaMockada() {
  return 18.0 + random(0, 240) / 10.0; // 18.0 a 42.0 C (faixa plausivel de campo)
}

float gerarUmidadeMockada() {
  return 30.0 + random(0, 600) / 10.0; // 30.0 a 90.0 %
}

// =========================
// Sensores mockados (MPU6050 e distancia esq/dir)
// Motivo: componentes ainda nao chegaram (pendencia documentada)
// =========================
unsigned long ultimaLeituraMock = 0;
const unsigned long INTERVALO_MOCK = 1000;
float vibX = 0.0, vibY = 0.0, vibZ = 9.81;
float giroX = 0.0, giroY = 0.0, giroZ = 0.0;
float mpuTempInternaC = 30.0;
float distEsquerdaMm = 400.0, distDireitaMm = 400.0;

void lerSensoresMockados() {
  // Acelerometro: pequena variacao aleatoria em torno de valores plausiveis (m/s^2)
  vibX = random(-50, 50) / 1000.0;
  vibY = random(-50, 50) / 1000.0;
  vibZ = 9.81 + random(-30, 30) / 1000.0;

  // Giroscopio: velocidade angular simulada (graus/s), baixa em repouso
  giroX = random(-200, 200) / 100.0;
  giroY = random(-200, 200) / 100.0;
  giroZ = random(-200, 200) / 100.0;

  // Temperatura interna do chip MPU6050 (graus C), tende a ficar um pouco acima da ambiente
  mpuTempInternaC = 30.0 + random(-20, 40) / 10.0;

  // Distancia: oscila para simular movimento, ocasionalmente "detecta" algo perto
  distEsquerdaMm = 400.0 + random(-100, 100);
  distDireitaMm  = 400.0 + random(-100, 100);
  if (random(0, 10) == 0) distEsquerdaMm = random(30, 150);   // simula objeto perto, de vez em quando
  if (random(0, 10) == 0) distDireitaMm  = random(30, 150);
}

// =========================
// Botao de turno + LEDs
// =========================
#define BOTAO_PIN 13
#define LED_VERDE_PIN 26
#define LED_VERMELHO_PIN 25
bool trabalhando = false;
unsigned long inicioTurno = 0;
unsigned long ultimoClique = 0;
const unsigned long DEBOUNCE_MS = 500;

// =========================
// Schema fixo dos dados (define a ordem das colunas do array)
// Precisa ficar IDENTICO no app Android
// =========================
const char* SCHEMA_CAMPOS[] = {
  "equipamento_id", "timestamp_ms", "temperatura", "umidade",
  "vibracao_x", "vibracao_y", "vibracao_z",
  "giro_x", "giro_y", "giro_z", "mpu_temp_interna_c",
  "distancia_esquerda_mm", "distancia_direita_mm",
  "status_turno", "mock"
};
const size_t NUM_CAMPOS = sizeof(SCHEMA_CAMPOS) / sizeof(SCHEMA_CAMPOS[0]);

// Medido: schema serializado ~227 bytes, linha de dado pior-caso ~99 bytes.
// Buffers com margem sobre isso (ver conversa/medicao em Python).
const size_t SCHEMA_BUFFER_SIZE = 256;
const size_t PAYLOAD_BUFFER_SIZE = 192;

// Monta o array JSON do schema (nomes das colunas, na ordem fixa)
size_t montarSchema(char* buffer, size_t tamanhoBuffer) {
  StaticJsonDocument<SCHEMA_BUFFER_SIZE> doc;
  JsonArray arr = doc.to<JsonArray>();
  for (size_t i = 0; i < NUM_CAMPOS; i++) {
    arr.add(SCHEMA_CAMPOS[i]);
  }
  return serializeJson(doc, buffer, tamanhoBuffer);
}

// =========================
// Store-and-forward via LittleFS
// =========================
const char* ARQUIVO_DADOS = "/telemetria.jsonl";
bool pausado = false;

void iniciarArquivo() {
  if (!LittleFS.exists(ARQUIVO_DADOS)) {
    File arquivo = LittleFS.open(ARQUIVO_DADOS, "w");
    if (arquivo) {
      char schemaBuf[SCHEMA_BUFFER_SIZE];
      montarSchema(schemaBuf, sizeof(schemaBuf));
      arquivo.println(schemaBuf);
      arquivo.close();
    }
    Serial.println("[FS] Arquivo criado com schema.");
    return;
  }

  // Arquivo ja existe: so verifica o formato (sem migrar automaticamente)
  File leitura = LittleFS.open(ARQUIVO_DADOS, "r");
  if (!leitura) {
    Serial.println("[FS] Erro ao abrir arquivo existente para verificar formato!");
    return;
  }
  String primeiraLinha = leitura.readStringUntil('\n');
  primeiraLinha.trim();
  leitura.close();

  if (primeiraLinha.length() == 0) {
    // Arquivo existia vazio (ex: gravacao anterior interrompida antes da 1a linha) -> escreve schema agora
    File arquivo = LittleFS.open(ARQUIVO_DADOS, "a");
    if (arquivo) {
      char schemaBuf[SCHEMA_BUFFER_SIZE];
      montarSchema(schemaBuf, sizeof(schemaBuf));
      arquivo.println(schemaBuf);
      arquivo.close();
    }
    Serial.println("[FS] Arquivo existia vazio, schema adicionado.");
  } else if (primeiraLinha.charAt(0) == '{') {
    Serial.println("[FS] AVISO: arquivo em formato antigo (objeto, sem schema) detectado! Firmware NAO migra automaticamente - linhas antigas serao reenviadas cruas, o app precisa tratar esse formato ao processar.");
  } else {
    Serial.println("[FS] Arquivo ja existe com schema, continuando gravacao.");
  }
}

void mostrarStatus() {
  size_t total = LittleFS.totalBytes();
  size_t usado = LittleFS.usedBytes();
  Serial.print("[FS] Espaco usado: ");
  Serial.print(usado / 1024);
  Serial.print(" KB / ");
  Serial.print(total / 1024);
  Serial.println(" KB total");
}

void limparArquivo() {
  if (LittleFS.remove(ARQUIVO_DADOS)) {
    Serial.println("[FS] Arquivo apagado com sucesso.");
    iniciarArquivo();
  } else {
    Serial.println("[FS] Erro ao apagar o arquivo!");
  }
}

void lerArquivo() {
  File arquivo = LittleFS.open(ARQUIVO_DADOS, "r");
  if (!arquivo) {
    Serial.println("[FS] Erro ao abrir arquivo para leitura!");
    return;
  }
  Serial.println("--- INICIO DOS DADOS ---");
  while (arquivo.available()) {
    Serial.write(arquivo.read());
  }
  Serial.println("--- FIM DOS DADOS ---");
  arquivo.close();
}

// Monta o JSON do payload atual num buffer (retorna o tamanho escrito)
// ATENCAO: a ordem dos arr.add() abaixo tem que bater EXATAMENTE com SCHEMA_CAMPOS.
// Ordem do schema, pra conferir lado a lado ao editar:
//   0 equipamento_id | 1 timestamp_ms | 2 temperatura | 3 umidade
//   4 vibracao_x | 5 vibracao_y | 6 vibracao_z
//   7 giro_x | 8 giro_y | 9 giro_z | 10 mpu_temp_interna_c
//   11 distancia_esquerda_mm | 12 distancia_direita_mm
//   13 status_turno | 14 mock
size_t montarPayload(char* buffer, size_t tamanhoBuffer, unsigned long tempo) {
  StaticJsonDocument<PAYLOAD_BUFFER_SIZE> doc;
  JsonArray arr = doc.to<JsonArray>();

  arr.add(EQUIPAMENTO_ID);                                  // 0  equipamento_id
  arr.add(tempo);                                           // 1  timestamp_ms
  arr.add(isnan(ultimaTemp) ? -1 : ultimaTemp);              // 2  temperatura
  arr.add(isnan(ultimaUmid) ? -1 : ultimaUmid);              // 3  umidade
  arr.add(vibX);                                            // 4  vibracao_x
  arr.add(vibY);                                            // 5  vibracao_y
  arr.add(vibZ);                                            // 6  vibracao_z
  arr.add(giroX);                                           // 7  giro_x
  arr.add(giroY);                                           // 8  giro_y
  arr.add(giroZ);                                           // 9  giro_z
  arr.add(mpuTempInternaC);                                 // 10 mpu_temp_interna_c
  arr.add(distEsquerdaMm);                                  // 11 distancia_esquerda_mm
  arr.add(distDireitaMm);                                   // 12 distancia_direita_mm
  arr.add(trabalhando ? "trabalhando" : "parado");           // 13 status_turno
  arr.add(true);                                            // 14 mock (sensores MPU/distancia ainda simulados)

  return serializeJson(doc, buffer, tamanhoBuffer);
}


void salvarNoFS(const char* payload) {
  size_t total = LittleFS.totalBytes();
  size_t usado = LittleFS.usedBytes();
  if ((total - usado) < 5120) {
    Serial.println("[FS] ERRO: espaco insuficiente, gravacao interrompida!");
    return;
  }
  File arquivo = LittleFS.open(ARQUIVO_DADOS, "a");
  if (arquivo) {
    arquivo.println(payload);
    arquivo.close();
    Serial.print("[FS] Dado guardado (sem BLE): ");
    Serial.println(payload);
  } else {
    Serial.println("[FS] Erro ao gravar no arquivo!");
  }
}

// Reenvia tudo que ficou pendente no LittleFS quando o BLE reconecta
// Envia o schema (nomes das colunas) como notificacao BLE separada
void enviarSchemaBLE() {
  char schemaBuf[SCHEMA_BUFFER_SIZE];
  montarSchema(schemaBuf, sizeof(schemaBuf));
  pCharacteristic->setValue(schemaBuf);
  pCharacteristic->notify();
  Serial.print("[BLE] Schema enviado: ");
  Serial.println(schemaBuf);
}

void reenviarPendentes() {
  // Primeira passada: so conta quantas linhas tem pendentes
  File contagem = LittleFS.open(ARQUIVO_DADOS, "r");
  if (!contagem) return;
  int totalPendentes = 0;
  while (contagem.available()) {
    String linha = contagem.readStringUntil('\n');
    linha.trim();
    if (linha.length() > 0) totalPendentes++;
  }
  contagem.close();

  if (totalPendentes == 0) return; // nada pendente, nao precisa avisar nada

  Serial.print("[SYNC] Iniciando sincronizacao de ");
  Serial.print(totalPendentes);
  Serial.println(" leituras pendentes...");

  // Manda o schema primeiro, pra garantir que o app sabe interpretar o que vem a seguir
  enviarSchemaBLE();
  delay(100);

  // Avisa o app que uma sincronizacao esta comecando
  char marcador[64];
  snprintf(marcador, sizeof(marcador), "{\"sync_start\":true,\"pendentes\":%d}", totalPendentes);
  pCharacteristic->setValue(marcador);
  pCharacteristic->notify();
  delay(100);

  File arquivo = LittleFS.open(ARQUIVO_DADOS, "r");
  int enviados = 0;
  while (arquivo.available()) {
    String linha = arquivo.readStringUntil('\n');
    linha.trim();
    if (linha.length() > 0) {
      pCharacteristic->setValue(linha.c_str());
      pCharacteristic->notify();
      enviados++;
      delay(50); // evita saturar o buffer BLE em rajada
    }
  }
  arquivo.close();
  limparArquivo();

  // Avisa o app que a sincronizacao terminou
  snprintf(marcador, sizeof(marcador), "{\"sync_end\":true,\"enviados\":%d}", enviados);
  pCharacteristic->setValue(marcador);
  pCharacteristic->notify();

  Serial.print("[SYNC] Total reenviado: ");
  Serial.println(enviados);
}

// =========================
// Callbacks de conexao BLE
// =========================
class MeusCallbacksServidor: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      bleConectado = true;
      Serial.println("[BLE] App conectado.");
    }
    void onDisconnect(BLEServer* pServer) {
      bleConectado = false;
      Serial.println("[BLE] App desconectado. Voltando a anunciar...");
      pServer->getAdvertising()->start();
    }
};

// Callback pra comandos que o app manda pro ESP32 via escrita na characteristic
class MeusCallbacksCharacteristic: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) {
      std::string valor = pChar->getValue();
      if (valor == "GET_SCHEMA") {
        Serial.println("[BLE] Comando recebido: GET_SCHEMA");
        enviarSchemaBLE();
      } else if (valor.length() == 0) {
        // escrita vazia - ignora, nao eh comando valido
        Serial.println("[BLE] AVISO: escrita vazia recebida, ignorada.");
      } else {
        Serial.print("[BLE] AVISO: comando desconhecido recebido: ");
        Serial.println(valor.c_str());
      }
    }
};

void setupBLE() {
  BLEDevice::init("RiskAI_ESP32");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MeusCallbacksServidor());

  BLEService* pService = pServer->createService(SERVICE_UUID);
  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
  );
  pCharacteristic->setCallbacks(new MeusCallbacksCharacteristic());
  pCharacteristic->addDescriptor(new BLE2902());
  pService->start();

  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[BLE] Anunciando como 'RiskAI_ESP32'...");
}

// =========================
// Setup
// =========================
void setup() {
  Serial.begin(115200);
  delay(1000);
  randomSeed(analogRead(0));

  if (!LittleFS.begin(true)) {
    Serial.println("[FS] Erro ao montar o sistema de arquivos!");
  } else {
    Serial.println("[FS] Sistema de arquivos montado.");
    iniciarArquivo();
    mostrarStatus();
  }

  dht.begin();

  pinMode(BOTAO_PIN, INPUT_PULLUP);
  pinMode(LED_VERDE_PIN, OUTPUT);
  pinMode(LED_VERMELHO_PIN, OUTPUT);
  digitalWrite(LED_VERMELHO_PIN, HIGH);
  digitalWrite(LED_VERDE_PIN, LOW);

  setupBLE();

  Serial.println("=== RiskAI - ESP32 v2 (BLE) iniciado ===");
  Serial.println("Comandos disponiveis: LER | LIMPAR | STATUS | PAUSAR | CONTINUAR");
}

// =========================
// Loop
// =========================
void loop() {
  unsigned long agora = millis();

  // ---------- Deteccao de reconexao BLE ----------
  if (bleConectado && !bleConectadoAnterior) {
    reenviarPendentes();
  }
  bleConectadoAnterior = bleConectado;

  // ---------- Comandos via Serial ----------
  if (Serial.available()) {
    String comando = Serial.readStringUntil('\n');
    comando.trim();
    comando.toUpperCase();

    if (comando == "LER") {
      lerArquivo();
    } else if (comando == "LIMPAR") {
      limparArquivo();
    } else if (comando == "STATUS") {
      mostrarStatus();
    } else if (comando == "PAUSAR") {
      pausado = true;
      Serial.println("[SISTEMA] Pausado. Use LER, STATUS ou LIMPAR. Digite CONTINUAR para retomar.");
    } else if (comando == "CONTINUAR") {
      pausado = false;
      Serial.println("[SISTEMA] Retomando coleta...");
    }
  }

  // ---------- Botao de turno (sempre ativo, mesmo pausado) ----------
  if (digitalRead(BOTAO_PIN) == LOW) {
    if (agora - ultimoClique > DEBOUNCE_MS) {
      ultimoClique = agora;
      trabalhando = !trabalhando;

      if (trabalhando) {
        inicioTurno = agora;
        digitalWrite(LED_VERDE_PIN, HIGH);
        digitalWrite(LED_VERMELHO_PIN, LOW);
        Serial.println("[TURNO] Iniciado (LED verde)");
      } else {
        unsigned long duracaoTurno = (agora - inicioTurno) / 1000;
        digitalWrite(LED_VERDE_PIN, LOW);
        digitalWrite(LED_VERMELHO_PIN, HIGH);
        Serial.print("[TURNO] Encerrado - Duracao: ");
        Serial.print(duracaoTurno);
        Serial.println(" segundos");
      }
    }
  }

  if (pausado) {
    return;
  }

  // ---------- DHT22 (real, com fallback simulado se nao responder) ----------
  if (agora - ultimaLeituraDHT >= INTERVALO_DHT) {
    ultimaLeituraDHT = agora;
    float tempReal = dht.readTemperature();
    float umidReal = dht.readHumidity();

    if (isnan(tempReal) || isnan(umidReal)) {
      ultimaTemp = gerarTemperaturaMockada();
      ultimaUmid = gerarUmidadeMockada();
    } else {
      ultimaTemp = tempReal;
      ultimaUmid = umidReal;
    }
  }

  // ---------- Sensores mockados + envio/gravacao ----------
  if (agora - ultimaLeituraMock >= INTERVALO_MOCK) {
    ultimaLeituraMock = agora;
    lerSensoresMockados();

    char payload[PAYLOAD_BUFFER_SIZE];
    montarPayload(payload, sizeof(payload), agora);

    if (bleConectado) {
      pCharacteristic->setValue(payload);
      pCharacteristic->notify();
      Serial.print("[BLE] Enviado: ");
      Serial.println(payload);
    } else {
      salvarNoFS(payload);
    }
  }
}
