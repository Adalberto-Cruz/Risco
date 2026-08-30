package com.example.sompo

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.step.AuthSignInStep
import com.amplifyframework.kotlin.core.Amplify
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

class AwsUploader {

    val ultimoStatus = mutableStateOf("Aguardando primeiro envio...")

    private val logging = HttpLoggingInterceptor { message ->
        Log.d("SompoAppHttp", message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://cimk32ki93.execute-api.us-east-1.amazonaws.com/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(TelemetriaApi::class.java)
    private val maquinarioApi = retrofit.create(MaquinarioApi::class.java)
    private val rankingApi = retrofit.create(RankingApi::class.java)
    private val processarApi = retrofit.create(ProcessarApi::class.java)

    // Gravidade padrao (m/s^2), usada para separar vibracao real da leitura estatica do eixo Z
    private val GRAVIDADE_MS2 = 9.81

    suspend fun autenticar(): String {
        return obterTokenValido() ?: throw IllegalStateException("Falha ao obter token de autenticação")
    }

    private suspend fun obterTokenValido(): String? {
        try {
            var sessao = Amplify.Auth.fetchAuthSession() as AWSCognitoAuthSession
            val resultadoToken = sessao.userPoolTokensResult
            Log.d("SompoApp", "userPoolTokensResult.type=${resultadoToken.type} error=${resultadoToken.error}")
            var token = resultadoToken.value?.idToken

            // Se não estiver autenticado ou sem token, realiza o login automático em segundo plano
            if (token == null || !sessao.isSignedIn) {
                try {
                    var resultado = Amplify.Auth.signIn(
                        BuildConfig.AWS_TEST_EMAIL,
                        BuildConfig.AWS_TEST_PASSWORD
                    )
                    if (resultado.nextStep.signInStep == AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD) {
                        Amplify.Auth.confirmSignIn(BuildConfig.AWS_TEST_PASSWORD)
                    }
                    sessao = Amplify.Auth.fetchAuthSession() as AWSCognitoAuthSession
                    token = sessao.userPoolTokensResult.value?.idToken
                } catch (e: Exception) {
                    Log.w("SompoApp", "Aviso ao obter sessao/login: ${e.message}")
                    sessao = Amplify.Auth.fetchAuthSession() as AWSCognitoAuthSession
                    token = sessao.userPoolTokensResult.value?.idToken
                }
            }
            return token
        } catch (e: Exception) {
            Log.e("SompoApp", "Erro ao obter token valido", e)
            return null
        }
    }

    private fun montarPayload(leitura: LeituraSensor): TelemetriaPayload {
        val velocidadeAngular = sqrt(
            leitura.giroX * leitura.giroX +
            leitura.giroY * leitura.giroY +
            leitura.giroZ * leitura.giroZ
        )

        val inclinacaoGraus = Math.toDegrees(
            atan2(
                sqrt(leitura.vibracaoX * leitura.vibracaoX + leitura.vibracaoY * leitura.vibracaoY),
                leitura.vibracaoZ
            )
        )

        val vibracaoG = sqrt(
            leitura.vibracaoX * leitura.vibracaoX +
            leitura.vibracaoY * leitura.vibracaoY +
            (leitura.vibracaoZ - GRAVIDADE_MS2) * (leitura.vibracaoZ - GRAVIDADE_MS2)
        ) / GRAVIDADE_MS2

        val distanciaCm = min(leitura.distanciaEsquerdaMm, leitura.distanciaDireitaMm) / 10.0

        val timestampEnvio = System.currentTimeMillis()
        val leituraId = "${leitura.equipamentoId}_$timestampEnvio"

        return TelemetriaPayload(
            leitura_id = leituraId,
            maquinario_id = leitura.equipamentoId,
            temperatura_c = leitura.temperatura,
            umidade_pct = leitura.umidade,
            distancia_cm = distanciaCm,
            mpu_temp_interna_c = leitura.mpuTempInternaC,
            mpu_vibracao_g = vibracaoG,
            mpu_inclinacao_graus = inclinacaoGraus,
            mpu_velocidade_graus_s = velocidadeAngular,
            status_turno = leitura.statusTurno
        )
    }

    suspend fun enviar(leitura: LeituraSensor) {
        try {
            val token = obterTokenValido()
            if (token == null) {
                val msg = "Erro: usuário não autenticado (faça login)"
                ultimoStatus.value = msg
                Log.e("SompoApp", msg)
                return
            }

            val payload = montarPayload(leitura)

            Log.d("SompoApp", "Enviando payload para AWS: $payload")
            val resposta = api.enviar("Bearer $token", payload)

            if (resposta.isSuccessful) {
                val msg = "Enviado com sucesso (HTTP ${resposta.code()})"
                ultimoStatus.value = msg
                Log.i("SompoApp", "AWS Upload OK: $msg")
            } else {
                val errBody = resposta.errorBody()?.string() ?: ""
                val msg = "Erro ao enviar: HTTP ${resposta.code()} $errBody"
                ultimoStatus.value = msg
                Log.e("SompoApp", "AWS Upload Erro: $msg")
            }
        } catch (e: Exception) {
            val msg = "Erro no envio AWS: ${e.message}"
            ultimoStatus.value = msg
            Log.e("SompoApp", msg, e)
        }
    }

    suspend fun buscarRanking(limite: Int = 20): List<RankingItem> {
        val token = obterTokenValido() ?: throw IllegalStateException("usuário não autenticado")
        val resposta = rankingApi.buscarRanking("Bearer $token", limite)
        if (!resposta.isSuccessful) throw retrofit2.HttpException(resposta)
        val bruto = resposta.body()?.ranking ?: emptyList()
        return bruto.map { RiscoMapper.paraRankingItem(it) }
    }

    suspend fun processarNaAws() {
        val token = obterTokenValido() ?: throw IllegalStateException("usuário não autenticado")
        val resposta = processarApi.processar("Bearer $token")
        if (!resposta.isSuccessful) {
            throw retrofit2.HttpException(resposta)
        }
    }

    suspend fun popularTelemetriaDeTeste(equipamentoIds: List<String>): ResultadoSeed {
        val token = obterTokenValido() ?: return ResultadoSeed(0, listOf("auth" to "usuário não autenticado"))
        val leituras = SeedTelemetriaHelper.gerarLeiturasSimuladas(equipamentoIds)

        var sucesso = 0
        val falhas = mutableListOf<Pair<String, String>>()
        for (leitura in leituras) {
            try {
                val resposta = api.enviar("Bearer $token", leitura)
                if (resposta.isSuccessful) sucesso++
                else falhas.add(leitura.leitura_id to "HTTP ${resposta.code()}")
            } catch (e: Exception) {
                falhas.add(leitura.leitura_id to (e.message ?: "erro desconhecido"))
            }
        }
        return ResultadoSeed(sucesso, falhas)
    }

    suspend fun popularDadosDeTeste(quantidade: Int = 20): ResultadoSeed {
        val token = obterTokenValido()
            ?: return ResultadoSeed(0, listOf("auth" to "usuário não autenticado"))

        val equipamentos = SeedDataHelper.gerarEquipamentosSimulados(quantidade)
        var sucesso = 0
        val falhas = mutableListOf<Pair<String, String>>()

        for (payload in equipamentos) {
            try {
                val resposta = maquinarioApi.cadastrarMaquinario("Bearer $token", payload)
                if (resposta.isSuccessful) {
                    sucesso++
                } else {
                    falhas.add(payload.equipamento_id to "HTTP ${resposta.code()}")
                }
            } catch (e: Exception) {
                falhas.add(payload.equipamento_id to (e.message ?: "erro desconhecido"))
            }
        }

        return ResultadoSeed(sucesso, falhas)
    }
}
