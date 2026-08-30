package com.example.sompo.ui.theme

import androidx.compose.ui.graphics.Color

val SompoVermelho = Color(0xFFC62828)       // primário — aproximação do vermelho Sompo
val SompoVermelhoEscuro = Color(0xFF8E0000)
val SompoVermelhoClaro = Color(0xFFE53935)

val CorRiscoAlto = Color(0xFFD32F2F)
val CorRiscoMedio = Color(0xFFF9A825)
val CorRiscoBaixo = Color(0xFF388E3C)

// Gradientes dos cards — nenhum usa azul
val GradienteEquipamentos = listOf(SompoVermelhoEscuro, SompoVermelhoClaro)
val GradienteRiscoAlto = listOf(Color(0xFFFF6F00), Color(0xFFD32F2F))
val GradientePerdaEsperada = listOf(Color(0xFFF9A825), Color(0xFFFB8C00))

fun corPorRisco(nivel: String) = when (nivel) {
    "alto" -> CorRiscoAlto
    "medio" -> CorRiscoMedio
    "baixo" -> CorRiscoBaixo
    else -> Color(0xFF9E9E9E) // "pendente"
}
