package com.example.sompo

data class TelemetriaPayload(
    val leitura_id: String,
    val maquinario_id: String,
    val temperatura_c: Double,
    val umidade_pct: Double,
    val distancia_cm: Double,
    val mpu_temp_interna_c: Double,
    val mpu_vibracao_g: Double,
    val mpu_inclinacao_graus: Double,
    val mpu_velocidade_graus_s: Double,
    val status_turno: String
)
