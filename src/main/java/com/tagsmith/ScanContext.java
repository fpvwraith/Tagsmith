package com.tagsmith;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Everything the per-file processors share. */
public final class ScanContext {
    final ItemFixer fixer;
    final FixStats stats;
    final boolean dryRun;
    final Path serverRoot;
    final Path backupRoot; // null = no backups
    final Logger logger;
    private final Consumer<List<String>> reportSink;

    public ScanContext(ItemFixer fixer, FixStats stats, boolean dryRun, Path serverRoot, Path backupRoot,
                       Logger logger, Consumer<List<String>> reportSink) {
        this.fixer = fixer;
        this.stats = stats;
        this.dryRun = dryRun;
        this.serverRoot = serverRoot;
        this.backupRoot = backupRoot;
        this.logger = logger;
        this.reportSink = reportSink;
    }

    String display(Path file) {
        try {
            return serverRoot.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    void report(List<String> lines) {
        if (!lines.isEmpty()) reportSink.accept(lines);
    }

    void warn(String message, Throwable t) {
        logger.log(Level.WARNING, message + (t == null ? "" : ": " + t));
    }

    /** Copies the original to the backup folder, then atomically replaces it with {@code data}. */
    void replaceFile(Path file, byte[] data) throws IOException {
        backup(file);
        Path tmp = file.resolveSibling(file.getFileName() + ".tagsmith-tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void deleteFile(Path file) throws IOException {
        backup(file);
        Files.deleteIfExists(file);
    }

    private void backup(Path file) throws IOException {
        if (backupRoot == null || !Files.exists(file)) return;
        Path target;
        try {
            target = backupRoot.resolve(serverRoot.relativize(file).toString());
        } catch (IllegalArgumentException e) {
            target = backupRoot.resolve(file.getFileName().toString());
        }
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
