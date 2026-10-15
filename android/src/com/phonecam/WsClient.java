package com.phonecam;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import android.util.Base64;

/** Minimal WebSocket client (text frames out, ping/close handling in). Sends the latest message every 16 ms. */
public class WsClient {
    public interface Listener { void onStatus(String s); }

    private final String host; private final int port; private final String path;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile String latest;
    private final java.util.concurrent.ConcurrentLinkedQueue<String> outbox = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    private Thread thread;
    private final SecureRandom rnd = new SecureRandom();

    public WsClient(String host, int port, String path, Listener l) {
        this.host = host; this.port = port; this.path = path; this.listener = l;
    }

    public void setLatest(String m) { latest = m; }
    /** One-off message (e.g. blinker command), sent in order before the next pose. */
    public void send(String m) { outbox.add(m); }

    public void start() {
        thread = new Thread(new Runnable() { public void run() { loop(); } });
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    private void loop() {
        while (running) {
            Socket s = null;
            try {
                listener.onStatus("Connecting to " + host + ":" + port + " ...");
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), 4000);
                s.setTcpNoDelay(true);
                final InputStream in = s.getInputStream();
                final OutputStream out = s.getOutputStream();
                byte[] k = new byte[16]; rnd.nextBytes(k);
                String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port +
                        "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " +
                        Base64.encodeToString(k, Base64.NO_WRAP) +
                        "\r\nSec-WebSocket-Version: 13\r\n\r\n";
                out.write(req.getBytes("US-ASCII")); out.flush();
                StringBuilder sb = new StringBuilder();
                int c, state = 0;
                while (state < 4 && (c = in.read()) != -1) {
                    sb.append((char) c);
                    if ((c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3))) state++;
                    else state = (c == '\r') ? 1 : 0;
                }
                if (!sb.toString().contains(" 101")) throw new IOException("handshake refused");
                listener.onStatus("Connected to " + host + ":" + port);

                final Socket fs = s;
                final OutputStream fout = out;
                Thread reader = new Thread(new Runnable() { public void run() {
                    try {
                        while (running) {
                            int b0 = in.read(); if (b0 < 0) break;
                            int b1 = in.read(); if (b1 < 0) break;
                            int op = b0 & 0x0f; long len = b1 & 0x7f;
                            if (len == 126) len = (in.read() << 8) | in.read();
                            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                            byte[] p = new byte[(int) len]; int off = 0;
                            while (off < len) { int r = in.read(p, off, (int) len - off); if (r < 0) throw new IOException(); off += r; }
                            if (op == 8) break;
                            if (op == 9) synchronized (fout) { writeFrame(fout, 0xA, p); }
                        }
                    } catch (Exception e) { }
                    try { fs.close(); } catch (Exception e) { }
                } });
                reader.setDaemon(true); reader.start();

                while (running && !s.isClosed()) {
                    String q;
                    while ((q = outbox.poll()) != null) synchronized (out) { writeFrame(out, 0x1, q.getBytes("UTF-8")); }
                    String m = latest;
                    if (m != null) synchronized (out) { writeFrame(out, 0x1, m.getBytes("UTF-8")); }
                    Thread.sleep(16);
                }
                try { synchronized (out) { writeFrame(out, 0x8, new byte[0]); } } catch (Exception e) { }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                listener.onStatus("Connection lost (" + e.getMessage() + "). Retrying...");
            } finally {
                try { if (s != null) s.close(); } catch (Exception e) { }
            }
            if (running) { try { Thread.sleep(2000); } catch (InterruptedException e) { break; } }
        }
    }

    private void writeFrame(OutputStream out, int op, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | op);
        int n = payload.length;
        if (n < 126) f.write(0x80 | n);
        else { f.write(0x80 | 126); f.write(n >> 8); f.write(n & 0xff); }
        byte[] mask = new byte[4]; rnd.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray()); out.flush();
    }
}
