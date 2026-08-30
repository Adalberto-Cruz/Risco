"""
Lambda handler do classificador de risco RiskAI (cliente Sompo).

Fluxo (offline-first): ESP32 grava no SD -> app do celular puxa via BLE ->
app sobe em LOTE para a nuvem quando tem sinal -> API Gateway (JWT
authorizer via Cognito) -> esta Lambda.

Aceita uma leitura unica OU uma lista de leituras no mesmo payload
(retrocompativel com o formato antigo de leitura unica). Cada leitura
precisa ter um `leitura_id` unico (gerado no ESP32/app) e `maquinario_id`.
A gravacao no DynamoDB e condicional (attribute_not_exists) para que um
reenvio do app (ex: nao teve certeza se o POST anterior chegou) nunca
duplique registro -- mas a classificacao e devolvida na resposta de
qualquer forma, mesmo em reenvio.

Modelo: RandomForestClassifier (n_estimators=300, max_depth=10, class_weight='balanced')
treinado em todo o dataset RiskAI (11.595 registros). Limiar da classe 'alto' = 0.22,
fechado via validacao out-of-fold (recall 95.0% / precisao 68.4%).
"""

import json
import os
import time
from decimal import Decimal

import boto3
import joblib
import numpy as np
from botocore.exceptions import BotoCoreError, ClientError


def _para_dynamo(valor):
    """DynamoDB (via boto3) nao aceita float do Python -- precisa ser Decimal.
    Converte recursivamente floats (e numeros numpy) em dicts/listas tambem."""
    if isinstance(valor, float):
        return Decimal(str(valor))
    if isinstance(valor, (np.floating,)):
        return Decimal(str(float(valor)))
    if isinstance(valor, dict):
        return {k: _para_dynamo(v) for k, v in valor.items()}
    if isinstance(valor, list):
        return [_para_dynamo(v) for v in valor]
    return valor

# ---------------------------------------------------------------------------
# Contrato de features - MESMA ORDEM usada no treino (nao mude sem retreinar)
# ---------------------------------------------------------------------------
FEATURES_NUMERICAS = [
    "temperatura_c", "umidade_pct", "distancia_cm",
    "mpu_temp_interna_c", "mpu_vibracao_g",
    "mpu_inclinacao_graus", "mpu_velocidade_graus_s",
]
TURNOS = ["trabalhando", "parado"]
CAMPOS_OBRIGATORIOS = FEATURES_NUMERICAS + ["status_turno", "leitura_id", "maquinario_id"]

LIMIAR_ALTO = 0.22
MODEL_PATH = os.environ.get("MODEL_PATH", "modelo_riskai_final.pkl")
TABELA_LEITURAS = os.environ.get("TABELA_LEITURAS", "riskai-leituras")
MAX_LOTE = 200  # protecao simples contra payloads gigantes numa unica invocacao

# Carregados uma vez por ambiente de execucao (reaproveitados em invocacoes "quentes")
_modelo = joblib.load(MODEL_PATH)
_classes = list(_modelo.classes_)          # ['alto', 'baixo', 'medio']
_idx_alto = _classes.index("alto")
_dynamodb = boto3.resource("dynamodb")
_tabela = _dynamodb.Table(TABELA_LEITURAS)


def _montar_features(dados: dict) -> np.ndarray:
    faltando = [c for c in CAMPOS_OBRIGATORIOS if c not in dados]
    if faltando:
        raise ValueError(f"Campos obrigatorios ausentes: {', '.join(faltando)}")

    linha = [dados[f] for f in FEATURES_NUMERICAS]
    linha += [1 if dados["status_turno"] == t else 0 for t in TURNOS]
    return np.array([linha], dtype=float)


def _prever_com_limiar(probas_linha: np.ndarray) -> dict:
    prob_alto = float(probas_linha[_idx_alto])
    if prob_alto >= LIMIAR_ALTO:
        nivel = "alto"
    else:
        outras = [i for i in range(len(_classes)) if i != _idx_alto]
        melhor = max(outras, key=lambda i: probas_linha[i])
        nivel = _classes[melhor]

    return {
        "nivel_risco": nivel,
        "probabilidades": {c: round(float(p), 4) for c, p in zip(_classes, probas_linha)},
        "limiar_alto_usado": LIMIAR_ALTO,
    }


def _gravar_se_novo(leitura: dict, resultado: dict) -> bool:
    """Grava a leitura+resultado no DynamoDB apenas se leitura_id ainda nao existe.
    Retorna True se gravou agora, False se ja existia (reenvio)."""
    item = _para_dynamo({
        "leitura_id": leitura["leitura_id"],
        "maquinario_id": leitura["maquinario_id"],
        "timestamp_leitura": leitura.get("timestamp", int(time.time())),
        "status_turno": leitura["status_turno"],
        **{f: leitura[f] for f in FEATURES_NUMERICAS},
        "nivel_risco": resultado["nivel_risco"],
        "probabilidades": resultado["probabilidades"],
        "gravado_em": int(time.time()),
    })
    try:
        _tabela.put_item(
            Item=item,
            ConditionExpression="attribute_not_exists(leitura_id)",
        )
        return True
    except ClientError as e:
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            return False
        raise


def _processar_uma_leitura(leitura: dict) -> dict:
    try:
        X = _montar_features(leitura)
    except (ValueError, KeyError, TypeError) as e:
        return {
            "leitura_id": leitura.get("leitura_id"),
            "erro": str(e),
        }

    probas = _modelo.predict_proba(X)[0]
    resultado = _prever_com_limiar(probas)
    resultado["leitura_id"] = leitura["leitura_id"]
    resultado["maquinario_id"] = leitura["maquinario_id"]

    try:
        gravou_agora = _gravar_se_novo(leitura, resultado)
        resultado["gravado_agora"] = gravou_agora  # False = reenvio, ja existia
    except (ClientError, BotoCoreError) as e:
        resultado["aviso_gravacao"] = f"classificado mas nao gravado: {e}"

    return resultado


def _resposta(status_code: int, corpo) -> dict:
    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(corpo, ensure_ascii=False),
    }


def lambda_handler(event, context):
    corpo_bruto = event.get("body", event)
    if isinstance(corpo_bruto, str):
        try:
            dados = json.loads(corpo_bruto)
        except json.JSONDecodeError:
            return _resposta(400, {"erro": "body nao e um JSON valido"})
    else:
        dados = corpo_bruto

    # Retrocompativel: aceita tanto um objeto unico quanto {"leituras": [...]}
    # ou uma lista JSON diretamente no body.
    if isinstance(dados, list):
        leituras = dados
    elif isinstance(dados, dict) and "leituras" in dados:
        leituras = dados["leituras"]
    else:
        leituras = [dados]

    if not leituras:
        return _resposta(400, {"erro": "nenhuma leitura enviada"})
    if len(leituras) > MAX_LOTE:
        return _resposta(400, {"erro": f"lote acima do limite de {MAX_LOTE} leituras"})

    resultados = [_processar_uma_leitura(leitura) for leitura in leituras]

    claims = (
        event.get("requestContext", {})
        .get("authorizer", {})
        .get("jwt", {})
        .get("claims", {})
    )
    usuario = claims.get("sub")

    # Se veio uma leitura unica no formato antigo (dict sem "leituras"), devolve
    # o objeto de resultado direto -- mantem compatibilidade com quem ainda
    # manda 1 por chamada. Lista ou {"leituras": [...]} sempre devolve em lote.
    veio_como_unica = isinstance(dados, dict) and "leituras" not in dados
    corpo_resposta = resultados[0] if veio_como_unica else {"resultados": resultados}
    if usuario:
        corpo_resposta["usuario"] = usuario

    tem_erro = any("erro" in r for r in resultados)
    return _resposta(207 if tem_erro else 200, corpo_resposta)
