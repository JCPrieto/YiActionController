package es.jcprieto.yiactioncontroller

/** Route specificity is evidence of reachability; Internet validation is irrelevant. */
internal data class CameraNetworkCandidate<T>(
    val network: T,
    val wifi: Boolean,
    val cellular: Boolean = false,
    val cameraRoutePrefix: Int?,
    val hasInternet: Boolean = false,
)

internal fun <T> selectCameraNetwork(candidates: List<CameraNetworkCandidate<T>>): T? =
    candidates.filter { it.wifi && !it.cellular && (it.cameraRoutePrefix ?: 0) > 0 }
        .maxByOrNull { it.cameraRoutePrefix!! }?.network
