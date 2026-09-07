package androidx.media3.exoplayer.rtsp;

import es.jcprieto.yiactioncontroller.PreviewUdpSockets;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Real loopback datagrams; no RTSP server, Android Network or decoder emulation.
 */
public class YiUdpChannelTest {
    private static void send(DatagramSocket socket, int port, byte[] bytes) throws Exception {
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }

    @Test
    public void advertisesUdpAndReadsPartialAndConsecutivePacketsWithoutTruncation() throws Exception {
        try (PreviewUdpSockets owner = new PreviewUdpSockets(socket -> {
        }, 500);
             DatagramSocket sender = new DatagramSocket()) {
            PreviewUdpSockets.Pair pair = owner.openPair();
            YiUdpMediaSource.Channel channel = new YiUdpMediaSource.Channel(pair);
            int port = channel.getLocalPort();
            assertEquals("RTP/AVP;unicast;client_port=" + port + "-" + (port + 1), channel.getTransport());
            assertNull(channel.getInterleavedBinaryDataListener());
            byte[] large = new byte[9000];
            for (int i = 0; i < large.length; i++) large[i] = (byte) i;
            send(sender, port, large);
            byte[] actual = new byte[9000];
            assertEquals(0, channel.read(actual, 0, 0));
            assertEquals(10, channel.read(actual, 0, 10));
            assertEquals(8990, channel.read(actual, 10, 8990));
            assertArrayEquals(large, actual);
            send(sender, port, new byte[]{9, 8, 7});
            byte[] small = new byte[3];
            assertEquals(3, channel.read(small, 0, 3));
            assertArrayEquals(new byte[]{9, 8, 7}, small);
            channel.close();
            channel.close();
            assertTrue(pair.rtcp.isClosed());
        }
    }

    @Test
    public void noPacketsRaisesTimeoutRatherThanEof() throws Exception {
        try (PreviewUdpSockets owner = new PreviewUdpSockets(socket -> {
        }, 50)) {
            YiUdpMediaSource.Channel channel = new YiUdpMediaSource.Channel(owner.openPair());
            assertThrows(SocketTimeoutException.class, () -> channel.read(new byte[100], 0, 100));
        }
    }

    @Test
    public void releaseUnblocksReceiverWithoutWaitingForEightSecondTimeout() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (PreviewUdpSockets owner = new PreviewUdpSockets(socket -> {
        }, 8000)) {
            YiUdpMediaSource.Channel channel = new YiUdpMediaSource.Channel(owner.openPair());
            var reading = executor.submit(() -> {
                assertThrows(java.net.SocketException.class, () -> channel.read(new byte[100], 0, 100));
            });
            owner.close();
            reading.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
