# RiskAI — Sompo Seguros

Plataforma IoT + IA de monitoramento de risco operacional, desenvolvida para a Sompo Seguros como projeto acadêmico (FIAP — Challenge Sprint 3).

## Time

| Nome | RM |
|---|---|
| Adalberto Alves Cruz | 574115 |
| Bruno Henrique Ferreira Ambrosio | 571218 | 
| Gustavo da Silva Nascimento | 570821 | 
| Lucas Maximo dos Santos | 569714 | 
| Renan de Assis Rodrigues | 574049 | 
| Tiago Thomaz Cesaro | 569374 | 

## Visão geral

Fluxo: sensores ESP32 (BLE) → app Android → API Gateway (AWS, autenticação Cognito/JWT) → Lambdas de classificação de risco (RandomForest) e previsão de quebra (Cox Proportional Hazards) → DynamoDB → dashboard no app.

## Estrutura do repositório
Sompo/ # app Android (Kotlin, Jetpack Compose)
firmware-esp32/ # firmware do ESP32 (PlatformIO)
lambdas/ # funções AWS Lambda (Python)
riskai-classificador/
riskai-ranking-api/
riskai-agregador-diario/
riskai-processador/
riskai-preditor-quebra/
docs/ # documentação do projeto

## Tecnologias

- **App**: Kotlin, Jetpack Compose, Amplify (auth), BLE nativo
- **Firmware**: C++ (PlatformIO/Arduino), ESP32
- **Backend**: AWS Lambda (Python), API Gateway, DynamoDB, Cognito
- **ML**: scikit-learn (RandomForestClassifier), lifelines (Cox Proportional Hazards)

## Como rodar

_(seção a completar — instruções de build do app, deploy das Lambdas, etc.)_

## Status do projeto

Sprint 3 — Challenge FIAP / Sompo Seguros.
