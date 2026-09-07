package es.jcprieto.yiactioncontroller;

import org.junit.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class PreviewUdpSocketsTest {
    @Test
    public void bindsBothSocketsBeforeLocalBindAndReservesEvenOddPorts() throws Exception {
        List<DatagramSocket> bound = new ArrayList<>();
        try (PreviewUdpSockets owner = new PreviewUdpSockets(socket -> {
            assertFalse(socket.isBound());
            assertFalse(socket.isConnected());
            bound.add(socket);
        }, 100)) {
            PreviewUdpSockets.Pair pair = owner.openPair();
            assertTrue(bound.contains(pair.rtp));
            assertTrue(bound.contains(pair.rtcp));
            assertEquals(0, pair.rtp.getLocalPort() % 2);
            assertEquals(pair.rtp.getLocalPort() + 1, pair.rtcp.getLocalPort());
            assertEquals(100, pair.rtp.getSoTimeout());
            pair.close();
            pair.close();
            assertTrue(pair.rtp.isClosed());
            assertTrue(pair.rtcp.isClosed());
        }
    }

    @Test
    public void bindingFailureClosesBothAndDoesNotFallBack() throws Exception {
        List<DatagramSocket> bound = new ArrayList<>();
        try (PreviewUdpSockets owner = new PreviewUdpSockets(socket -> {
            bound.add(socket);
            if (bound.size() == 2) throw new IOException("network lost");
        }, 100)) {
            assertThrows(IOException.class, owner::openPair);
            assertEquals(2, bound.size());
            for (DatagramSocket socket : bound) assertTrue(socket.isClosed());
        }
    }

    @Test
    public void releaseClosesAllTracksAndPreventsLateAllocations() throws Exception {
        PreviewUdpSockets owner = new PreviewUdpSockets(socket -> {
        }, 100);
        PreviewUdpSockets.Pair first = owner.openPair();
        PreviewUdpSockets.Pair second = owner.openPair();
        owner.close();
        owner.close();
        assertTrue(first.rtp.isClosed());
        assertTrue(first.rtcp.isClosed());
        assertTrue(second.rtp.isClosed());
        assertTrue(second.rtcp.isClosed());
        assertThrows(SocketException.class, owner::openPair);
    }

    @Test
    public void releaseDuringBindingDoesNotLeakOrReturnUnboundPair() {
        List<DatagramSocket> seen = new ArrayList<>();
        PreviewUdpSockets[] owner = new PreviewUdpSockets[1];
        owner[0] = new PreviewUdpSockets(socket -> {
            seen.add(socket);
            owner[0].close();
        }, 100);
        assertThrows(SocketException.class, owner[0]::openPair);
        assertTrue(seen.get(0).isClosed());
    }
}
