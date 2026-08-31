## 4. Visão Técnica da Solução

### Tecnologias Utilizadas
* **Hardware/IoT:** ESP32, C++ (PlatformIO), Sensores DHT22, MPU6050 (Mockado) e VL53L1X (ToF).
* **Mobile:** Android Nativo (Kotlin / Jetpack Compose), BLE nativo, AWS Amplify (Cognito), gráficos Vico.
* **Backend & Cloud AWS:** API Gateway (HTTP API), Lambda (Python 3.11 / Docker ECR), DynamoDB, Cognito.
* **Machine Learning:** Scikit-Learn (RandomForestClassifier), Lifelines (Cox Proportional Hazards), API Open-Meteo (Clima).

### Arquitetura de Dados e Fluxo de Integração (7 Etapas)

A arquitetura do RiskAI foi desenhada com foco em performance, governança e preservação das informações críticas de risco. O fluxo de ponta a ponta ocorre nas seguintes etapas:

**1. Captura (IoT / ESP32)**
O firmware realiza a leitura de temperatura/umidade (DHT22), vibração/giroscópio (MPU6050 — mockado na atual sprint) e distância frontal/lateral (VL53L1X). O microcontrolador monta um payload JSON enxuto e transmite via BLE.

**2. Ingestão e Transformação (App Android)**
O app recebe os dados brutos via `BleManager.kt`. Os eixos do MPU são convertidos através de fórmulas físicas em valores escalares esperados pelo modelo de ML (aceleração em *g*, inclinação em *graus* e velocidade angular em *graus/s*). A `AwsUploader.kt` monta o payload, gera um `leitura_id` único (ID do equipamento + timestamp da geração celular) e realiza o disparo HTTP autenticado via Cognito.

**3. Ingestão na Nuvem (Classificação - Camada 1)**
O `POST /telemetria` passa pelo *Authorizer* JWT no API Gateway e aciona a Lambda `riskai-classificador`. Operando via container, ela executa o *RandomForest* e classifica o risco daquela leitura. O registro completo (incluindo todo o vetor `predict_proba`) é gravado na tabela DynamoDB `riskai-leituras`, indexada por `leitura_id` com uma GSI secundária `por-maquinario`.

**4. Agregação Diária de Risco**
A Lambda `riskai-agregador-diario` (arquitetada para disparo via EventBridge) consolida as métricas diárias. Ela busca as leituras na GSI e calcula o `score_leitura` baseando-se na fórmula: `probabilidade["alto"] + 0.5 × probabilidade["medio"]`. 
> *Decisão de Arquitetura:* O sistema grava estritamente o **maior valor do dia** como `score_risco_atual` na tabela `riskai-equipamentos`, e não uma média diária. Isso impede que picos isolados de risco extremo (ex: quase colisão) sejam mascarados ou diluídos por horas de operação em inatividade.

**5. Predição de Sobrevivência (Modelagem Cox - Camada 2)**
Executada diariamente, a Lambda `riskai-preditor-quebra` consome o score de risco recém-calculado, o cadastro da máquina e o clima da região (requisição automática à Open-Meteo por lat/long com cache de 7 dias). O sistema escolhe dinamicamente entre 3 níveis do modelo *Cox Proportional Hazards* (a depender da completude dos dados), calculando a `prob_quebra_90d` e a `perda_esperada_90d` (Probabilidade × Valor do equipamento) e devolvendo ao banco.

**6. Exposição das APIs e Proteção de Dados**
O endpoint `GET /ranking` consome uma GSI (`por-risco-global`) com partição fixa, ordenada nativamente pela probabilidade de quebra. Isso permite devolver o *Top N* máquinas em risco sem realizar uma operação onerosa de *Scan* na tabela. 
> *Decisão de Arquitetura:* O endpoint `POST /maquinarios` utiliza a operação `UpdateItem` (e nunca `PutItem`). Isso garante que atualizações cadastrais do usuário não apaguem inadvertidamente os scores gerados de forma assíncrona pelo Machine Learning.

**7. Consumo e Renderização (Dashboard Mobile)**
O `RankingViewModel` no Kotlin consome o endpoint de ranking, calcula o `nivel_risco` categórico localmente e distribui os dados reativamente pelo Jetpack Compose. Esses dados alimentam os cards consolidados, o gráfico de pizza e os gráficos em barras top 10 (biblioteca Vico).

### Diagrama de Integração Lógica

```mermaid
flowchart TD
    subgraph Edge [Borda / Local]
        A[1. ESP32<br>Captura Sensores] -- BLE JSON --> B[2. App Android<br>Conversão e Auth]
    end
    
    subgraph AWS_Ingestao [Ingestão e Camada 1 ML]
        B -- POST /telemetria<br>JWT Cognito --> C[3. API Gateway]
        C --> D[Lambda: riskai-classificador<br>RandomForest]
        D -- predict_proba --> E[(DynamoDB<br>riskai-leituras)]
    end
    
    subgraph AWS_Processamento [Agregação e Camada 2 ML]
        E -- GSI: por-maquinario --> F[4. Lambda: riskai-agregador<br>Pico de Risco Diário]
        F --> G[(DynamoDB<br>riskai-equipamentos)]
        G --> H[5. Lambda: riskai-preditor<br>Cox PH + Open-Meteo]
        H -- prob_quebra_90d<br>perda_esperada --> G
    end
    
    subgraph AWS_Exposicao [Consumo API]
        G -- GSI: por-risco-global --> I[6. Lambda: riskai-ranking-api]
        I -- GET /ranking --> J[7. App Android<br>Dashboard]
    end
