package com.tagsmith;

import com.tagsmith.nbt.NbtIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes one Anvil region file (.mca): 1024 chunk slots, each a length-prefixed compressed NBT blob
 * stored in 4 KiB sectors. Oversized chunks live in external c.X.Z.mcc files next to the region.
 * <p>
 * If any chunk changes, the whole region file is rebuilt: unchanged chunks are copied byte-for-byte,
 * changed chunks are recompressed with their original compression type.
 */
final class RegionFileProcessor {

    private static final int SECTOR = 4096;
    private static final int HEADER = 2 * SECTOR;
    private static final int EXTERNAL_FLAG = 0x80;
    private static final int MAX_INTERNAL_SECTORS = 255;
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionFileProcessor() {}

    static void process(Path file, ScanContext ctx) throws IOException {
        byte[] data = Files.readAllBytes(file);
        ctx.stats.bytesProcessed.add(data.length);
        if (data.length < HEADER) {
            if (data.length > 0) ctx.warn("Skipping truncated region file " + ctx.display(file), null);
            return;
        }

        Matcher m = REGION_NAME.matcher(file.getFileName().toString());
        int regionX = m.matches() ? Integer.parseInt(m.group(1)) : 0;
        int regionZ = m.matches() ? Integer.parseInt(m.group(2)) : 0;
        ByteBuffer buf = ByteBuffer.wrap(data);

        byte[][] newPayloads = new byte[1024][]; // compressed replacement data, null = unchanged
        int[] compressionTypes = new int[1024];
        boolean anyChanged = false;
        boolean structurallyBroken = false;
        List<String> report = new ArrayList<>();

        for (int i = 0; i < 1024; i++) {
            int entry = buf.getInt(i * 4);
            if (entry == 0) continue;
            int sectorOffset = entry >>> 8;
            int chunkX = regionX * 32 + (i & 31);
            int chunkZ = regionZ * 32 + (i >> 5);
            String where = ctx.display(file) + " chunk " + chunkX + "," + chunkZ;
            ctx.stats.chunksScanned.increment();

            long pos = (long) sectorOffset * SECTOR;
            if (sectorOffset < 2 || pos + 5 > data.length) {
                ctx.warn("Invalid chunk offset in " + where, null);
                structurallyBroken = true;
                ctx.stats.chunksFailed.increment();
                continue;
            }
            int length = buf.getInt((int) pos);
            int type = data[(int) pos + 4] & 0xFF;
            boolean external = (type & EXTERNAL_FLAG) != 0;
            int compression = type & ~EXTERNAL_FLAG;
            compressionTypes[i] = compression;
            if (length < 1 || pos + 4 + length > data.length) {
                ctx.warn("Invalid chunk length in " + where, null);
                structurallyBroken = true;
                ctx.stats.chunksFailed.increment();
                continue;
            }

            try {
                byte[] compressed;
                int cOff, cLen;
                if (external) {
                    Path mcc = externalFile(file, chunkX, chunkZ);
                    if (!Files.exists(mcc)) {
                        ctx.warn("Missing external chunk file " + ctx.display(mcc), null);
                        ctx.stats.chunksFailed.increment();
                        continue;
                    }
                    compressed = Files.readAllBytes(mcc);
                    cOff = 0;
                    cLen = compressed.length;
                } else {
                    compressed = data;
                    cOff = (int) pos + 5;
                    cLen = length - 1;
                }

                byte[] nbt = Compression.decompress(compression, compressed, cOff, cLen);
                if (!ctx.fixer.mightNeedFixing(nbt)) continue;

                ctx.stats.chunksParsed.increment();
                NbtIO.Root root = NbtIO.read(nbt);
                if (ctx.fixer.fixTree(root.tag(), where, report, ctx.stats)) {
                    newPayloads[i] = Compression.compress(compression, NbtIO.write(root));
                    ctx.stats.chunksModified.increment();
                    anyChanged = true;
                }
            } catch (IOException | RuntimeException e) {
                ctx.warn("Could not process " + where + " (left unchanged)", e);
                ctx.stats.chunksFailed.increment();
            }
        }

        ctx.report(report);
        if (!anyChanged) return;
        if (structurallyBroken) {
            ctx.warn("Not rewriting " + ctx.display(file) + " because it has corrupt chunk entries; "
                    + "the fixes listed in the report for this file were NOT applied", null);
            return;
        }
        if (ctx.dryRun) {
            ctx.stats.filesModified.increment();
            return;
        }
        rewrite(file, data, newPayloads, compressionTypes, regionX, regionZ, ctx);
        ctx.stats.filesModified.increment();
    }

    private static void rewrite(Path file, byte[] original, byte[][] newPayloads, int[] compressionTypes,
                                int regionX, int regionZ, ScanContext ctx) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(original);
        ByteArrayOutputStream body = new ByteArrayOutputStream(original.length);
        ByteBuffer header = ByteBuffer.allocate(HEADER);
        header.position(SECTOR);
        header.put(original, SECTOR, SECTOR); // timestamps unchanged

        List<Path> mccToWrite = new ArrayList<>();
        List<byte[]> mccData = new ArrayList<>();
        List<Path> mccToDelete = new ArrayList<>();
        int nextSector = 2;

        for (int i = 0; i < 1024; i++) {
            int entry = in.getInt(i * 4);
            if (entry == 0) continue;
            int chunkX = regionX * 32 + (i & 31);
            int chunkZ = regionZ * 32 + (i >> 5);

            byte[] payload; // [length:int][type:byte][data...]
            if (newPayloads[i] == null) {
                int pos = (entry >>> 8) * SECTOR;
                int length = in.getInt(pos);
                payload = new byte[4 + length];
                System.arraycopy(original, pos, payload, 0, payload.length);
            } else {
                byte[] compressed = newPayloads[i];
                Path mcc = externalFile(file, chunkX, chunkZ);
                if (sectorsFor(compressed.length + 5) > MAX_INTERNAL_SECTORS) {
                    mccToWrite.add(mcc);
                    mccData.add(compressed);
                    payload = ByteBuffer.allocate(5).putInt(1)
                            .put((byte) (compressionTypes[i] | EXTERNAL_FLAG)).array();
                } else {
                    if (Files.exists(mcc)) mccToDelete.add(mcc);
                    payload = ByteBuffer.allocate(compressed.length + 5).putInt(compressed.length + 1)
                            .put((byte) compressionTypes[i]).put(compressed).array();
                }
            }

            int sectors = sectorsFor(payload.length);
            header.putInt(i * 4, (nextSector << 8) | sectors);
            body.write(payload);
            body.write(new byte[sectors * SECTOR - payload.length]); // pad to sector boundary
            nextSector += sectors;
        }

        byte[] out = new byte[HEADER + body.size()];
        System.arraycopy(header.array(), 0, out, 0, HEADER);
        System.arraycopy(body.toByteArray(), 0, out, HEADER, body.size());

        for (int i = 0; i < mccToWrite.size(); i++) ctx.replaceFile(mccToWrite.get(i), mccData.get(i));
        ctx.replaceFile(file, out);
        for (Path mcc : mccToDelete) ctx.deleteFile(mcc);
    }

    private static int sectorsFor(int bytes) {
        return (bytes + SECTOR - 1) / SECTOR;
    }

    private static Path externalFile(Path region, int chunkX, int chunkZ) {
        return region.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }
}
