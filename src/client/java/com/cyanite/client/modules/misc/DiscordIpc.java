package com.cyanite.client.modules.misc;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The Discord desktop app's local IPC: frames of {little-endian opcode, little-endian length, JSON}
 * over a named pipe on Windows or a Unix socket elsewhere. Just enough of it to set and clear the
 * "Playing" activity.
 */
final class DiscordIpc implements Closeable {
    private static final int HANDSHAKE = 0;
    private static final int FRAME = 1;

    private final Transport transport;

    private DiscordIpc(Transport transport) {
        this.transport = transport;
    }

    /** Connects and introduces itself as {@code clientId}. Throws if Discord isn't running or refuses. */
    static DiscordIpc connect(String clientId) throws IOException {
        Transport transport = open();
        DiscordIpc ipc = new DiscordIpc(transport);
        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("client_id", clientId);
        ipc.send(HANDSHAKE, hello);
        JsonObject reply = ipc.receive();
        if (!"DISPATCH".equals(string(reply, "cmd")) || !"READY".equals(string(reply, "evt"))) {
            ipc.close();
            throw new IOException("Discord refused the connection: " + reply);
        }
        return ipc;
    }

    /** Sets the activity, or clears it when {@code activity} is null. */
    void setActivity(JsonObject activity) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) args.add("activity", activity);
        JsonObject command = new JsonObject();
        command.addProperty("cmd", "SET_ACTIVITY");
        command.add("args", args);
        command.addProperty("nonce", UUID.randomUUID().toString());
        send(FRAME, command);
        JsonObject reply = receive();
        if ("ERROR".equals(string(reply, "evt"))) throw new IOException("Discord rejected the activity: " + reply);
    }

    private void send(int opcode, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(opcode).putInt(body.length).put(body);
        transport.write(frame.array());
    }

    private JsonObject receive() throws IOException {
        ByteBuffer header = ByteBuffer.wrap(transport.read(8)).order(ByteOrder.LITTLE_ENDIAN);
        header.getInt();
        int length = header.getInt();
        if (length < 0 || length > 1 << 20) throw new IOException("Bad frame from Discord");
        return com.google.gson.JsonParser.parseString(new String(transport.read(length), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (IOException ignored) {
        }
    }

    // ---- Transports: the app listens on the first free of ten slots.

    private interface Transport extends Closeable {
        void write(byte[] data) throws IOException;

        byte[] read(int length) throws IOException;
    }

    private static Transport open() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (int i = 0; i < 10; i++) {
            try {
                if (windows) return new PipeTransport(new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw"));
                Path socket = socketDir().resolve("discord-ipc-" + i);
                if (!Files.exists(socket)) continue;
                SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                channel.connect(UnixDomainSocketAddress.of(socket));
                return new SocketTransport(channel);
            } catch (IOException ignored) {
                // Try the next slot.
            }
        }
        throw new IOException("Discord isn't running");
    }

    private static Path socketDir() {
        for (String variable : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String value = System.getenv(variable);
            if (value != null && !value.isEmpty()) return Path.of(value);
        }
        return Path.of("/tmp");
    }

    private record PipeTransport(RandomAccessFile pipe) implements Transport {
        @Override
        public void write(byte[] data) throws IOException {
            pipe.write(data);
        }

        @Override
        public byte[] read(int length) throws IOException {
            byte[] data = new byte[length];
            pipe.readFully(data);
            return data;
        }

        @Override
        public void close() throws IOException {
            pipe.close();
        }
    }

    private record SocketTransport(SocketChannel channel) implements Transport {
        @Override
        public void write(byte[] data) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
        }

        @Override
        public byte[] read(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new IOException("Discord closed the connection");
            }
            return buffer.array();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
