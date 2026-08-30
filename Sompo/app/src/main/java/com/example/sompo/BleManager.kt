package com.example.sompo

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Precisam ser IDENTICOS aos definidos no firmware do ESP32
val SERVICE_UUID: UUID = UUID.fromString("6532737a-6c11-44b4-8512-5f85353f28c2")
val CHARACTERISTIC_UUID: UUID = UUID.fromString("ee8656c2-b9e2-4289-bdca-8cfd416626c0")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val DEFAULT_SCHEMA = listOf(
    "equipamento_id", "timestamp_ms", "temperatura", "umidade",
    "vibracao_x", "vibracao_y", "vibracao_z",
    "giro_x", "giro_y", "giro_z", "mpu_temp_interna_c",
    "distancia_esquerda_mm", "distancia_direita_mm",
    "status_turno", "mock"
)

data class LeituraSensor(
    val equipamentoId: String = "-",
    val timestampMs: Long = 0,
    val temperatura: Double = -1.0,
    val umidade: Double = -1.0,
    val vibracaoX: Double = 0.0,
    val vibracaoY: Double = 0.0,
    val vibracaoZ: Double = 0.0,
    val giroX: Double = 0.0,
    val giroY: Double = 0.0,
    val giroZ: Double = 0.0,
    val mpuTempInternaC: Double = -1.0,
    val distanciaEsquerdaMm: Double = -1.0,
    val distanciaDireitaMm: Double = -1.0,
    val statusTurno: String = "-",
    val mock: Boolean = false
)

enum class StatusConexao { DESCONECTADO, PROCURANDO, CONECTANDO, CONECTADO, ERRO }

class BleManager(private val context: Context, val awsUploader: AwsUploader) {

    val status = mutableStateOf(StatusConexao.DESCONECTADO)
    val ultimaLeitura = mutableStateOf(LeituraSensor())
    val mensagemErro = mutableStateOf<String?>(null)
    val historico = mutableStateListOf<LeituraSensor>()
    val mensagemSync = mutableStateOf<String?>(null)
    private val LIMITE_HISTORICO = 100
    private var schemaAtual: List<String> = DEFAULT_SCHEMA

    private var bluetoothGatt: BluetoothGatt? = null
    private val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private fun Any?.toDoubleVal(default: Double = 0.0): Double {
        return when (this) {
            is Number -> this.toDouble()
            is String -> this.toDoubleOrNull() ?: default
            else -> default
        }
    }

    private fun Any?.toLongVal(default: Long = 0L): Long {
        return when (this) {
            is Number -> this.toLong()
            is String -> this.toLongOrNull() ?: default
            else -> default
        }
    }

    @SuppressLint("MissingPermission")
    fun iniciarConexao() {
        mensagemErro.value = null
        status.value = StatusConexao.PROCURANDO

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.value = StatusConexao.ERRO
            mensagemErro.value = "Bluetooth desligado. Ative o Bluetooth do celular."
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status.value = StatusConexao.ERRO
            mensagemErro.value = "Bluetooth LE Scanner não disponível."
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        // Timeout de seguranca: se nao achar em 10s, avisa e para o scan
        android.os.Handler(context.mainLooper).postDelayed({
            if (status.value == StatusConexao.PROCURANDO) {
                scanner.stopScan(scanCallback)
                status.value = StatusConexao.ERRO
                mensagemErro.value = "ESP32 nao encontrado. Verifique se ele esta ligado e no alcance."
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun solicitarSchema(gatt: BluetoothGatt) {
        val caracteristica = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
        caracteristica?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(it, "GET_SCHEMA".toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                it.value = "GET_SCHEMA".toByteArray(Charsets.UTF_8)
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(it)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val servicos = result.scanRecord?.serviceUuids
            val encontrou = servicos?.any { it.uuid == SERVICE_UUID } == true
            if (encontrou) {
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(this)
                status.value = StatusConexao.CONECTANDO
                bluetoothGatt = result.device.connectGatt(context, false, gattCallback)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            status.value = StatusConexao.ERRO
            mensagemErro.value = "Falha ao escanear (codigo $errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Payload JSON tem ~200+ bytes; o MTU padrao (23 bytes) truncaria os dados.
                    // Pede um MTU maior ANTES de descobrir servicos.
                    val success = gatt.requestMtu(517)
                    if (!success) {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    status.value = StatusConexao.DESCONECTADO
                    mensagemErro.value = "Conexao com o ESP32 foi perdida."
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, statusCode: Int) {
            // So depois do MTU negociado e que descobrimos os servicos com seguranca
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            val caracteristica = gatt.getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID)

            if (caracteristica == null) {
                status.value = StatusConexao.ERRO
                mensagemErro.value = "Servico/caracteristica BLE nao encontrado no ESP32."
                return
            }

            gatt.setCharacteristicNotification(caracteristica, true)
            val descriptor = caracteristica.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            status.value = StatusConexao.CONECTADO
            mensagemErro.value = null
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                this@BleManager.solicitarSchema(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val texto = characteristic.value?.toString(Charsets.UTF_8) ?: return
            try {
                if (texto.startsWith("{")) {
                    val json = JSONObject(texto)

                    // Marcadores de sincronizacao: nao sao leituras de sensor, so avisos
                    if (json.has("sync_start")) {
                        val pendentes = json.optInt("pendentes", 0)
                        mensagemSync.value = "Sincronizando $pendentes leituras pendentes..."
                        return
                    }
                    if (json.has("sync_end")) {
                        val enviados = json.optInt("enviados", 0)
                        mensagemSync.value = "Sincronização concluída: $enviados leituras recebidas"
                        return
                    }
                    return
                }

                val arr = JSONArray(texto)
                val ehSchema = arr.length() > 0 && arr.optString(0) == "equipamento_id"

                if (ehSchema) {
                    schemaAtual = List(arr.length()) { i -> arr.getString(i) }
                    Log.i("SompoApp", "Schema recebido (${arr.length()} campos): $schemaAtual")
                    return
                }

                val schema = schemaAtual
                if (schema.size != arr.length()) {
                    Log.w("SompoApp", "Tamanho da leitura (${arr.length()}) difere do schema (${schema.size}), descartando.")
                    return
                }

                val mapa = schema.zip(List(arr.length()) { i -> arr.get(i) }).toMap()

                val leitura = LeituraSensor(
                    equipamentoId = mapa["equipamento_id"] as? String ?: "-",
                    timestampMs = mapa["timestamp_ms"].toLongVal(0L),
                    temperatura = mapa["temperatura"].toDoubleVal(-1.0),
                    umidade = mapa["umidade"].toDoubleVal(-1.0),
                    vibracaoX = mapa["vibracao_x"].toDoubleVal(0.0),
                    vibracaoY = mapa["vibracao_y"].toDoubleVal(0.0),
                    vibracaoZ = mapa["vibracao_z"].toDoubleVal(0.0),
                    giroX = mapa["giro_x"].toDoubleVal(0.0),
                    giroY = mapa["giro_y"].toDoubleVal(0.0),
                    giroZ = mapa["giro_z"].toDoubleVal(0.0),
                    mpuTempInternaC = mapa["mpu_temp_interna_c"].toDoubleVal(-1.0),
                    distanciaEsquerdaMm = mapa["distancia_esquerda_mm"].toDoubleVal(-1.0),
                    distanciaDireitaMm = mapa["distancia_direita_mm"].toDoubleVal(-1.0),
                    statusTurno = mapa["status_turno"] as? String ?: "-",
                    mock = mapa["mock"] as? Boolean ?: false
                )
                ultimaLeitura.value = leitura

                // Historico em memoria: mais recente primeiro, com limite pra nao pesar
                historico.add(0, leitura)
                if (historico.size > LIMITE_HISTORICO) {
                    historico.removeAt(historico.size - 1)
                }

                // Envia pro backend AWS em segundo plano, sem travar a leitura BLE
                escopo.launch { awsUploader.enviar(leitura) }
            } catch (e: Exception) {
                Log.e("SompoApp", "Falha ao processar payload BLE: $texto", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun desconectar() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        status.value = StatusConexao.DESCONECTADO
    }
}
