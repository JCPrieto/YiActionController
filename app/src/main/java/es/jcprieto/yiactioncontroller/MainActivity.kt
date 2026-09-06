package es.jcprieto.yiactioncontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val model: CameraViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                Diagnostics(state, model::connect, model::disconnect, model::refresh)
            }
        }
    }
}

@Composable
private fun Diagnostics(
    state: CameraState,
    connect: () -> Unit,
    disconnect: () -> Unit,
    refresh: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("YI · Diagnóstico", style = MaterialTheme.typography.headlineMedium)
            Text("Conecta el teléfono manualmente al Wi-Fi de la cámara y después pulsa Conectar.")
            Text("192.168.42.1:7878", style = MaterialTheme.typography.bodySmall)
            val connection = when (state.connection) {
                ConnectionStatus.DISCONNECTED -> "Desconectada"
                ConnectionStatus.CONNECTING -> "Conectando…"
                ConnectionStatus.AUTHENTICATING -> "Obteniendo token…"
                ConnectionStatus.CONNECTED -> "Conectada"
            }
            Text(connection, style = MaterialTheme.typography.titleLarge)
            if (state.connection == ConnectionStatus.DISCONNECTED) {
                Button(onClick = connect) { Text("Conectar") }
            } else {
                OutlinedButton(onClick = disconnect) { Text("Desconectar") }
            }
            Button(
                onClick = refresh,
                enabled = state.connection == ConnectionStatus.CONNECTED && state.pending.isEmpty(),
            ) { Text(if (state.pending.isEmpty()) "Consultar batería y configuración" else "Consultando…") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticField("Token", state.token?.toString())
                    DiagnosticField("Batería", state.battery?.let { "$it %" })
                    DiagnosticField("Firmware", state.firmware)
                    DiagnosticField("Hardware", state.hardware)
                    DiagnosticField("Tarjeta SD", state.sdCard)
                    DiagnosticField("Resolución de vídeo", state.videoResolution)
                    DiagnosticField("Estado de la cámara", state.cameraStatus)
                    HorizontalDivider()
                    Text("Configuración recibida", style = MaterialTheme.typography.titleMedium)
                    if (state.configuration.isEmpty()) Text("Sin datos")
                    state.configuration.toSortedMap().forEach { (key, value) -> DiagnosticField(key, value) }
                    HorizontalDivider()
                    DiagnosticField("Último evento asíncrono", state.lastEvent)
                    DiagnosticField("Última petición", state.lastRequest)
                    DiagnosticField("Último mensaje", state.lastMessage)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticField(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value ?: "Sin datos", style = MaterialTheme.typography.bodyMedium)
    }
}
