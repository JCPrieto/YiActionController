package es.jcprieto.yiactioncontroller

object CameraCommand {
    const val LOGIN = 257
    const val GET_CONFIG = 3
    const val GET_BATTERY = 13
    const val TAKE_PHOTO = 769
    const val START_RECORDING = 513
    const val STOP_RECORDING = 514
    const val EVENT = 7
}

enum class CameraAction(val commandId: Int) {
    TAKE_PHOTO(CameraCommand.TAKE_PHOTO),
    START_RECORDING(CameraCommand.START_RECORDING),
    STOP_RECORDING(CameraCommand.STOP_RECORDING),
}

enum class RecordingState { UNKNOWN, IDLE, STARTING, RECORDING, STOPPING }

/** Last reported photo milestone, not inferred from command acceptance. */
enum class PhotoEvent { START_PHOTO_CAPTURE, PRECISE_CAPTURE_DATA_READY, PHOTO_TAKEN }
