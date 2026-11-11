package dev.civmaker.map.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Framed, bounded snapshots over ordinary Paper/Fabric play plugin messages. */
public final class MapProtocol {
    public static final int VERSION = 1;
    public static final String HELLO = "civmaker:map_hello";
    public static final String DATA = "civmaker:map_data";
    public static final int CHUNK_BYTES = 24_000;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_PARTS = (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    public static final int HEADER_BYTES = 20;
    public static final long TIMEOUT_MILLIS = 15_000;

    private MapProtocol() {}

    public static byte[] hello() {
        return new byte[] {0, 0, 0, VERSION};
    }

    public static boolean isHello(byte[] data) {
        return java.util.Arrays.equals(data, hello());
    }

    public static List<byte[]> frames(byte[] snapshot, int generation) throws IOException {
        if (snapshot.length == 0 || snapshot.length > MAX_BYTES) {
            throw new IOException("Map snapshot exceeds the protocol size limit");
        }
        int parts = (snapshot.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        List<byte[]> result = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeInt(generation);
            out.writeInt(i);
            out.writeInt(parts);
            out.writeInt(snapshot.length);
            int offset = i * CHUNK_BYTES;
            out.write(snapshot, offset, Math.min(CHUNK_BYTES, snapshot.length - offset));
            result.add(bytes.toByteArray());
        }
        return result;
    }

    /** Accepts out-of-order parts, publishes only complete snapshots, and rejects malformed frames. */
    public static final class Assembler {
        private int generation;
        private byte[][] parts;
        private int length;
        private int received;
        private long started;

        public void clear() {
            parts = null;
            received = 0;
            length = 0;
        }

        public byte[] accept(byte[] frame, long now) throws IOException {
            if (frame.length < HEADER_BYTES || frame.length > HEADER_BYTES + CHUNK_BYTES) {
                throw new IOException("Invalid map frame length");
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
            int version = in.readInt();
            int nextGeneration = in.readInt();
            int index = in.readInt();
            int count = in.readInt();
            int total = in.readInt();
            if (version != VERSION || total <= 0 || total > MAX_BYTES || count <= 0 || count > MAX_PARTS
                    || count != (total + CHUNK_BYTES - 1) / CHUNK_BYTES || index < 0 || index >= count
                    || in.available() != Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES)) {
                throw new IOException("Invalid map frame header");
            }
            if (parts == null || generation != nextGeneration || now - started > TIMEOUT_MILLIS) {
                generation = nextGeneration;
                parts = new byte[count][];
                received = 0;
                length = total;
                started = now;
            }
            if (parts.length != count || length != total) {
                clear();
                throw new IOException("Inconsistent map snapshot parts");
            }
            if (parts[index] == null) {
                parts[index] = in.readAllBytes();
                received++;
            }
            if (received != count) {
                return null;
            }
            ByteArrayOutputStream snapshot = new ByteArrayOutputStream(length);
            for (byte[] part : parts) {
                snapshot.write(part);
            }
            clear();
            return snapshot.toByteArray();
        }
    }
}
