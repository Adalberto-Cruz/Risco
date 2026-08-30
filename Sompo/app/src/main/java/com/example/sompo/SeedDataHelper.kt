package com.example.sompo

import kotlin.random.Random

object SeedDataHelper {

    // Coordenada fixa — ajuste pra região real da fazenda/cliente
    private const val LATITUDE_FIXA = -23.5505
    private const val LONGITUDE_FIXA = -46.6333

    private val TIPOS = listOf("trator", "colheitadeira", "pulverizador", "plantadeira")

    fun gerarEquipamentosSimulados(quantidade: Int = 20): List<MaquinarioPayload> {
        return (1..quantidade).map { i ->
            MaquinarioPayload(
                equipamento_id = "sim-equip-%03d".format(i),
                tipo = TIPOS.random(),
                latitude = LATITUDE_FIXA,
                longitude = LONGITUDE_FIXA,
                valor_pago = Random.nextDouble(15000.0, 250000.0),
                idade_dias = Random.nextInt(30, 3650),          // 1 mês a 10 anos
                dias_desde_manutencao = Random.nextInt(0, 400),  // inclui casos "vencidos"
                qtd_manutencoes = Random.nextInt(0, 20),
                rendimento_pct = Random.nextDouble(40.0, 100.0)
            )
        }
    }
}

// Resultado de uma rodada de seed, pra reportar sucesso/erro por item
data class ResultadoSeed(
    val sucesso: Int,
    val falhas: List<Pair<String, String>> // equipamento_id, mensagem de erro
)
