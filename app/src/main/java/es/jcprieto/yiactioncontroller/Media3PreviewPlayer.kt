package es.jcprieto.yiactioncontroller

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.YiUdpMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketTimeoutException

@UnstableApi
class Media3PreviewPlayer(context: Context) : PreviewPlayback {
    private val appContext = context.applicationContext
    private val signals = PreviewPlaybackSignals()
    override val status = signals.status
    private val mutablePlayer = MutableStateFlow<Player?>(null)
    val player = mutablePlayer.asStateFlow()
    private var exoPlayer: ExoPlayer? = null
    private var udpSockets: PreviewUdpSockets? = null

    override fun start(transport: PreviewTransport) {
        release()
        signals.starting()
        val engine = ExoPlayer.Builder(appContext).build()
        exoPlayer = engine
        mutablePlayer.value = engine
        engine.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (exoPlayer !== engine) return
                when (playbackState) {
                    Player.STATE_IDLE -> if (engine.playerError == null) signals.starting()
                    Player.STATE_BUFFERING -> signals.buffering()
                    Player.STATE_READY -> signals.ready(engine.isPlaying)
                    Player.STATE_ENDED -> signals.error(PreviewError.RTSP, "El stream RTSP ha terminado")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (exoPlayer === engine && engine.playbackState == Player.STATE_READY) signals.ready(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (exoPlayer !== engine) return
                val timeout = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
                        generateSequence<Throwable>(error) { it.cause }.take(12).any {
                            it is SocketTimeoutException || it is java.util.concurrent.TimeoutException
                        }
                val type = when {
                    timeout -> PreviewError.RTSP_TIMEOUT
                    error.errorCode in 2000..2999 -> PreviewError.RTSP
                    else -> PreviewError.MEDIA3
                }
                val setupCode = generateSequence<Throwable>(error) { it.cause }.take(12)
                    .mapNotNull { Regex("^SETUP (\\d{3})$").matchEntire(it.message.orEmpty())?.groupValues?.get(1) }
                    .firstOrNull()
                signals.error(
                    type, when {
                        timeout -> "Timeout RTSP/RTP UDP: no se reciben datos a tiempo"
                        setupCode != null -> "RTSP SETUP $setupCode (RTP/UDP)"
                        else -> "Fallo de reproducción RTP/UDP: ${error.errorCodeName}"
                    }
                )
            }
        })
        val sockets = PreviewUdpSockets({ transport.bindDatagramSocket(it) }, 8_000)
        udpSockets = sockets
        val source = YiUdpMediaSource.create(
            MediaItem.fromUri("rtsp://192.168.42.1/live"), transport.socketFactory, sockets
        )
        engine.setMediaSource(source)
        engine.prepare()
        engine.playWhenReady = true
    }

    override fun release() {
        val previous = exoPlayer
        exoPlayer = null
        mutablePlayer.value = null
        // Unblock Media3's receiving threads before waiting for player release.
        udpSockets?.close()
        udpSockets = null
        previous?.clearVideoSurface()
        previous?.release()
        signals.idle()
    }
}
