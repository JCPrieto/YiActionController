package es.jcprieto.yiactioncontroller

object CameraCommand {
    const val LOGIN = 257
    const val GET_CONFIG = 3
    const val SET_CONFIG = 2
    const val GET_BATTERY = 13
    const val TAKE_PHOTO = 769
    const val START_RECORDING = 513
    const val STOP_RECORDING = 514
    const val EVENT = 7
    const val START_PREVIEW = 259
    const val STOP_PREVIEW = 260
    const val GET_STORAGE = 5
    const val GET_FILE = 1285
    const val LIST_DIRECTORY = 1282
    const val CHANGE_DIRECTORY = 1283
}

data class CameraControlResult(val accepted: Boolean, val error: String? = null, val rval: Int? = null)

/** Observed rejection, not a claim that -21 always means "already started". */
object CameraErrorCode {
    const val PREVIEW_RESTART_CANDIDATE = -21
}

enum class CameraAction(val commandId: Int) {
    TAKE_PHOTO(CameraCommand.TAKE_PHOTO),
    START_RECORDING(CameraCommand.START_RECORDING),
    STOP_RECORDING(CameraCommand.STOP_RECORDING),
}

enum class RecordingState { UNKNOWN, IDLE, STARTING, RECORDING, STOPPING }

/** Last reported photo milestone, not inferred from command acceptance. */
enum class PhotoEvent { START_PHOTO_CAPTURE, PRECISE_CAPTURE_DATA_READY, PHOTO_TAKEN }
