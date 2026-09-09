package es.jcprieto.yiactioncontroller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** Owned by the ViewModel. All operations/callbacks run on the main thread. */
@RequiresApi(29)
internal class CameraWifiConnectionManager(
    context: Context,
    ready: (Network) -> Unit,
    lost: () -> Unit,
    private val diagnostic: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var callbackId: Long? = null
    private var deadline: Runnable? = null
    private val request = CameraWifiRequest(::unregister, ready, lost, diagnostic)
    val status = request.status

    fun connect(ssid: String, password: String) {
        if (request.active != null) {
            diagnostic("Petición duplicada ignorada"); return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            fail(CameraWifiError.PERMISSION_DENIED); return
        }
        try {
            if (!wifi.isWifiEnabled) {
                fail(CameraWifiError.WIFI_DISABLED); return
            }
            if (ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size > 32 ||
                password.length !in 8..63 || password.any { it.code !in 32..126 }
            ) {
                fail(CameraWifiError.INVALID_CREDENTIALS); return
            }
            val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier).build()
            val id = request.begin(ssid) ?: return
            val observer = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = request.available(id, network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    request.capabilities(
                        id, network,
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    )
                }

                override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) {
                    request.route(id, network, cameraRoutePrefix(links) != null)
                }

                override fun onLost(network: Network) = request.lost(id, network)
                override fun onUnavailable() = request.unavailable(id)
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    if (callbackId == id) diagnostic("Acceso a Network bloqueado=$blocked")
                }
            }
            callback = observer; callbackId = id
            // Also covers onAvailable followed by missing route/capabilities.
            deadline = Runnable { request.timeout(id) }.also { handler.postDelayed(it, 45_000) }
            connectivity.requestNetwork(networkRequest, observer, handler, 45_000)
        } catch (_: SecurityException) {
            fail(CameraWifiError.PERMISSION_DENIED)
        } catch (_: IllegalArgumentException) {
            fail(CameraWifiError.INVALID_CREDENTIALS)
        } catch (_: RuntimeException) {
            // Exceptions can embed the specifier: never propagate/log their text.
            fail(CameraWifiError.REQUEST_FAILED)
        }
    }

    fun fail(error: CameraWifiError) = request.fail(error)
    private fun unregister(id: Long) {
        if (callbackId != id) return
        val observer = callback
        callback = null; callbackId = null
        deadline?.let(handler::removeCallbacks); deadline = null
        if (observer != null) {
            try {
                connectivity.unregisterNetworkCallback(observer)
            } catch (_: RuntimeException) { /* already removed */
            }
            diagnostic("Callback liberada")
        }
    }

    override fun close() = request.disconnect()
}
