import json
import os
from datetime import datetime, timezone, timedelta
from decimal import Decimal

import boto3
from boto3.dynamodb.conditions import Key

TABELA_EQUIPAMENTOS = os.environ.get("TABELA_EQUIPAMENTOS", "riskai-equipamentos")
TABELA_LEITURAS = os.environ.get("TABELA_LEITURAS", "riskai-leituras")
INDICE_POR_MAQUINARIO = os.environ.get("INDICE_POR_MAQUINARIO", "por-maquinario")

dynamodb = boto3.resource("dynamodb")
tabela_equipamentos = dynamodb.Table(TABELA_EQUIPAMENTOS)
tabela_leituras = dynamodb.Table(TABELA_LEITURAS)


def _inicio_e_fim_do_dia_utc():
    agora = datetime.now(timezone.utc)
    inicio = agora.replace(hour=0, minute=0, second=0, microsecond=0)
    fim = inicio + timedelta(days=1)
    return int(inicio.timestamp()), int(fim.timestamp())


def _score_da_leitura(item):
    probs = item.get("probabilidades", {})
    alto = float(probs.get("alto", 0))
    medio = float(probs.get("medio", 0))
    return alto + 0.5 * medio


def lambda_handler(event, context):
    inicio_ts, fim_ts = _inicio_e_fim_do_dia_utc()

    if isinstance(event, dict) and "data_referencia_epoch_inicio" in event:
        inicio_ts = int(event["data_referencia_epoch_inicio"])
        fim_ts = inicio_ts + 86400

    equipamentos = _listar_equipamentos_cadastrados()

    processados = []
    pulados_sem_leitura = []
    erros = []

    for equipamento_id in equipamentos:
        try:
            leituras = _buscar_leituras_do_dia(equipamento_id, inicio_ts, fim_ts)
            if not leituras:
                pulados_sem_leitura.append(equipamento_id)
                continue

            scores = [_score_da_leitura(item) for item in leituras]
            score_maximo = round(max(scores), 4)

            tabela_equipamentos.update_item(
                Key={"equipamento_id": equipamento_id},
                UpdateExpression="SET score_risco_atual = :s, score_atualizado_em = :t",
                ExpressionAttributeValues={
                    ":s": Decimal(str(score_maximo)),
                    ":t": Decimal(str(int(datetime.now(timezone.utc).timestamp()))),
                },
            )
            processados.append({"equipamento_id": equipamento_id, "score_risco_atual": score_maximo,
                                 "leituras_consideradas": len(leituras)})
        except Exception as e:
            erros.append({"equipamento_id": equipamento_id, "erro": str(e)})

    resultado = {
        "processados": len(processados),
        "pulados_sem_leitura": len(pulados_sem_leitura),
        "erros": erros,
        "detalhes": processados,
    }
    return {"statusCode": 200, "body": json.dumps(resultado, ensure_ascii=False)}


def _listar_equipamentos_cadastrados():
    ids = []
    resposta = tabela_equipamentos.scan(ProjectionExpression="equipamento_id")
    ids.extend(item["equipamento_id"] for item in resposta.get("Items", []))
    while "LastEvaluatedKey" in resposta:
        resposta = tabela_equipamentos.scan(
            ProjectionExpression="equipamento_id",
            ExclusiveStartKey=resposta["LastEvaluatedKey"],
        )
        ids.extend(item["equipamento_id"] for item in resposta.get("Items", []))
    return ids


def _buscar_leituras_do_dia(equipamento_id, inicio_ts, fim_ts):
    itens = []
    kwargs = {
        "IndexName": INDICE_POR_MAQUINARIO,
        "KeyConditionExpression": Key("maquinario_id").eq(equipamento_id)
        & Key("timestamp_leitura").between(inicio_ts, fim_ts),
    }
    resposta = tabela_leituras.query(**kwargs)
    itens.extend(resposta.get("Items", []))
    while "LastEvaluatedKey" in resposta:
        kwargs["ExclusiveStartKey"] = resposta["LastEvaluatedKey"]
        resposta = tabela_leituras.query(**kwargs)
        itens.extend(resposta.get("Items", []))
    return itens
