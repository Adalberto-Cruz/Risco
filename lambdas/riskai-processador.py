import json
import boto3

LAMBDA_AGREGADOR = "riskai-agregador-diario"
LAMBDA_PREDITOR = "riskai-preditor-quebra"

lambda_client = boto3.client("lambda")


def _invocar(nome_funcao, payload=None):
    resposta = lambda_client.invoke(
        FunctionName=nome_funcao,
        InvocationType="RequestResponse",
        Payload=json.dumps(payload or {}).encode("utf-8"),
    )
    corpo_bruto = resposta["Payload"].read()
    resultado = json.loads(corpo_bruto)

    if resposta.get("FunctionError"):
        raise RuntimeError(f"{nome_funcao} falhou internamente: {resultado}")

    if isinstance(resultado, dict) and "body" in resultado:
        return json.loads(resultado["body"])
    return resultado


def lambda_handler(event, context):
    try:
        resultado_agregacao = _invocar(LAMBDA_AGREGADOR)
    except Exception as e:
        return _resposta(502, {"etapa": "agregacao", "erro": str(e)})

    try:
        resultado_cox = _invocar(LAMBDA_PREDITOR)
    except Exception as e:
        return _resposta(502, {
            "etapa": "cox",
            "erro": str(e),
            "agregacao": resultado_agregacao,
        })

    return _resposta(200, {
        "agregacao": resultado_agregacao,
        "cox": resultado_cox,
    })


def _resposta(status, corpo):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(corpo, ensure_ascii=False),
    }
