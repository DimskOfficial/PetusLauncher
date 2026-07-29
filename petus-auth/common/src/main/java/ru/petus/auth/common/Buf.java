package ru.petus.auth.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal reader/writer for the Minecraft wire format (VarInt length prefixed
 * strings), so the plugins can talk to petus-connect without depending on any
 * server internals.
 */
public final class Buf {
    private final byte[] data;
    private int cursor;

    public Buf(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    public static byte[] write(Object... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object value : values) {
            if (value instanceof String text) {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                writeVarInt(out, bytes.length);
                out.write(bytes, 0, bytes.length);
            } else if (value instanceof Integer number) {
                writeVarInt(out, number);
            } else if (value instanceof Boolean flag) {
                out.write(flag ? 1 : 0);
            } else {
                throw new IllegalArgumentException("Unsupported payload value: " + value);
            }
        }
        return out.toByteArray();
    }

    public String readString(int maxLength) {
        int length = readVarInt();
        if (length < 0 || length > maxLength * 4 || cursor + length > data.length) {
            throw new IllegalStateException("Malformed string in payload");
        }
        String value = new String(data, cursor, length, StandardCharsets.UTF_8);
        cursor += length;
        return value;
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public int readVarInt() {
        int result = 0;
        int shift = 0;
        while (true) {
            byte current = readByte();
            result |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IllegalStateException("VarInt is too big");
            }
        }
    }

    public boolean hasRemaining() {
        return cursor < data.length;
    }

    private byte readByte() {
        if (cursor >= data.length) {
            throw new IllegalStateException("Payload ended too early");
        }
        return data[cursor++];
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }
}
