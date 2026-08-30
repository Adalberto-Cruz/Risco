"""
riskai-preditor-quebra
----------------------
Roda 1x/dia via EventBridge, depois da Lambda de agregacao diaria.

Para cada equipamento em riskai-equipamentos que ja tem um score_risco_atual
(escrito pela agregacao diaria), busca/atualiza o clima da regiao via
Open-Meteo, escolhe o nivel de Cox mais completo que os dados permitem, roda
o modelo e grava de volta prob_quebra_90d, perda_esperada_90d e o
risco_bucket="GLOBAL" usado pelo ranking.

Pressupoe que riskai-equipamentos ja tem, por equipamento (cadastro feito
pelo app/site no momento em que o maquinario e registrado):
    equipamento_id, tipo, idade_dias, dias_desde_manutencao, qtd_manutencoes,
    valor_pago, rendimento_pct, latitude, longitude
e que a Lambda de agregacao diaria mantem atualizado:
    score_risco_atual
"""
import json
import os
import time
from datetime import date, timedelta
from decimal import Decimal

import boto3
import joblib
import pandas as pd
import requests

MODELOS_DIR = os.path.join(os.path.dirname(__file__), "modelos_treinados")
TABELA_EQUIPAMENTOS = os.environ.get("TABELA_EQUIPAMENTOS", "riskai-equipamentos")
HORAS_CACHE_CLIMA = int(os.environ.get("HORAS_CACHE_CLIMA", "168"))  # 7 dias
TEMP_EXTREMA_C = 33.0

dynamodb = boto3.resource("dynamodb")
tabela = dynamodb.Table(TABELA_EQUIPAMENTOS)

with open(os.path.join(MODELOS_DIR, "metadata.json"), encoding="utf-8") as f:
    METADATA = json.load(f)

MODELOS_COX = {
    nivel: joblib.load(os.path.join(MODELOS_DIR, f"{nivel}.pkl"))
    for nivel in ["cox_nivel1", "cox_nivel2", "cox_nivel3"]
}
HORIZONTE_DIAS = METADATA["horizonte_dias"]


def escolher_nivel(item):
    """Usa o nivel de Cox mais completo (3 > 2 > 1) que os dados do equipamento permitem."""
    for nivel in ["cox_nivel3", "cox_nivel2", "cox_nivel1"]:
        features = METADATA["cox"][nivel]["features"]
        if all(item.get(f) is not None for f in features):
            return nivel, features
    return None, None


def buscar_clima_open_meteo(lat, lon, dias=365, tentativas=3):
    fim = date.today() - timedelta(days=2)
    inicio = fim - timedelta(days=dias)
    url = "https://archive-api.open-meteo.com/v1/archive"
    params = {
        "latitude": lat,
        "longitude": lon,
        "start_date": inicio.isoformat(),
        "end_date": fim.isoformat(),
        "daily": "temperature_2m_mean,temperature_2m_max,temperature_2m_min,"
                 "relative_humidity_2m_mean,precipitation_sum",
        "timezone": "auto",
    }
    for tentativa in range(tentativas):
        resp = requests.get(url, params=params, timeout=20)
        if resp.status_code == 200:
            return resp.json()
        time.sleep(2 * (tentativa + 1))
    resp.raise_for_status()


def resumir_clima(dados_diarios):
    diario = dados_diarios["daily"]
    temp_media = pd.Series(diario["temperature_2m_mean"], dtype=float)
    temp_max = pd.Series(diario["temperature_2m_max"], dtype=float)
    temp_min = pd.Series(diario["temperature_2m_min"], dtype=float)
    umidade = pd.Series(diario["relative_humidity_2m_mean"], dtype=float)
    chuva = pd.Series(diario["precipitation_sum"], dtype=float)
    n_dias = max(len(temp_media), 1)
    meses = max(n_dias / 30.0, 1e-6)
    return {
        "temp_media_regiao": round(float(temp_media.mean()), 1),
        "umidade_media_regiao": round(float(umidade.mean()), 1),
        "indice_chuva_mm_mes": round(float(chuva.sum()) / meses, 1),
        "dias_extremos_calor_mes": round(float((temp_max > TEMP_EXTREMA_C).sum()) / meses, 1),
        "amplitude_termica_diaria": round(float((temp_max - temp_min).mean()), 1),
    }


def clima_desatualizado(item):
    atualizado_em = item.get("clima_atualizado_em")
    if atualizado_em is None:
        return True
    return (time.time() - float(atualizado_em)) / 3600.0 > HORAS_CACHE_CLIMA


def garantir_clima(item):
    """So bate na Open-Meteo se o clima desse equipamento estiver com mais de
    HORAS_CACHE_CLIMA (padrao 7 dias) — clima historico nao muda de um dia pro outro."""
    if "latitude" not in item or "longitude" not in item:
        return item
    if not clima_desatualizado(item):
        return item
    resumo = resumir_clima(buscar_clima_open_meteo(float(item["latitude"]), float(item["longitude"])))
    item.update(resumo)
    item["clima_atualizado_em"] = time.time()
    return item


def calcular_prob_quebra(item, nivel, features):
    linha = pd.DataFrame([{f: float(item[f]) for f in features}])
    cph = MODELOS_COX[nivel]
    sobrevivencia = cph.predict_survival_function(linha, times=[HORIZONTE_DIAS])
    return round(float(1 - sobrevivencia.T[HORIZONTE_DIAS].values[0]), 4)


def to_decimal(valor):
    return Decimal(str(valor)) if isinstance(valor, float) else valor


def lambda_handler(event, context):
    processados, pulados, erros = 0, 0, []
    scan_kwargs = {}

    while True:
        resposta = tabela.scan(**scan_kwargs)
        for item in resposta.get("Items", []):
            equipamento_id = item["equipamento_id"]
            try:
                if item.get("score_risco_atual") is None:
                    pulados += 1
                    continue

                item = garantir_clima(item)
                item["chuva_x_manut_atrasada"] = float(item.get("indice_chuva_mm_mes", 0)) * (
                    float(item.get("dias_desde_manutencao", 0)) / 365.0
                )
                item["score_risco"] = float(item["score_risco_atual"])

                nivel, features = escolher_nivel(item)
                if nivel is None:
                    erros.append({"equipamento_id": equipamento_id,
                                  "erro": "dados insuficientes para qualquer nivel de Cox"})
                    continue

                prob_quebra_90d = calcular_prob_quebra(item, nivel, features)
                perda_esperada_90d = round(prob_quebra_90d * float(item.get("valor_pago", 0)), 2)

                tabela.update_item(
                    Key={"equipamento_id": equipamento_id},
                    UpdateExpression=(
                        "SET prob_quebra_90d = :p, perda_esperada_90d = :l, "
                        "risco_bucket = :b, nivel_cox_usado = :n, ultima_atualizacao = :t, "
                        "temp_media_regiao = :tm, umidade_media_regiao = :um, "
                        "indice_chuva_mm_mes = :ic, dias_extremos_calor_mes = :de, "
                        "amplitude_termica_diaria = :at, clima_atualizado_em = :ca"
                    ),
                    ExpressionAttributeValues={
                        ":p": to_decimal(prob_quebra_90d),
                        ":l": to_decimal(perda_esperada_90d),
                        ":b": "GLOBAL",
                        ":n": nivel,
                        ":t": to_decimal(time.time()),
                        ":tm": to_decimal(item.get("temp_media_regiao")),
                        ":um": to_decimal(item.get("umidade_media_regiao")),
                        ":ic": to_decimal(item.get("indice_chuva_mm_mes")),
                        ":de": to_decimal(item.get("dias_extremos_calor_mes")),
                        ":at": to_decimal(item.get("amplitude_termica_diaria")),
                        ":ca": to_decimal(item.get("clima_atualizado_em")),
                    },
                )
                processados += 1
            except Exception as e:
                erros.append({"equipamento_id": equipamento_id, "erro": str(e)})

        if "LastEvaluatedKey" not in resposta:
            break
        scan_kwargs["ExclusiveStartKey"] = resposta["LastEvaluatedKey"]

    resultado = {"processados": processados, "pulados_sem_score": pulados, "erros": erros}
    print(json.dumps(resultado, ensure_ascii=False))
    return {"statusCode": 200, "body": json.dumps(resultado, ensure_ascii=False)}
