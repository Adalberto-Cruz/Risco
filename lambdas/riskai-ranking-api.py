"""
riskai-ranking-api
-------------------
Lambda leve (Zip, so boto3) atras do API Gateway riskai-api.

GET /ranking?limite=10          -> top N equipamentos com maior prob_quebra_90d
GET /maquinarios/{id}           -> detalhe de um equipamento especifico
POST /maquinarios               -> cadastra (ou atualiza o cadastro de) um equipamento

Le direto da GSI "por-risco-global" da riskai-equipamentos, que tem
risco_bucket="GLOBAL" (fixo, pra virar uma unica particao) como PK e
prob_quebra_90d como SK — isso permite um Query com ScanIndexForward=false
e Limit=N pra pegar o top N sem nunca varrer a tabela toda.

O cadastro (POST /maquinarios) so grava os campos que o app/site conhece
(idade, manutencao, valor pago, localizacao). Os campos calculados pelo
pipeline (score_risco_atual, prob_quebra_90d, clima etc.) ficam de fora —
quem preenche isso e a riskai-preditor-quebra / a agregacao diaria.
"""
import json
import os
from decimal import Decimal

import boto3
from boto3.dynamodb.conditions import Key

TABELA_EQUIPAMENTOS = os.environ.get("TABELA_EQUIPAMENTOS", "riskai-equipamentos")
INDICE_RANKING = os.environ.get("INDICE_RANKING", "por-risco-global")
LIMITE_PADRAO = 10
LIMITE_MAXIMO = 100

CAMPOS_CADASTRO_OBRIGATORIOS = [
    "equipamento_id", "tipo", "idade_dias", "dias_desde_manutencao",
    "qtd_manutencoes", "valor_pago", "rendimento_pct", "latitude", "longitude",
]
CAMPOS_CADASTRO_NUMERICOS = [
    "idade_dias", "dias_desde_manutencao", "qtd_manutencoes",
    "valor_pago", "rendimento_pct", "latitude", "longitude",
]

dynamodb = boto3.resource("dynamodb")
tabela = dynamodb.Table(TABELA_EQUIPAMENTOS)


def _decimal_para_numero(obj):
    if isinstance(obj, list):
        return [_decimal_para_numero(v) for v in obj]
    if isinstance(obj, dict):
        return {k: _decimal_para_numero(v) for k, v in obj.items()}
    if isinstance(obj, Decimal):
        return float(obj) if obj % 1 else int(obj)
    return obj


def _resposta(status, corpo):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(_decimal_para_numero(corpo), ensure_ascii=False),
    }


def _rota(event):
    # cobre tanto API Gateway HTTP API (payload v2, "routeKey") quanto REST API (v1)
    if "routeKey" in event:
        return event["routeKey"]
    return f'{event.get("httpMethod", "")} {event.get("resource", "")}'


def lambda_handler(event, context):
    rota = _rota(event)

    if rota.startswith("GET /ranking"):
        params = event.get("queryStringParameters") or {}
        try:
            limite = min(int(params.get("limite", LIMITE_PADRAO)), LIMITE_MAXIMO)
        except (TypeError, ValueError):
            return _resposta(400, {"erro": "parametro 'limite' precisa ser um numero inteiro"})

        resposta = tabela.query(
            IndexName=INDICE_RANKING,
            KeyConditionExpression=Key("risco_bucket").eq("GLOBAL"),
            ScanIndexForward=False,  # maior prob_quebra_90d primeiro
            Limit=limite,
        )
        return _resposta(200, {"ranking": resposta.get("Items", [])})

    if rota.startswith("GET /maquinarios/"):
        path_params = event.get("pathParameters") or {}
        equipamento_id = path_params.get("id")
        if not equipamento_id:
            return _resposta(400, {"erro": "id do equipamento nao informado"})

        resposta = tabela.get_item(Key={"equipamento_id": equipamento_id})
        item = resposta.get("Item")
        if not item:
            return _resposta(404, {"erro": f"equipamento '{equipamento_id}' nao encontrado"})
        return _resposta(200, item)

    if rota.startswith("POST /maquinarios"):
        return _cadastrar_maquinario(event)

    return _resposta(404, {"erro": f"rota nao reconhecida: {rota}"})


def _cadastrar_maquinario(event):
    corpo_bruto = event.get("body") or "{}"
    try:
        corpo = json.loads(corpo_bruto)
    except json.JSONDecodeError:
        return _resposta(400, {"erro": "body precisa ser um JSON valido"})

    faltando = [c for c in CAMPOS_CADASTRO_OBRIGATORIOS if corpo.get(c) in (None, "")]
    if faltando:
        return _resposta(400, {"erro": "campos obrigatorios faltando", "campos": faltando})

    equipamento_id = str(corpo["equipamento_id"])
    campos_atualizados = {"tipo": str(corpo["tipo"])}
    for campo in CAMPOS_CADASTRO_NUMERICOS:
        try:
            campos_atualizados[campo] = Decimal(str(corpo[campo]))
        except (TypeError, ValueError):
            return _resposta(400, {"erro": f"campo '{campo}' precisa ser numerico"})

    # UpdateItem (nao PutItem): so toca nos campos de cadastro. Assim, recadastrar
    # um equipamento (ex.: atualizar valor_pago apos manutencao) nao apaga
    # score_risco_atual/prob_quebra_90d/clima que o pipeline ja tiver calculado.
    ja_existe = tabela.get_item(Key={"equipamento_id": equipamento_id}).get("Item") is not None

    nomes_expr = {f"#{c}": c for c in campos_atualizados}
    valores_expr = {f":{c}": v for c, v in campos_atualizados.items()}
    update_expr = "SET " + ", ".join(f"#{c} = :{c}" for c in campos_atualizados)

    tabela.update_item(
        Key={"equipamento_id": equipamento_id},
        UpdateExpression=update_expr,
        ExpressionAttributeNames=nomes_expr,
        ExpressionAttributeValues=valores_expr,
    )

    status = 200 if ja_existe else 201
    return _resposta(status, {"equipamento_id": equipamento_id, "atualizado": ja_existe})
