package com.example.sompo

data class MaquinarioPayload(
    val equipamento_id: String,
    val tipo: String,
    val latitude: Double,
    val longitude: Double,
    val valor_pago: Double,
    val idade_dias: Int,
    val dias_desde_manutencao: Int,
    val qtd_manutencoes: Int,
    val rendimento_pct: Double
)
