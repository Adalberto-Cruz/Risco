# Documento de Evolução Técnica — RiskAI (Sompo Seguros)
## Challenge Sprint 3 — FIAP

---

## 1. Resumo da Sprint

### O que foi desenvolvido na Sprint 3
* **Firmware ESP32:** Leitura de sensores (DHT22, MPU6050, VL53L1X ToF) e transmissão de telemetria/alertas via BLE para o aplicativo mobile.
* **App Android Nativo (Kotlin / Jetpack Compose):** Cadastro de equipamentos, visualização de ranking, gráficos de distribuição de risco, cards de perda esperada e recepção de alertas de colisão em tempo real.
* **Arquitetura AWS Integrada:** Fluxo completo via API Gateway com autenticação JWT (Cognito) → Lambda de classificação (RandomForest) → DynamoDB (`riskai-leituras`) → Lambda de agregação diária → Lambda de previsão de quebra (Cox Proportional Hazards + integração Open-Meteo) → API de ranking consumida pelo App.
* **Pipeline de Machine Learning:** Camada 1 (Classificação de severidade com RandomForest) e Camada 2 (Sobrevivência/Cox para estimativa de perda financeira em R$) operando em containers Docker (ECR/Lambda).

### Principais evoluções em relação à Sprint 2
* Migração da interface mobile de PWA para aplicativo Android nativo, viabilizando comunicação BLE estável em background.
* Implementação da camada de agregação diária de telemetria e cálculo de perda esperada (Camada 2 de ML).
* Integração automatizada com dados meteorológicos históricos via API Open-Meteo.
* Substituição de protótipos estáticos por dashboard interativo consumindo dados reais da AWS.

### Percentual aproximado de conclusão
**~80%** do escopo priorizado concluído (MVP funcional com fluxo completo de dados, predição e alertas locais).

### Principais dificuldades encontradas
* Atraso no recebimento de sensores físicos finais (MPU6050 e VL53L1X), contornado através de emulação/mock estruturado diretamente no firmware do ESP32 para não travar a integração do app e nuvem.
* Limitações de permissões do ambiente AWS Academy Learner Lab (ausência de permissão para criação de IAM Roles personalizadas), exigindo o uso da `LabRole` genérica e orquestração via chamadas diretas entre Lambdas.
* Escassez de dados reais de quebra de maquinário no tempo para a Camada 2 (Cox), exigindo treino inicial com base sintética e validação out-of-fold.

---

## 2. Feedback da Sompo

### Principais comentários recebidos
* **Pontos positivos:** Reconhecimento da maturidade técnica ao já possuir modelos de ML integrados e postura crítica quanto à inclusão de novas variáveis sem justificativa clara de negócio. A Sompo sinalizou que as variáveis atuais podem ser suficientes.
* **Pontos de atenção:** Excesso de tempo dedicado à exposição de código-fonte e arquitetura no pitch anterior, reduzindo o tempo de demonstração da solução prática rodando.

### Alterações realizadas a partir do feedback
* Redesenho da apresentação e do vídeo de demonstração com foco total no produto funcionando na mão do usuário (Dashboard, App e Alertas), deixando o código apenas como sustentação técnica.
* Decisão de não buscar novas variáveis externas sem antes avaliar se as atuais (telemetria e clima) já atendem perfeitamente ao objetivo do modelo.

### Sugestões que não serão implementadas por ora (com justificativa)
* **Ingestão de novas bases de dados externas complexas:** Adiada para focar no refinamento e assertividade das variáveis já mapeadas, conforme recomendação da Sompo, evitando complexidade técnica desnecessária.

---

## 3. User Stories Trabalhadas

Abaixo está o mapeamento do backlog priorizado, cobrindo as visões da Seguradora (Sompo), do Segurado (Cliente) e das Personas operacionais.

| ID | User Story (Visão / Persona) | O que foi implementado | Status | Evidência |
|---|---|---|---|---|
| **US01** | **Score de Risco e Perda Esperada** *(Sompo)* | Pipeline ML (RandomForest + Cox) rodando em Lambda/Docker gerando probabilidade de falha e Perda Esperada em R$. | ✅ Concluído | Dashboard exibindo score e R$ de perda esperada |
| **US02** | **Painel de Risco Operacional** *(Cliente)* | Dashboard Android nativo com cards consolidados, gráfico de pizza por nível de risco e lista de ativos. | ✅ Concluído | Prints/vídeo do dashboard mobile |
| **US03** | **Ranking de Risco de Frota** *(Gestor)* | Endpoint `GET /ranking` e tela de ranking no app, ordenando equipamentos pela severidade do risco. | ✅ Concluído | Print da tela de ranking no App |
| **US04** | **Alerta de Colisão com Obstáculos** *(Operador)* | Monitoramento de distância via VL53L1X no firmware ESP32 com disparo de notificação visual/crítica no App via BLE. | ✅ Concluído | Print/vídeo do alerta de proximidade no App |
| **US05** | **Integração com Clima** *(Sompo / Operador)* | Integração da API Open-Meteo na Lambda `riskai-preditor-quebra` para correlacionar histórico de chuva/temperatura. | ✅ Concluído | Logs e código de integração Open-Meteo |
| **US06** | **Drivers de Risco / Explicabilidade** *(Sompo)* | Extração de *feature importance* do modelo para exibição dos principais fatores agravantes no dashboard. | ✅ Concluído | Print da tela detalhada de fatores de risco |
| **US07** | **Trilha de Auditoria e Governança** *(Sompo)* | Tabela DynamoDB (`riskai-leituras`) armazenando telemetria bruta, timestamps e saídas de inferência. | 🟡 Parcial | Schema e registros da tabela DynamoDB |
| **US08** | **Alertas em Tempo de Decisão** *(Operador)* | Alertas de colisão locais via BLE já funcionam; alertas preditivos de terreno/solo crítico pendentes de processamento. | 🟡 Parcial | Alertas via BLE no App |
| **US09** | **Detecção de Proximidade de Água** *(Operador)* | Identificação de zonas críticas de rios/represas via geofencing (Google Maps SDK pausado temporariamente). | 🔴 Pendente | — (Planejado para Sprint 4) |
| **US10** | **Recomendações Práticas / Prescritivo** *(Cliente)* | Módulo de sugestões operacionais de rotas, velocidade e horários ("o que mudar"). | 🔴 Pendente | — (Planejado para Sprint 4) |
| **US11** | **Relatórios Consolidados por Período** *(Cliente)* | Emissão de relatórios em PDF com série temporal de sinistros evitados e tendências de risco. | 🔴 Pendente | — (Planejado para Sprint 4) |
| **US12** | **Configuração de Políticas Internas** *(Gestor)* | Interface para o gestor definir regras de bloqueio de operação ou limiares de alerta customizados. | 🔴 Pendente | — (Planejado para Sprint 4) |

---

## 4. Visão Técnica da Solução

### Tecnologias Utilizadas
* **Hardware/IoT:** ESP32, C++ (PlatformIO), Sensores DHT22, MPU6050 (Mockado) e VL53L1X (ToF).
* **Mobile:** Android Nativo (Kotlin / Jetpack Compose), BLE nativo, AWS Amplify (Cognito Auth), gráficos Vico.
* **Backend & Cloud AWS:** API Gateway (HTTP API), Lambda (Python 3.11 / Docker ECR), DynamoDB, Cognito.
* **Machine Learning:** Scikit-Learn (RandomForestClassifier), Lifelines (Cox Proportional Hazards), Pandas, API Open-Meteo.

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
