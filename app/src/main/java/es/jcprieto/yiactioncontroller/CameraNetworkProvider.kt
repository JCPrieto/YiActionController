package es.jcprieto.yiactioncontroller

import android.content.Context
import android.net.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.net.SocketFactory

class CameraNetworkProvider(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutableNetwork = MutableStateFlow<Network?>(null)
    val network = mutableNetwork.asStateFlow()
    val socketFactory: SocketFactory? get() = network.value?.socketFactory
    private var closed = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            refresh()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh(excluding = network)
        }
    }

    init {
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build(),
            callback,
        )
        registered = true
        refresh()
    }

    @Synchronized
    @Suppress("DEPRECATION") // Enumerate non-default local-only networks explicitly.
    fun refresh(excluding: Network? = null): Network? {
        if (closed) return null
        val candidates = manager.allNetworks.filter { it != excluding }.mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val links = manager.getLinkProperties(network) ?: return@mapNotNull null
            val prefix = cameraRoutePrefix(links)
            CameraNetworkCandidate(
                network,
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), prefix,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            )
        }
        return selectCameraNetwork(candidates).also { mutableNetwork.value = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (registered) manager.unregisterNetworkCallback(callback)
        mutableNetwork.value = null
    }
}
