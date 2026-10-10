package ua.codex.phonesensors;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;

/** Small MQTT 3.1.1 client for this app's publish-only use. */
public final class MqttConnection implements Closeable {
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int packetId = 1;
    private long lastTraffic;

    public synchronized void connect(String host, int port, String clientId, String username, String password, String willTopic, String willPayload) throws Exception {
        socket = port == 8883 ? SSLSocketFactory.getDefault().createSocket(host, port) : new Socket(host, port);
        socket.setSoTimeout(10000);
        in = new DataInputStream(socket.getInputStream()); out = new DataOutputStream(socket.getOutputStream());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        putString(body, "MQTT"); body.write(4);
        boolean auth = username != null && username.length() > 0;
        // MQTT 3.1.1 CONNECT flags: clean session is bit 1 (0x02).
        // Bit 0 is reserved and must remain zero. The old value used 1,
        // producing 0x2D for an unauthenticated connection, which Mosquitto rejects.
        int flags = 2 | 4 | 8 | 32 | (auth ? 128 | (password != null && password.length() > 0 ? 64 : 0) : 0);
        body.write(flags); body.write(0); body.write(60);
        putString(body, clientId); putString(body, willTopic); putString(body, willPayload);
        if (auth) { putString(body, username); if (password != null && password.length() > 0) putString(body, password); }
        writePacket(0x10, body.toByteArray());
        int header = in.readUnsignedByte(); int len = readRemainingLength();
        if ((header & 0xF0) != 0x20 || len != 2 || in.readUnsignedByte() != 0 || in.readUnsignedByte() != 0) throw new java.io.IOException("MQTT broker refused connection");
        lastTraffic = SystemClockCompat.elapsed();
    }

    public synchronized boolean isConnected() { return socket != null && socket.isConnected() && !socket.isClosed(); }

    public synchronized void publish(String topic, String payload, boolean retained) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); putString(body, topic);
        int id = packetId++ & 0xFFFF; if (id == 0) id = packetId++ & 0xFFFF;
        body.write((id >> 8) & 255); body.write(id & 255);
        body.write(payload.getBytes("UTF-8"));
        writePacket(retained ? 0x33 : 0x32, body.toByteArray());
        int header = in.readUnsignedByte(); int len = readRemainingLength();
        if ((header & 0xF0) != 0x40 || len != 2) throw new java.io.IOException("Missing MQTT PUBACK");
        int ack = (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (ack != id) throw new java.io.IOException("Unexpected MQTT PUBACK");
        lastTraffic = SystemClockCompat.elapsed();
    }

    public synchronized void pingIfDue() throws Exception {
        if (!isConnected() || SystemClockCompat.elapsed() - lastTraffic < TimeUnit.SECONDS.toMillis(25)) return;
        writePacket(0xC0, new byte[0]);
        int old = socket.getSoTimeout(); socket.setSoTimeout(5000);
        int header = in.readUnsignedByte(); int len = readRemainingLength();
        if ((header & 0xF0) != 0xD0 || len != 0) throw new java.io.IOException("MQTT keepalive failed");
        socket.setSoTimeout(old); lastTraffic = SystemClockCompat.elapsed();
    }

    private void writePacket(int header, byte[] body) throws Exception {
        out.writeByte(header); int n = body.length;
        do { int digit = n % 128; n /= 128; if (n > 0) digit |= 0x80; out.writeByte(digit); } while (n > 0);
        out.write(body); out.flush(); lastTraffic = SystemClockCompat.elapsed();
    }
    private int readRemainingLength() throws Exception {
        int multiplier = 1, value = 0, digit, count = 0;
        do { digit = in.readUnsignedByte(); value += (digit & 127) * multiplier; multiplier *= 128; if (++count > 4) throw new java.io.IOException("Invalid MQTT packet length"); } while ((digit & 128) != 0);
        return value;
    }
    private static void putString(ByteArrayOutputStream out, String value) throws Exception {
        byte[] b = value.getBytes("UTF-8"); out.write((b.length >> 8) & 255); out.write(b.length & 255); out.write(b);
    }
    @Override public synchronized void close() {
        try { if (socket != null && !socket.isClosed()) { out.writeByte(0xE0); out.writeByte(0); out.flush(); } } catch (Exception ignored) { }
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null; in = null; out = null;
    }
    private static final class SystemClockCompat {
        static long elapsed() { return android.os.SystemClock.elapsedRealtime(); }
    }
}
