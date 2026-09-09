package es.jcprieto.yiactioncontroller

import java.net.DatagramSocket
import javax.net.SocketFactory

/** One immutable binding shared by control TCP, RTSP and both UDP sockets. */
internal class CameraNetworkBinding<N>(
    val network: N,
    val socketFactory: SocketFactory,
    bindDatagramSocket: (DatagramSocket) -> Unit,
) {
    val previewTransport = PreviewTransport(socketFactory, bindDatagramSocket)
}
