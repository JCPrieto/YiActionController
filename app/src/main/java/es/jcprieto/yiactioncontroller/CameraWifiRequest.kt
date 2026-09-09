package es.jcprieto.yiactioncontroller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraWifiState { DISCONNECTED, REQUESTING, CONNECTING, CONNECTED, UNAVAILABLE, LOST, ERROR }
enum class CameraWifiError(val description: String) {
    PERMISSION_DENIED("Permiso denegado. Concede el permiso necesario para conectar a la cámara."),
    WIFI_DISABLED("Wi-Fi desactivada. Actívala desde Android y vuelve a intentarlo."),
    INVALID_CREDENTIALS("Revisa el SSID y la contraseña WPA2 (8–63 caracteres ASCII)."),
    UNAVAILABLE("Solicitud no disponible: autorización cancelada, red no encontrada o conexión fallida. Revisa la Wi-Fi de la cámara y las credenciales y reintenta."),
    NO_ROUTE("La red disponible no tiene una ruta a 192.168.42.1."),
    WRONG_NETWORK("La red recibida no es una Wi-Fi válida para la cámara."),
    NETWORK_LOST("Se perdió la red Wi-Fi de la cámara. Puedes volver a conectar."),
    CAMERA_CONNECTION("La sesión TCP de la cámara falló. Comprueba la cámara y vuelve a conectar."),
    REQUEST_FAILED("No se pudo solicitar la Wi-Fi. Revisa los ajustes de Android y reintenta."),
}

data class CameraWifiStatus<N>(
    val state: CameraWifiState = CameraWifiState.DISCONNECTED,
    val network: N? = null,
    val ssid: String? = null,
    val error: CameraWifiError? = null,
)

/** Main-thread lifecycle, independent of Android. Generation IDs reject late callbacks. */
internal class CameraWifiRequest<N>(
    private val releaseRequest: (Long) -> Unit,
    private val ready: (N) -> Unit,
    private val lost: () -> Unit,
    private val diagnostic: (String) -> Unit,
) {
    private val mutableStatus = MutableStateFlow(CameraWifiStatus<N>())
    val status = mutableStatus.asStateFlow()
    private var sequence = 0L
    var active: Long? = null
        private set
    private var candidate: N? = null
    private var wifi: Boolean? = null
    private var route = false

    fun begin(ssid: String): Long? {
        if (active != null) {
            diagnostic("Petición duplicada ignorada"); return null
        }
        val id = ++sequence
        active = id
        candidate = null; wifi = null; route = false
        mutableStatus.value = CameraWifiStatus(CameraWifiState.REQUESTING, ssid = ssid)
        diagnostic("Solicitud local-only iniciada; Android puede pedir autorización")
        return id
    }

    fun available(id: Long, network: N) {
        if (id != active || candidate == network) return
        if (candidate != null) {
            fail(CameraWifiError.NETWORK_LOST); return
        }
        candidate = network
        mutableStatus.value = mutableStatus.value.copy(state = CameraWifiState.CONNECTING)
        diagnostic("Network disponible; comprobando ruta")
    }

    fun capabilities(id: Long, network: N, isWifi: Boolean, cellular: Boolean, internet: Boolean, validated: Boolean) {
        if (id != active || candidate != network) return
        diagnostic("Wi-Fi=$isWifi celular=$cellular INTERNET=$internet VALIDATED=$validated")
        wifi = isWifi && !cellular
        if (wifi == false) fail(CameraWifiError.WRONG_NETWORK) else acceptIfReady()
    }

    fun route(id: Long, network: N, usable: Boolean) {
        if (id != active || candidate != network) return
        route = usable
        diagnostic("Ruta 192.168.42.1=$usable")
        if (!usable && status.value.state == CameraWifiState.CONNECTED) fail(CameraWifiError.NETWORK_LOST)
        else acceptIfReady()
    }

    private fun acceptIfReady() {
        if (wifi != true || !route || status.value.state == CameraWifiState.CONNECTED) return
        val network = candidate ?: return
        mutableStatus.value = mutableStatus.value.copy(state = CameraWifiState.CONNECTED, network = network)
        diagnostic("Ruta confirmada; Wi-Fi local conectada")
        ready(network)
    }

    fun unavailable(id: Long) {
        if (id == active) fail(CameraWifiError.UNAVAILABLE)
    }

    fun timeout(id: Long) {
        if (id == active && status.value.state != CameraWifiState.CONNECTED)
            fail(if (candidate != null) CameraWifiError.NO_ROUTE else CameraWifiError.UNAVAILABLE)
    }

    fun lost(id: Long, network: N) {
        if (id == active && candidate == network) fail(CameraWifiError.NETWORK_LOST)
    }

    fun fail(error: CameraWifiError) {
        val hadNetwork = candidate != null
        val id = active
        active = null; candidate = null; wifi = null; route = false
        mutableStatus.value = mutableStatus.value.copy(
            state = when (error) {
                CameraWifiError.NETWORK_LOST -> CameraWifiState.LOST
                CameraWifiError.UNAVAILABLE -> CameraWifiState.UNAVAILABLE
                else -> CameraWifiState.ERROR
            },
            network = null, error = error,
        )
        diagnostic("Error=$error")
        if (hadNetwork) lost()
        if (id != null) releaseRequest(id)
    }

    fun disconnect() {
        val id = active
        active = null; candidate = null; wifi = null; route = false
        mutableStatus.value = CameraWifiStatus()
        if (id != null) releaseRequest(id)
    }
}
