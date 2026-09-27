package com.tagsmith;

import com.tagsmith.nbt.NbtIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Processes one gzip-compressed player data file (playerdata/&lt;uuid&gt;.dat). */
final class PlayerDataProcessor {

    private PlayerDataProcessor() {}

    static void process(Path file, ScanContext ctx) throws IOException {
        byte[] data = Files.readAllBytes(file);
        ctx.stats.bytesProcessed.add(data.length);
        if (data.length == 0) return;

        byte[] nbt = Compression.decompress(Compression.GZIP, data, 0, data.length);
        if (!ctx.fixer.mightNeedFixing(nbt)) return;

        NbtIO.Root root = NbtIO.read(nbt);
        List<String> report = new ArrayList<>();
        boolean changed = ctx.fixer.fixTree(root.tag(), ctx.display(file), report, ctx.stats);
        ctx.report(report);
        if (!changed) return;

        if (!ctx.dryRun) {
            ctx.replaceFile(file, Compression.compress(Compression.GZIP, NbtIO.write(root)));
        }
        ctx.stats.filesModified.increment();
    }
}
