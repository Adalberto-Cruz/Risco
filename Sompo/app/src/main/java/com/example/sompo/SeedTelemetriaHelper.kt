package com.example.sompo

import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

object SeedTelemetriaHelper {

    private enum class FaixaRisco { ALTO, MEDIO, BAIXO }

    fun gerarLeiturasSimuladas(
        equipamentoIds: List<String>,
        leiturasPorEquipamento: IntRange = 3..5
    ): List<TelemetriaPayload> {
        val leituras = mutableListOf<TelemetriaPayload>()

        equipamentoIds.forEachIndexed { index, equipamentoId ->
            // Distribui ~1/3 pra cada faixa
            val faixa = when (index % 3) {
                0 -> FaixaRisco.ALTO
                1 -> FaixaRisco.MEDIO
                else -> FaixaRisco.BAIXO
            }
            val qtd = Random.nextInt(leiturasPorEquipamento.first, leiturasPorEquipamento.last + 1)

            repeat(qtd) {
                leituras.add(gerarLeitura(equipamentoId, faixa))
                Thread.sleep(2) // garante timestamp_envio distinto por leitura (leitura_id único)
            }
        }
        return leituras
    }

    private fun gerarLeitura(equipamentoId: String, faixa: FaixaRisco): TelemetriaPayload {
        // distancia pequena = objeto perto = mais risco (é o que hoje domina o nivel_risco)
        val distanciaCm = when (faixa) {
            FaixaRisco.ALTO -> Random.nextDouble(3.0, 15.0)
            FaixaRisco.MEDIO -> Random.nextDouble(25.0, 60.0)
            FaixaRisco.BAIXO -> Random.nextDouble(100.0, 300.0)
        }
        // giro/vibração amplificados pra alto (fora da faixa pequena do mock do ESP32,
        // já que aqui simulamos direto o payload calculado, não o firmware)
        val (giroX, giroY, giroZ) = when (faixa) {
            FaixaRisco.ALTO -> Triple(Random.nextDouble(-40.0, 40.0), Random.nextDouble(-40.0, 40.0), Random.nextDouble(-40.0, 40.0))
            FaixaRisco.MEDIO -> Triple(Random.nextDouble(-10.0, 10.0), Random.nextDouble(-10.0, 10.0), Random.nextDouble(-10.0, 10.0))
            FaixaRisco.BAIXO -> Triple(Random.nextDouble(-2.0, 2.0), Random.nextDouble(-2.0, 2.0), Random.nextDouble(-2.0, 2.0))
        }
        val (vibX, vibY, vibZ) = when (faixa) {
            FaixaRisco.ALTO -> Triple(Random.nextDouble(-3.0, 3.0), Random.nextDouble(-3.0, 3.0), 9.81 + Random.nextDouble(-3.0, 3.0))
            FaixaRisco.MEDIO -> Triple(Random.nextDouble(-1.0, 1.0), Random.nextDouble(-1.0, 1.0), 9.81 + Random.nextDouble(-1.0, 1.0))
            FaixaRisco.BAIXO -> Triple(Random.nextDouble(-0.1, 0.1), Random.nextDouble(-0.1, 0.1), 9.81 + Random.nextDouble(-0.1, 0.1))
        }

        val timestampEnvio = System.currentTimeMillis()
        return TelemetriaPayload(
            maquinario_id = equipamentoId,
            leitura_id = "${equipamentoId}_${timestampEnvio}",
            temperatura_c = Random.nextDouble(15.0, 45.0),
            umidade_pct = Random.nextDouble(20.0, 90.0),
            distancia_cm = distanciaCm,
            mpu_temp_interna_c = Random.nextDouble(20.0, 60.0),
            mpu_vibracao_g = sqrt(vibX * vibX + vibY * vibY + (vibZ - 9.81) * (vibZ - 9.81)) / 9.81,
            mpu_inclinacao_graus = Math.toDegrees(atan2(sqrt(vibX * vibX + vibY * vibY), vibZ)),
            mpu_velocidade_graus_s = sqrt(giroX * giroX + giroY * giroY + giroZ * giroZ),
            status_turno = listOf("trabalhando", "parado").random()
        )
    }
}
