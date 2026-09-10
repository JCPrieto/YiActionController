package es.jcprieto.yiactioncontroller

import javax.net.SocketFactory

/** Service-owned policy. A TCP failure never tears down the Wi-Fi request. Main thread only. */
internal class CameraSessionRecovery<N>(
    private val createBinding: (N) -> CameraNetworkBinding<N>,
    private val tcpState: () -> CameraState,
    private val connectTcp: (SocketFactory) -> Unit,
    private val disconnectTcp: () -> Unit,
    private val diagnostic: (String) -> Unit,
) {
    var binding: CameraNetworkBinding<N>? = null
        private set
    private var previousWifi = CameraWifiState.DISCONNECTED
    private var recovering = false
    private var attemptObserved = false
    private var enabled = false

    fun activate() {
        enabled = true
    }

    fun network(status: CameraWifiStatus<N>) {
        if (!enabled) return
        val wasBlocked = previousWifi == CameraWifiState.BLOCKED
        previousWifi = status.state
        val network = status.network
        if (status.state == CameraWifiState.CONNECTED && network != null) {
            if (binding == null) {
                binding = createBinding(network)
                connectTcp(binding!!.socketFactory)
            } else if (binding!!.network == network && wasBlocked && tcpState().connection == ConnectionStatus.DISCONNECTED) {
                recovering = true
                attemptObserved = false
                diagnostic("Reconectando tras UNBLOCK")
                connectTcp(binding!!.socketFactory)
            }
        }
    }

    fun tcp(state: CameraState) {
        if (!enabled) return
        if (state.connection != ConnectionStatus.DISCONNECTED) attemptObserved = true
        if (recovering && state.connection == ConnectionStatus.CONNECTED) {
            recovering = false
            diagnostic("Reconectado con nuevo token")
        } else if (recovering && state.connection == ConnectionStatus.DISCONNECTED && (attemptObserved || state.error != null)) {
            recovering = false
            diagnostic("Recuperación tras UNBLOCK fallida")
        }
        if (state.connection == ConnectionStatus.DISCONNECTED && binding != null && previousWifi == CameraWifiState.BLOCKED)
            diagnostic("Suspendido durante Network BLOCKED")
    }

    fun retry() {
        if (enabled && previousWifi == CameraWifiState.CONNECTED && tcpState().connection == ConnectionStatus.DISCONNECTED)
            binding?.let { connectTcp(it.socketFactory) }
    }

    fun clear() {
        val wasEnabled = enabled
        enabled = false
        binding = null
        recovering = false
        previousWifi = CameraWifiState.DISCONNECTED
        if (wasEnabled) disconnectTcp()
    }
}
