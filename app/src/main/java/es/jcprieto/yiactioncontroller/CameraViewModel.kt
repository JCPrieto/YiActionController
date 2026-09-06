package es.jcprieto.yiactioncontroller

import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {
    private val client = CameraClient()
    val state = client.state
    fun connect() = client.connect()
    fun disconnect() = client.disconnect()
    fun refresh() = client.refresh()
    override fun onCleared() = client.close()
}
