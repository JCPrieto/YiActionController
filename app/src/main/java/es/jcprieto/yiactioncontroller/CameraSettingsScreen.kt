package es.jcprieto.yiactioncontroller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CameraSettingsScreen(
    state: CameraSettingsState,
    camera: CameraState,
    blocked: Boolean,
    transferBusy: Boolean,
    mediaBusy: Boolean,
    refresh: () -> Unit,
    apply: (String, String) -> Unit,
) {
    val readable = camera.connection == ConnectionStatus.CONNECTED && !blocked && !transferBusy && !mediaBusy
    val editable = readable && !state.busy && camera.canSendCommand && camera.recording == RecordingState.IDLE
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = state.settings.firstOrNull { it.key == selectedKey && it.access == CameraSettingAccess.EDITABLE }
    if (selected != null && editable) AlertDialog(
        onDismissRequest = { selectedKey = null },
        title = { Text(SETTING_LABELS[selected.key] ?: "Ajuste") },
        text = {
            LazyColumn {
                items(selected.allowedValues) { value ->
                    TextButton(onClick = { selectedKey = null; apply(selected.key, value) }) {
                        Text(settingValueLabel(value) + if (value == selected.currentValue) " ✓" else "")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { selectedKey = null }) { Text("Cerrar") } },
    )
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Ajustes de la cámara", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(
                onClick = refresh,
                enabled = readable && !state.busy && camera.canSendCommand
            ) { Text("Actualizar") }
            when {
                transferBusy -> Text(CameraSettingsError.DOWNLOAD_ACTIVE.description)
                blocked -> Text(CameraSettingsError.BLOCKED.description)
                camera.connection != ConnectionStatus.CONNECTED -> Text(CameraSettingsError.DISCONNECTED.description)
                mediaBusy -> Text(CameraSettingsError.BUSY.description)
                camera.recording != RecordingState.IDLE -> Text(CameraSettingsError.NOT_IDLE.description)
            }
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.mutation is CameraSettingsMutationState.Applying) "Aplicando…" else "Cargando configuración…")
            }
            state.error?.let { Text(it.description, color = MaterialTheme.colorScheme.error) }
            if (state.malformedEntries > 0) Text("Algunas entradas de configuración no se pudieron interpretar.")
        }
        for (category in CameraSettingCategory.entries) {
            val entries =
                state.settings.filter { it.category == category && it.key in SETTING_LABELS && it.access != CameraSettingAccess.SENSITIVE }
            if (entries.isEmpty()) continue
            item { Text(category.label, style = MaterialTheme.typography.titleLarge) }
            items(entries, key = { it.key }) { setting ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(SETTING_LABELS.getValue(setting.key), style = MaterialTheme.typography.titleMedium)
                        Text(setting.currentValue?.let(::settingValueLabel) ?: "No disponible")
                        if (setting.access == CameraSettingAccess.EDITABLE) {
                            if (setting.allowedValues.toSet() == setOf(
                                    "on",
                                    "off"
                                ) && setting.currentValue in setOf("on", "off")
                            )
                                Switch(
                                    checked = setting.currentValue == "on", enabled = editable,
                                    onCheckedChange = { apply(setting.key, if (it) "on" else "off") }
                                )
                            else OutlinedButton(
                                onClick = { selectedKey = setting.key },
                                enabled = editable
                            ) { Text("Cambiar") }
                        } else Text("Solo lectura", style = MaterialTheme.typography.bodySmall)
                        when (val mutation = state.mutation) {
                            is CameraSettingsMutationState.Applying -> if (mutation.key == setting.key) Text("Aplicando…")
                            is CameraSettingsMutationState.Verified -> if (mutation.key == setting.key) Text("Cambio confirmado por la cámara")
                            is CameraSettingsMutationState.Failed -> if (mutation.key == setting.key)
                                Text(mutation.error.description, color = MaterialTheme.colorScheme.error)

                            CameraSettingsMutationState.Idle -> Unit
                        }
                    }
                }
            }
        }
    }
}
