package com.tagsmith.nbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal in-memory NBT model. Compounds and lists are mutable; primitive tags are immutable
 * and are replaced in their parent when changed.
 */
public sealed interface Tag {

    byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    byte type();

    record ByteTag(byte value) implements Tag {
        public byte type() { return BYTE; }
    }

    record ShortTag(short value) implements Tag {
        public byte type() { return SHORT; }
    }

    record IntTag(int value) implements Tag {
        public byte type() { return INT; }
    }

    record LongTag(long value) implements Tag {
        public byte type() { return LONG; }
    }

    record FloatTag(float value) implements Tag {
        public byte type() { return FLOAT; }
    }

    record DoubleTag(double value) implements Tag {
        public byte type() { return DOUBLE; }
    }

    record ByteArrayTag(byte[] value) implements Tag {
        public byte type() { return BYTE_ARRAY; }
    }

    record StringTag(String value) implements Tag {
        public byte type() { return STRING; }
    }

    record IntArrayTag(int[] value) implements Tag {
        public byte type() { return INT_ARRAY; }
    }

    record LongArrayTag(long[] value) implements Tag {
        public byte type() { return LONG_ARRAY; }
    }

    final class ListTag implements Tag {
        private final byte elementType;
        private final List<Tag> values;

        public ListTag(byte elementType, List<Tag> values) {
            this.elementType = elementType;
            this.values = values;
        }

        public ListTag(byte elementType) {
            this(elementType, new ArrayList<>());
        }

        public byte type() { return LIST; }

        public byte elementType() { return elementType; }

        public List<Tag> values() { return values; }
    }

    final class CompoundTag implements Tag {
        private final LinkedHashMap<String, Tag> map;

        public CompoundTag(LinkedHashMap<String, Tag> map) {
            this.map = map;
        }

        public CompoundTag() {
            this(new LinkedHashMap<>());
        }

        public byte type() { return COMPOUND; }

        public Map<String, Tag> map() { return map; }

        public Tag get(String key) { return map.get(key); }

        public void put(String key, Tag value) { map.put(key, value); }

        public Tag remove(String key) { return map.remove(key); }

        public CompoundTag getCompound(String key) {
            return map.get(key) instanceof CompoundTag c ? c : null;
        }

        public ListTag getList(String key) {
            return map.get(key) instanceof ListTag l ? l : null;
        }

        public String getString(String key) {
            return map.get(key) instanceof StringTag s ? s.value() : null;
        }
    }

    static boolean isNumeric(Tag tag) {
        return tag instanceof ByteTag || tag instanceof ShortTag || tag instanceof IntTag
                || tag instanceof LongTag || tag instanceof FloatTag || tag instanceof DoubleTag;
    }

    static double asDouble(Tag tag) {
        return switch (tag) {
            case ByteTag t -> t.value();
            case ShortTag t -> t.value();
            case IntTag t -> t.value();
            case LongTag t -> t.value();
            case FloatTag t -> t.value();
            case DoubleTag t -> t.value();
            default -> throw new IllegalArgumentException("Not a numeric tag: " + tag);
        };
    }

    /** Returns a tag of the same numeric type as {@code original} holding {@code value}. */
    static Tag numericOfSameType(Tag original, int value) {
        return switch (original) {
            case ByteTag t -> new ByteTag((byte) value);
            case ShortTag t -> new ShortTag((short) value);
            case IntTag t -> new IntTag(value);
            case LongTag t -> new LongTag(value);
            case FloatTag t -> new FloatTag(value);
            case DoubleTag t -> new DoubleTag(value);
            default -> throw new IllegalArgumentException("Not a numeric tag: " + original);
        };
    }
}
