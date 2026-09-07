package androidx.media3.exoplayer.rtsp;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import es.jcprieto.yiactioncontroller.PreviewUdpSockets;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.DatagramPacket;
import java.util.Locale;

/**
 * Narrow bridge to package-private Media3 1.11.0 RTP extension points. No reflection or fork.
 * Recheck RtspMediaSource's constructor and RtpDataChannel when upgrading Media3.
 * The public Factory can bind RTSP TCP only, not the RTP/RTCP DatagramSockets.
 */
@UnstableApi
public final class YiUdpMediaSource {
    private YiUdpMediaSource() {
    }

    public static RtspMediaSource create(
            MediaItem item, SocketFactory rtspSockets, PreviewUdpSockets udpSockets) {
        return new RtspMediaSource(item, trackId -> {
            Channel channel = new Channel(udpSockets.openPair());
            channel.open(RtpUtils.getIncomingRtpDataSpec(channel.getLocalPort()));
            return channel;
        }, MediaLibraryInfo.VERSION_SLASHY, rtspSockets, false);
        // No fallback factory: this diagnostic transport is UDP only.
    }

    static final class Channel extends BaseDataSource implements RtpDataChannel {
        private final PreviewUdpSockets.Pair sockets;
        private final byte[] packetBuffer = new byte[65_507];
        private final DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
        private int remaining;
        private boolean opened;
        @Nullable
        private Uri uri;

        Channel(PreviewUdpSockets.Pair sockets) {
            super(true);
            this.sockets = sockets;
        }

        @Override
        public String getTransport() {
            return String.format(Locale.ROOT, "RTP/AVP;unicast;client_port=%d-%d",
                    sockets.rtp.getLocalPort(), sockets.rtcp.getLocalPort());
        }

        @Override
        public int getLocalPort() {
            return sockets.rtp.isClosed() ? C.INDEX_UNSET : sockets.rtp.getLocalPort();
        }

        @Override
        public boolean needsClosingOnLoadCompletion() {
            return true;
        }

        @Nullable
        @Override
        public RtspMessageChannel.InterleavedBinaryDataListener
        getInterleavedBinaryDataListener() {
            return null;
        }

        @Override
        public long open(DataSpec dataSpec) {
            transferInitializing(dataSpec);
            uri = dataSpec.uri;
            opened = true;
            transferStarted(dataSpec);
            return C.LENGTH_UNSET;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (remaining == 0) {
                packet.setLength(packetBuffer.length);
                // Propagate SocketTimeoutException: visible timeout, not EOF or TCP fallback.
                sockets.rtp.receive(packet);
                remaining = packet.getLength();
            }
            int count = Math.min(remaining, length);
            System.arraycopy(packetBuffer, packet.getLength() - remaining, buffer, offset, count);
            remaining -= count;
            if (opened) bytesTransferred(count);
            return count;
        }

        @Override
        public void close() {
            sockets.close();
            uri = null;
            remaining = 0;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
