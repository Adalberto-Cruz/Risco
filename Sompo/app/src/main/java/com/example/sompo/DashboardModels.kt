package com.example.sompo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RankingResponse(
    val ranking: List<RankingItemBruto>
)

data class RankingItemBruto(
    val equipamento_id: String,
    val tipo: String,
    val prob_quebra_90d: Double? = null,
    val perda_esperada_90d: Double? = null
)

data class RankingItem(
    val equipamentoId: String,
    val tipo: String,
    val probQuebra90d: Double?,
    val perdaEsperada90d: Double?,
    val nivelRisco: String // "alto" | "medio" | "baixo" | "pendente"
)

object RiscoMapper {
    fun paraRankingItem(bruto: RankingItemBruto): RankingItem {
        val nivel = when {
            bruto.prob_quebra_90d == null -> "pendente"
            bruto.prob_quebra_90d >= 0.5 -> "alto"
            bruto.prob_quebra_90d >= 0.2 -> "medio"
            else -> "baixo"
        }
        return RankingItem(
            equipamentoId = bruto.equipamento_id,
            tipo = bruto.tipo,
            probQuebra90d = bruto.prob_quebra_90d,
            perdaEsperada90d = bruto.perda_esperada_90d,
            nivelRisco = nivel
        )
    }
}

data class ResumoDashboard(
    val totalEquipamentos: Int,
    val emRiscoAlto: Int,
    val perdaEsperadaTotal: Double
) {
    companion object {
        fun calcularDe(itens: List<RankingItem>) = ResumoDashboard(
            totalEquipamentos = itens.size,
            emRiscoAlto = itens.count { it.nivelRisco == "alto" },
            perdaEsperadaTotal = itens.sumOf { it.perdaEsperada90d ?: 0.0 }
        )
    }
}

sealed interface RankingUiState {
    data object Carregando : RankingUiState
    data class Sucesso(val itens: List<RankingItem>) : RankingUiState
    data class Erro(val mensagem: String) : RankingUiState
}

sealed interface EtapaSimulacao {
    data object Cadastrando : EtapaSimulacao       // POST /maquinarios x 20
    data object EnviandoTelemetria : EtapaSimulacao // POST /telemetria x ~98
    data object ProcessandoNaAws : EtapaSimulacao   // POST /processar (agregação + Cox)
    data object AtualizandoDashboard : EtapaSimulacao // GET /ranking de novo
    data class Concluido(val resultado: ResultadoSeed) : EtapaSimulacao
    data class Falhou(val etapa: String, val mensagem: String) : EtapaSimulacao
}

class RankingViewModel(
    private val awsUploader: AwsUploader
) : ViewModel() {

    private val _estado = MutableStateFlow<RankingUiState>(RankingUiState.Carregando)
    val estado: StateFlow<RankingUiState> = _estado.asStateFlow()

    private val _etapaSimulacao = MutableStateFlow<EtapaSimulacao?>(null)
    val etapaSimulacao: StateFlow<EtapaSimulacao?> = _etapaSimulacao.asStateFlow()

    init {
        carregarRanking()
    }

    fun recarregar() = carregarRanking()

    private fun carregarRanking(limite: Int = 20) {
        _estado.value = RankingUiState.Carregando
        viewModelScope.launch {
            try {
                val itens = awsUploader.buscarRanking(limite)
                _estado.value = if (itens.isEmpty()) {
                    RankingUiState.Erro("Nenhum equipamento com dados de risco ainda. Popule dados de teste ou aguarde o cálculo diário.")
                } else {
                    RankingUiState.Sucesso(itens)
                }
            } catch (e: retrofit2.HttpException) {
                val msg = when (e.code()) {
                    401, 403 -> "Sessão expirada. Faça login novamente."
                    else -> "Erro do servidor (HTTP ${e.code()})."
                }
                _estado.value = RankingUiState.Erro(msg)
            } catch (e: java.io.IOException) {
                _estado.value = RankingUiState.Erro("Sem conexão com a internet. Verifique o sinal e tente novamente.")
            } catch (e: Exception) {
                _estado.value = RankingUiState.Erro(e.message ?: "Erro inesperado ao buscar ranking.")
            }
        }
    }

    fun testarDadosSimulados(quantidade: Int = 20) {
        if (_etapaSimulacao.value != null &&
            _etapaSimulacao.value !is EtapaSimulacao.Concluido &&
            _etapaSimulacao.value !is EtapaSimulacao.Falhou) return // evita clique duplo

        viewModelScope.launch {
            try {
                _etapaSimulacao.value = EtapaSimulacao.Cadastrando
                val resultadoCadastro = awsUploader.popularDadosDeTeste(quantidade)
                if (resultadoCadastro.sucesso == 0) {
                    _etapaSimulacao.value = EtapaSimulacao.Falhou("cadastro", "Nenhum equipamento cadastrado. Confira a conexão.")
                    return@launch
                }

                _etapaSimulacao.value = EtapaSimulacao.EnviandoTelemetria
                val idsSimulados = (1..quantidade).map { "sim-equip-%03d".format(it) }
                val resultadoTelemetria = awsUploader.popularTelemetriaDeTeste(idsSimulados)

                _etapaSimulacao.value = EtapaSimulacao.ProcessandoNaAws
                awsUploader.processarNaAws() // POST /processar - roda agregação + Cox de verdade

                _etapaSimulacao.value = EtapaSimulacao.AtualizandoDashboard
                carregarRanking() // recarrega o GET /ranking com os resultados novos

                _etapaSimulacao.value = EtapaSimulacao.Concluido(
                    ResultadoSeed(
                        sucesso = resultadoCadastro.sucesso,
                        falhas = resultadoCadastro.falhas + resultadoTelemetria.falhas
                    )
                )
            } catch (e: Exception) {
                _etapaSimulacao.value = EtapaSimulacao.Falhou(
                    etapa = "processamento",
                    mensagem = e.message ?: "Erro inesperado ao processar na AWS"
                )
            }
        }
    }

    fun fecharSimulacao() {
        _etapaSimulacao.value = null
    }
}
