package es.jcprieto.yiactioncontroller

/** Avoid rebuilding notification actions for every byte-progress emission. Main-thread owned. */
internal class CameraNotificationUpdates {
    private var lastKey: String? = null
    private var lastPercent = -1
    private var lastTime = 0L

    fun shouldPublish(key: String, percent: Int, now: Long): Boolean {
        if (key == lastKey && (percent == lastPercent || now - lastTime < 1_000)) return false
        lastKey = key
        lastPercent = percent
        lastTime = now
        return true
    }

    fun reset() {
        lastKey = null
        lastPercent = -1
        lastTime = 0
    }
}
