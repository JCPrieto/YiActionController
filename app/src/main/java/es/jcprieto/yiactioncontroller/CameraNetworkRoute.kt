package es.jcprieto.yiactioncontroller

import android.net.LinkProperties
import android.net.RouteInfo
import android.os.Build
import java.net.InetAddress

private val cameraAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 42, 1))

internal fun cameraRoutePrefix(links: LinkProperties): Int? = links.routes.filter {
    (Build.VERSION.SDK_INT < 33 || it.type == RouteInfo.RTN_UNICAST) &&
            it.destination.contains(cameraAddress) && it.destination.prefixLength > 0
}.maxOfOrNull { it.destination.prefixLength }
