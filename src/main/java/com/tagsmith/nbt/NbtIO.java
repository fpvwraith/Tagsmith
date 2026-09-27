package com.tagsmith.nbt;

import com.tagsmith.nbt.Tag.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes uncompressed, big-endian (Java edition) NBT. */
public final class NbtIO {

    private static final int MAX_DEPTH = 512;

    private NbtIO() {}

    /** A root compound together with its (usually empty) name. */
    public record Root(String name, CompoundTag tag) {}

    public static Root read(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        byte type = in.readByte();
        if (type != Tag.COMPOUND) {
            throw new IOException("Root tag is not a compound (type " + type + ")");
        }
        String name = in.readUTF();
        return new Root(name, readCompound(in, 0));
    }

    public static byte[] write(Root root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Tag.COMPOUND);
        out.writeUTF(root.name());
        writePayload(out, root.tag());
        out.flush();
        return bytes.toByteArray();
    }

    private static CompoundTag readCompound(DataInput in, int depth) throws IOException {
        LinkedHashMap<String, Tag> map = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == Tag.END) {
                return new CompoundTag(map);
            }
            String key = in.readUTF();
            map.put(key, readPayload(in, type, depth + 1));
        }
    }

    private static Tag readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        }
        switch (type) {
            case Tag.BYTE: return new ByteTag(in.readByte());
            case Tag.SHORT: return new ShortTag(in.readShort());
            case Tag.INT: return new IntTag(in.readInt());
            case Tag.LONG: return new LongTag(in.readLong());
            case Tag.FLOAT: return new FloatTag(in.readFloat());
            case Tag.DOUBLE: return new DoubleTag(in.readDouble());
            case Tag.BYTE_ARRAY: {
                byte[] a = new byte[checkLength(in.readInt())];
                in.readFully(a);
                return new ByteArrayTag(a);
            }
            case Tag.STRING: return new StringTag(in.readUTF());
            case Tag.LIST: {
                byte elementType = in.readByte();
                int length = checkLength(in.readInt());
                List<Tag> values = new ArrayList<>(Math.min(length, 4096));
                if (elementType != Tag.END) {
                    for (int i = 0; i < length; i++) {
                        values.add(readPayload(in, elementType, depth + 1));
                    }
                }
                return new ListTag(elementType, values);
            }
            case Tag.COMPOUND: return readCompound(in, depth);
            case Tag.INT_ARRAY: {
                int[] a = new int[checkLength(in.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return new IntArrayTag(a);
            }
            case Tag.LONG_ARRAY: {
                long[] a = new long[checkLength(in.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return new LongArrayTag(a);
            }
            default: throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int checkLength(int length) throws IOException {
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("Invalid NBT array/list length " + length);
        }
        return length;
    }

    private static void writePayload(DataOutput out, Tag tag) throws IOException {
        switch (tag) {
            case ByteTag t -> out.writeByte(t.value());
            case ShortTag t -> out.writeShort(t.value());
            case IntTag t -> out.writeInt(t.value());
            case LongTag t -> out.writeLong(t.value());
            case FloatTag t -> out.writeFloat(t.value());
            case DoubleTag t -> out.writeDouble(t.value());
            case ByteArrayTag t -> {
                out.writeInt(t.value().length);
                out.write(t.value());
            }
            case StringTag t -> out.writeUTF(t.value());
            case ListTag t -> {
                // An emptied list keeps its element type, as vanilla does when reading it back.
                out.writeByte(t.values().isEmpty() ? t.elementType() : t.values().get(0).type());
                out.writeInt(t.values().size());
                for (Tag v : t.values()) writePayload(out, v);
            }
            case CompoundTag t -> {
                for (Map.Entry<String, Tag> e : t.map().entrySet()) {
                    out.writeByte(e.getValue().type());
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue());
                }
                out.writeByte(Tag.END);
            }
            case IntArrayTag t -> {
                out.writeInt(t.value().length);
                for (int v : t.value()) out.writeInt(v);
            }
            case LongArrayTag t -> {
                out.writeInt(t.value().length);
                for (long v : t.value()) out.writeLong(v);
            }
        }
    }
}
