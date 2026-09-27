package com.tagsmith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/** The chunk compression schemes used by region files (see server.properties region-file-compression). */
final class Compression {
    static final int GZIP = 1, ZLIB = 2, NONE = 3, LZ4 = 4;

    private Compression() {}

    static byte[] decompress(int type, byte[] data, int offset, int length) throws IOException {
        InputStream raw = new ByteArrayInputStream(data, offset, length);
        try (InputStream in = switch (type) {
            case GZIP -> new GZIPInputStream(raw);
            case ZLIB -> new InflaterInputStream(raw);
            case NONE -> raw;
            case LZ4 -> Lz4.in(raw);
            default -> throw new IOException("Unsupported chunk compression type " + type);
        }) {
            return in.readAllBytes();
        }
    }

    static byte[] compress(int type, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(4096, data.length / 4));
        try (OutputStream out = switch (type) {
            case GZIP -> new GZIPOutputStream(bytes);
            case ZLIB -> new DeflaterOutputStream(bytes);
            case NONE -> bytes;
            case LZ4 -> Lz4.out(bytes);
            default -> throw new IOException("Unsupported chunk compression type " + type);
        }) {
            out.write(data);
        }
        return bytes.toByteArray();
    }

    /** Isolated so a missing LZ4 library only breaks LZ4 chunks, not the whole plugin. */
    private static final class Lz4 {
        static InputStream in(InputStream in) throws IOException {
            try {
                return new net.jpountz.lz4.LZ4BlockInputStream(in);
            } catch (LinkageError e) {
                throw new IOException("LZ4 library not available", e);
            }
        }

        static OutputStream out(OutputStream out) throws IOException {
            try {
                return new net.jpountz.lz4.LZ4BlockOutputStream(out);
            } catch (LinkageError e) {
                throw new IOException("LZ4 library not available", e);
            }
        }
    }
}
