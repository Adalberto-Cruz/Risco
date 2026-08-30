package com.example.sompo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sompo.ui.theme.SompoTheme

// Limiar de distancia (mm) abaixo do qual consideramos "objeto/pessoa perto"
private const val DISTANCIA_ALERTA_MM = 150.0

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val awsUploader = AwsUploader()
        bleManager = BleManager(applicationContext, awsUploader)

        setContent {
            SompoTheme {
                var logado by remember { mutableStateOf(false) }

                if (!logado) {
                    val loginViewModel: LoginViewModel = viewModel(
                        factory = viewModelFactory { initializer { LoginViewModel(awsUploader) } }
                    )
                    LoginScreen(viewModel = loginViewModel, aoEntrarNoApp = { logado = true })
                } else {
                    SompoScaffold(bleManager = bleManager, awsUploader = awsUploader, nomeUsuario = "Adalberto")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.desconectar()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaPrincipal(bleManager: BleManager) {

    val permissoesNecessarias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    var permissaoConcedida by remember { mutableStateOf(false) }

    val launcherPermissao = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultado ->
        permissaoConcedida = resultado.values.all { it }
        if (permissaoConcedida) {
            bleManager.iniciarConexao()
        }
    }

    val status by bleManager.status
    val leitura by bleManager.ultimaLeitura
    val erro by bleManager.mensagemErro
    val statusAws by bleManager.awsUploader.ultimoStatus

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        CartaoStatusConexao(status, erro)

        Spacer(modifier = Modifier.height(8.dp))
        Text("AWS: $statusAws", fontSize = 12.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (permissaoConcedida) bleManager.iniciarConexao()
                else launcherPermissao.launch(permissoesNecessarias)
            },
            enabled = status != StatusConexao.PROCURANDO && status != StatusConexao.CONECTANDO,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when (status) {
                    StatusConexao.CONECTADO -> "Conectado ✓"
                    StatusConexao.PROCURANDO -> "Procurando..."
                    StatusConexao.CONECTANDO -> "Conectando..."
                    else -> "Conectar ao ESP32"
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (status == StatusConexao.CONECTADO) {

            AlertaProximidade(leitura.distanciaEsquerdaMm, leitura.distanciaDireitaMm)

            Spacer(modifier = Modifier.height(12.dp))

            CartaoSensor("Temperatura", if (leitura.temperatura < 0) "Sem leitura" else "${leitura.temperatura} °C")
            CartaoSensor("Umidade", if (leitura.umidade < 0) "Sem leitura" else "${leitura.umidade} %")
            CartaoSensor(
                "Vibração (X/Y/Z)",
                "${"%.2f".format(leitura.vibracaoX)} / ${"%.2f".format(leitura.vibracaoY)} / ${"%.2f".format(leitura.vibracaoZ)}"
            )
            CartaoSensor(
                "Giroscópio (X/Y/Z)",
                "${"%.2f".format(leitura.giroX)} / ${"%.2f".format(leitura.giroY)} / ${"%.2f".format(leitura.giroZ)}"
            )
            CartaoSensor(
                "Temp. Interna MPU",
                if (leitura.mpuTempInternaC < 0) "Sem leitura" else "${"%.1f".format(leitura.mpuTempInternaC)} °C"
            )
            CartaoSensor("Distância Esquerda", "${leitura.distanciaEsquerdaMm.toInt()} mm")
            CartaoSensor("Distância Direita", "${leitura.distanciaDireitaMm.toInt()} mm")
            CartaoSensor("Status do Turno", leitura.statusTurno.replaceFirstChar { it.uppercase() })
            CartaoSensor("Equipamento", leitura.equipamentoId)
        }
    }
}

@Composable
fun CartaoStatusConexao(status: StatusConexao, erro: String?) {
    val (cor, texto) = when (status) {
        StatusConexao.CONECTADO -> Color(0xFF2E7D32) to "🟢 Conectado ao ESP32"
        StatusConexao.PROCURANDO -> Color(0xFFF9A825) to "🟡 Procurando dispositivo..."
        StatusConexao.CONECTANDO -> Color(0xFFF9A825) to "🟡 Conectando..."
        StatusConexao.ERRO -> Color(0xFFC62828) to "🔴 Erro de conexão"
        StatusConexao.DESCONECTADO -> Color(0xFF757575) to "⚪ Desconectado"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(texto, fontWeight = FontWeight.Bold, color = cor)
        if (erro != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(erro, fontSize = 12.sp, color = cor)
        }
    }
}

@Composable
fun AlertaProximidade(distEsquerda: Double, distDireita: Double) {
    val alertaEsquerda = distEsquerda in 0.0..DISTANCIA_ALERTA_MM
    val alertaDireita = distDireita in 0.0..DISTANCIA_ALERTA_MM

    if (alertaEsquerda || alertaDireita) {
        val lado = when {
            alertaEsquerda && alertaDireita -> "ESQUERDA e DIREITA"
            alertaEsquerda -> "ESQUERDA"
            else -> "DIREITA"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFDECEA), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🚨", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Objeto/pessoa detectado à $lado",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun CartaoSensor(rotulo: String, valor: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(rotulo, color = Color.Gray, fontSize = 14.sp)
            Text(valor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
