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
**~75% a 80%** do escopo priorizado concluído (MVP funcional com fluxo completo de dados, predição e alertas locais).

### Principais dificuldades encontradas
* Atraso no recebimento de sensores físicos finais (MPU6050 e VL53L1X), contornado através de emulação/mock estruturado diretamente no firmware do ESP32 para não travar a integração do app e nuvem.
* Limitações de permissões do ambiente AWS Academy Learner Lab (ausência de permissão para criação de IAM Roles personalizadas), exigindo o uso da `LabRole` e orquestração via chamadas diretas entre Lambdas.
* Escassez de dados reais de quebra de maquinário no tempo para a Camada 2 (Cox), exigindo treino inicial com base sintética e validação out-of-fold.

---

## 2. Feedback da Sompo

### Principais comentários recebidos
* **Pontos positivos:** Reconhecimento da maturidade técnica ao já possuir modelos de ML integrados e postura crítica quanto à inclusão de novas variáveis sem justificativa clara de negócio.
* **Pontos de atenção:** Excesso de tempo dedicado à exposição de código-fonte no pitch anterior, reduzindo o tempo de demonstração da solução prática funcionando.

### Alterações realizadas a partir do feedback
* Redesenho da apresentação e do vídeo de demonstração com foco total no produto funcionando na mão do usuário (Dashboard, App e Alertas).
* Congelamento da adição de novas bases externas até a validação do impacto das variáveis climáticas e de telemetria atuais.

### Sugestões que não serão implementadas por ora (com justificativa)
* **Ingestão de novas bases de dados externas complexas:** Adiada para focar no refinamento e assertividade das variáveis já mapeadas (clima e telemetria), conforme recomendação da Sompo.

---

## 3. User Stories Trabalhadas

Abaixo está o mapeamento do backlog priorizado, cobrindo as visões da Seguradora (Sompo), do Segurado (Cliente) e das Personas operacionais.

| ID | User Story (Visão / Persona) | O que foi implementado | Status | Evidência |
|---|---|---|---|---|
| **US01** | **Score de Risco e Perda Esperada** *(Sompo / Subscrição)* | Pipeline ML (RandomForest + Cox) rodando em Lambda/Docker gerando probabilidade de falha e Perda Esperada em R$. | ✅ Concluído | Dashboard exibindo score e R$ de perda esperada |
| **US02** | **Painel de Risco Operacional** *(Cliente / Segurado)* | Dashboard Android nativo com cards consolidados, gráfico de pizza por nível de risco e lista de ativos. | ✅ Concluído | Prints/vídeo do dashboard mobile |
| **US03** | **Ranking de Risco de Frota** *(Gestor de Frota)* | Endpoint `GET /ranking` e tela de ranking no app, ordenando equipamentos pela severidade do risco. | ✅ Concluído | Print da tela de ranking no App |
| **US04** | **Alerta de Colisão com Obstáculos** *(Operador de Campo)* | Monitoramento de distância via VL53L1X no firmware ESP32 com disparo de notificação visual/crítica no App via BLE. | ✅ Concluído | Print/vídeo do alerta de proximidade no App |
| **US05** | **Integração com Clima** *(Sompo / Operador)* | Integração da API Open-Meteo na Lambda `riskai-preditor-quebra` para correlacionar histórico de chuva/temperatura. | ✅ Concluído | Logs e código de integração Open-Meteo |
| **US06** | **Drivers de Risco / Explicabilidade** *(Sompo / Corretor)* | Extração de *feature importance* do modelo para exibição dos principais fatores agravantes no dashboard. | ✅ Concluído | Print da tela detalhada de fatores de risco |
| **US07** | **Trilha de Auditoria e Governança** *(Sompo / Auditoria)* | Tabela DynamoDB (`riskai-leituras`) armazenando telemetria bruta, timestamps e saídas de inferência. | 🟡 Parcial | Schema e registros da tabela DynamoDB *(Falta versionamento estrito de modelo)* |
| **US08** | **Alertas em Tempo de Decisão** *(Operador / Cliente)* | Alertas de colisão ativos; alertas preditivos de terreno/solo crítico ainda em processamento assíncrono. | 🟡 Parcial | Alertas via BLE no App *(Pendente push notification preventiva)* |
| **US09** | **Detecção de Proximidade de Água** *(Operador / Gestor)* | Identificação de zonas críticas de rios/represas via geofencing (Google Maps SDK pausado temporariamente). | 🔴 Pendente | — (Planejado para Sprint 4) |
| **US10** | **Recomendações Práticas / Prescritivo** *(Cliente / Corretor)* | Módulo de sugestões operacionais de rotas, velocidade e horários ("o que mudar"). | 🔴 Pendente | — (Planejado para Sprint 4) |
| **US11** | **Relatórios Consolidados por Período** *(Cliente / Sinistros)* | Emissão de relatórios em PDF com série temporal de sinistros evitados e tendências de risco. | 🔴 Pendente | — (Planejado para Sprint 4) |
| **US12** | **Configuração de Políticas Internas** *(Gestor de Frota)* | Interface para o gestor definir regras de bloqueio de operação ou limiares de alerta customizados. | 🔴 Pendente | — (Planejado para Sprint 4) |

---

## 4. Visão Técnica da Solução

### Tecnologias Utilizadas
* **Hardware/IoT:** ESP32, C++ (PlatformIO), Sensores DHT22, MPU6050 e VL53L1X.
* **Mobile:** Kotlin, Jetpack Compose, Android BLE API, AWS Amplify (Cognito Auth).
* **Backend Cloud:** AWS API Gateway (HTTP API), AWS Lambda (Python 3.11 / Docker), AWS DynamoDB, Amazon ECR.
* **Machine Learning & Dados:** Scikit-Learn (RandomForestClassifier), Lifelines (Cox Proportional Hazards), Pandas, Open-Meteo API.

### Diagrama de Arquitetura e Integração

```mermaid
flowchart LR
    A[ESP32<br/>Sensores + BLE] -->|BLE Telemetria| B[App Android<br/>Kotlin / Jetpack]
    B -->|HTTPS + JWT| C[API Gateway]
    C --> D[Lambda<br/>riskai-classificador]
    D -->|RandomForest| E[(DynamoDB<br/>riskai-leituras)]
    E --> F[Lambda<br/>riskai-agregador-diario]
    F --> G[(DynamoDB<br/>riskai-equipamentos)]
    G --> H[Lambda<br/>riskai-preditor-quebra]
    H -->|Cox + Open-Meteo| G
    G --> I[Lambda<br/>riskai-ranking-api]
    I --> B
