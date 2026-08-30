package com.example.sompo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sompo.ui.theme.*
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries

@Composable
fun DashboardScreen(viewModel: RankingViewModel) {
    val estado by viewModel.estado.collectAsState()
    val etapa by viewModel.etapaSimulacao.collectAsState()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.testarDadosSimulados(20) },
                containerColor = SompoVermelho,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Science, contentDescription = null) },
                text = { Text("Testar dados simulados") }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val state = estado) {
                is RankingUiState.Carregando -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = SompoVermelho)
                }

                is RankingUiState.Erro -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Warning, null, tint = CorRiscoAlto)
                        Spacer(Modifier.height(8.dp))
                        Text(state.mensagem)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.recarregar() },
                            colors = ButtonDefaults.buttonColors(containerColor = SompoVermelho)
                        ) { Text("Tentar novamente") }
                    }
                }

                is RankingUiState.Sucesso -> {
                    val itens = state.itens
                    val resumo = remember(itens) { ResumoDashboard.calcularDe(itens) }

                    LazyColumn(
                        Modifier.fillMaxSize().background(Color(0xFFF7F7F9)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item { CardsResumo(resumo) }
                        item { PizzaRiscoCard(itens) }
                        item { RankingBarrasCard(itens) }
                        item { Text("Detalhes", style = MaterialTheme.typography.titleMedium) }
                        items(itens) { item -> CardEquipamento(item) }
                    }
                }
            }
        }
    }

    etapa?.let { DialogoSimulacao(it, onFechar = { viewModel.fecharSimulacao() }) }
}

@Composable
private fun DialogoSimulacao(etapa: EtapaSimulacao, onFechar: () -> Unit) {
    val etapas = listOf(
        "Cadastrando equipamentos" to (etapa is EtapaSimulacao.Cadastrando),
        "Enviando leituras de telemetria" to (etapa is EtapaSimulacao.EnviandoTelemetria),
        "Processando na AWS (classificação + Cox)" to (etapa is EtapaSimulacao.ProcessandoNaAws),
        "Atualizando dashboard" to (etapa is EtapaSimulacao.AtualizandoDashboard),
    )
    val indiceAtual = when (etapa) {
        is EtapaSimulacao.Cadastrando -> 0
        is EtapaSimulacao.EnviandoTelemetria -> 1
        is EtapaSimulacao.ProcessandoNaAws -> 2
        is EtapaSimulacao.AtualizandoDashboard -> 3
        else -> 4 // concluído ou falhou: tudo já passou
    }

    AlertDialog(
        onDismissRequest = { if (etapa is EtapaSimulacao.Concluido || etapa is EtapaSimulacao.Falhou) onFechar() },
        confirmButton = {
            if (etapa is EtapaSimulacao.Concluido || etapa is EtapaSimulacao.Falhou) {
                TextButton(onClick = onFechar) { Text("Fechar") }
            }
        },
        title = { Text("Testar dados simulados") },
        text = {
            Column {
                etapas.forEachIndexed { i, (rotulo, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        when {
                            i < indiceAtual -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CorRiscoBaixo)
                            i == indiceAtual && etapa !is EtapaSimulacao.Concluido && etapa !is EtapaSimulacao.Falhou ->
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SompoVermelho)
                            else -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = Color.LightGray)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(rotulo, color = if (i <= indiceAtual) Color.Black else Color.Gray)
                    }
                }

                if (etapa is EtapaSimulacao.Concluido) {
                    Spacer(Modifier.height(12.dp))
                    Text("✓ Dashboard atualizado com dados reais da AWS.", color = CorRiscoBaixo)
                    if (etapa.resultado.falhas.isNotEmpty()) {
                        Text("(${etapa.resultado.falhas.size} itens com falha — não bloqueou o restante)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                if (etapa is EtapaSimulacao.Falhou) {
                    Spacer(Modifier.height(12.dp))
                    Text("Falhou na etapa: ${etapa.etapa}", color = CorRiscoAlto)
                    Text(etapa.mensagem, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

@Composable
private fun CardsResumo(resumo: ResumoDashboard) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CardGradiente("Equipamentos", resumo.totalEquipamentos.toString(), GradienteEquipamentos, Modifier.weight(1f))
        CardGradiente("Risco alto", resumo.emRiscoAlto.toString(), GradienteRiscoAlto, Modifier.weight(1f))
        CardGradiente("Perda esperada", "R$ ${"%,.0f".format(resumo.perdaEsperadaTotal)}", GradientePerdaEsperada, Modifier.weight(1f))
    }
}

@Composable
private fun CardGradiente(titulo: String, valor: String, cores: List<Color>, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(cores))
            .padding(14.dp)
    ) {
        Column {
            Text(titulo, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(valor, color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun PizzaRiscoCard(itens: List<RankingItem>) {
    val porRisco = remember(itens) { itens.groupingBy { it.nivelRisco }.eachCount() }
    val ordem = listOf("alto", "medio", "baixo", "pendente").filter { (porRisco[it] ?: 0) > 0 }
    val total = itens.size.toFloat()

    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Distribuição por risco", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (itens.isEmpty()) {
                Text("Sem dados ainda", color = Color.Gray)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Canvas(modifier = Modifier.size(100.dp)) {
                        var startAngle = -90f
                        ordem.forEach { nivel ->
                            val qtd = porRisco[nivel] ?: 0
                            val sweepAngle = (qtd / total) * 360f
                            drawArc(
                                color = corPorRisco(nivel),
                                startAngle = startAngle,
                                sweepAngle = sweepAngle,
                                useCenter = true
                            )
                            startAngle += sweepAngle
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ordem.forEach { nivel ->
                            val qtd = porRisco[nivel] ?: 0
                            val pct = if (total > 0) (qtd / total * 100) else 0f
                            LegendaRisco(nivel, qtd, pct, corPorRisco(nivel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendaRisco(nivel: String, qtd: Int, pct: Float, cor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(cor, shape = CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("${nivel.replaceFirstChar { it.uppercase() }}: $qtd (${"%.0f".format(pct)}%)", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RankingBarrasCard(itens: List<RankingItem>) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val topItens = remember(itens) {
        itens.filter { it.probQuebra90d != null }
            .sortedByDescending { it.probQuebra90d }
            .take(10)
    }

    LaunchedEffect(topItens) {
        if (topItens.isNotEmpty()) {
            modelProducer.runTransaction {
                columnSeries { series(topItens.map { (it.probQuebra90d ?: 0.0) * 100 }) }
            }
        }
    }

    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Top 10 — probabilidade de quebra (90d)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (topItens.isEmpty()) {
                Text("Nenhum equipamento com predição calculada ainda", color = Color.Gray)
            } else {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(),
                    ),
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )
            }
        }
    }
}

@Composable
private fun CardEquipamento(item: RankingItem) {
    ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(item.equipamentoId, style = MaterialTheme.typography.bodyLarge)
                Text(item.tipo, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    item.probQuebra90d?.let { "%.1f%%".format(it * 100) } ?: "pendente",
                    color = corPorRisco(item.nivelRisco)
                )
                item.perdaEsperada90d?.let {
                    Text("R$ ${"%,.0f".format(it)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}
