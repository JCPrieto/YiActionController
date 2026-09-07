package es.jcprieto.yiactioncontroller;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-preview socket owner. No Android dependency: binding is supplied by the selected Network.
 */
public final class PreviewUdpSockets implements Closeable {
    private final Binder binder;
    private final int timeoutMs;
    private final Set<DatagramSocket> sockets = new HashSet<>();
    private boolean closed;
    public PreviewUdpSockets(Binder binder, int timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        this.binder = binder;
        this.timeoutMs = timeoutMs;
    }

    private DatagramSocket open(int port) throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        synchronized (this) {
            if (closed) {
                socket.close();
                throw new SocketException("Preview UDP cerrado");
            }
            sockets.add(socket);
        }
        try {
            // Fail closed: never silently receive on the default/mobile network.
            binder.bind(socket);
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(timeoutMs);
            return socket;
        } catch (IOException | RuntimeException e) {
            discard(socket);
            throw e;
        }
    }

    /**
     * RTP even port, RTCP next odd port, reserved together before RTSP SETUP.
     */
    public Pair openPair() throws IOException {
        for (int attempt = 0; attempt < 10; attempt++) {
            DatagramSocket first = open(0);
            try {
                int port = first.getLocalPort();
                DatagramSocket second = open(port % 2 == 0 ? port + 1 : port - 1);
                return port % 2 == 0 ? new Pair(first, second) : new Pair(second, first);
            } catch (BindException e) {
                discard(first);
                if (attempt == 9) throw e;
            } catch (IOException | RuntimeException e) {
                discard(first);
                throw e;
            }
        }
        throw new SocketException("No se pudo reservar el par RTP/RTCP");
    }

    private synchronized void discard(DatagramSocket socket) {
        socket.close();
        sockets.remove(socket);
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (DatagramSocket socket : sockets) socket.close();
        sockets.clear();
    }

    public interface Binder {
        void bind(DatagramSocket socket) throws IOException;
    }

    public final class Pair implements Closeable {
        public final DatagramSocket rtp;
        public final DatagramSocket rtcp;

        private Pair(DatagramSocket rtp, DatagramSocket rtcp) {
            this.rtp = rtp;
            this.rtcp = rtcp;
        }

        @Override
        public void close() {
            discard(rtp);
            discard(rtcp);
        }
    }
}
