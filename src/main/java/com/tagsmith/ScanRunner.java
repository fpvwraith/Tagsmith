package com.tagsmith;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Finds every file to process and fans the work out over a thread pool. */
public final class ScanRunner {

    public record Options(Path serverRoot, List<String> paths, int threads, boolean dryRun,
                          boolean backup, Path dataFolder, ItemFixer.Settings fixerSettings) {}

    private record Job(Path file, long size, boolean region) {}

    private ScanRunner() {}

    /** @return true if every file was processed without a file-level error */
    public static boolean run(Options options, Logger logger) throws IOException, InterruptedException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path serverRoot = options.serverRoot().toAbsolutePath().normalize();

        List<Job> jobs = collectJobs(serverRoot, options.paths(), logger);
        // Biggest files first so one huge region at the end doesn't leave the other cores idle.
        jobs.sort(Comparator.comparingLong(Job::size).reversed());
        long totalBytes = jobs.stream().mapToLong(Job::size).sum();

        int threads = options.threads() > 0 ? options.threads() : Runtime.getRuntime().availableProcessors();
        logger.info(String.format(Locale.ROOT, "Scanning %d files (%.1f MB) on %d threads%s",
                jobs.size(), totalBytes / 1048576.0, threads, options.dryRun() ? " [DRY RUN - nothing will be written]" : ""));

        Path reportDir = options.dataFolder().resolve("reports");
        Files.createDirectories(reportDir);
        Path reportFile = reportDir.resolve("report-" + timestamp + (options.dryRun() ? "-dryrun" : "") + ".txt");
        Path backupRoot = options.backup() && !options.dryRun()
                ? options.dataFolder().resolve("backups").resolve(timestamp) : null;

        FixStats stats = new FixStats();
        AtomicInteger done = new AtomicInteger();
        long start = System.nanoTime();

        try (BufferedWriter report = Files.newBufferedWriter(reportFile, StandardCharsets.UTF_8)) {
            report.write("# Tagsmith report " + timestamp + (options.dryRun() ? " (DRY RUN)" : "") + "\n");
            report.write("# location | path inside NBT | item id | changes\n");

            ScanContext ctx = new ScanContext(new ItemFixer(options.fixerSettings()), stats, options.dryRun(),
                    serverRoot, backupRoot, logger, lines -> {
                synchronized (report) {
                    try {
                        for (String line : lines) {
                            report.write(line);
                            report.newLine();
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });

            ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Tagsmith-progress");
                t.setDaemon(true);
                return t;
            });
            progress.scheduleAtFixedRate(() -> logProgress(logger, stats, done.get(), jobs.size(), totalBytes, start),
                    5, 5, TimeUnit.SECONDS);

            ExecutorService pool = Executors.newFixedThreadPool(threads, new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();

                public Thread newThread(Runnable r) {
                    return new Thread(r, "Tagsmith-worker-" + n.incrementAndGet());
                }
            });

            List<Future<?>> futures = new ArrayList<>(jobs.size());
            for (Job job : jobs) {
                futures.add(pool.submit(() -> {
                    try {
                        if (job.region()) RegionFileProcessor.process(job.file(), ctx);
                        else PlayerDataProcessor.process(job.file(), ctx);
                    } catch (Exception e) {
                        stats.filesFailed.increment();
                        ctx.warn("Failed to process " + ctx.display(job.file()) + " (left unchanged)", e);
                    } finally {
                        stats.filesScanned.increment();
                        done.incrementAndGet();
                    }
                }));
            }
            pool.shutdown();
            try {
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        stats.filesFailed.increment();
                        logger.warning("Worker error: " + e.getCause());
                    }
                }
            } finally {
                progress.shutdownNow();
                pool.shutdownNow();
            }

            report.write(summary(stats, Duration.ofNanos(System.nanoTime() - start), options.dryRun()));
        }

        for (String line : summary(stats, Duration.ofNanos(System.nanoTime() - start), options.dryRun()).split("\n")) {
            logger.info(line);
        }
        logger.info("Report written to " + reportFile);
        if (backupRoot != null && Files.exists(backupRoot)) logger.info("Originals of modified files backed up to " + backupRoot);
        return stats.filesFailed.sum() == 0;
    }

    private static List<Job> collectJobs(Path serverRoot, List<String> paths, Logger logger) throws IOException {
        List<Job> jobs = new ArrayList<>();
        for (String p : paths) {
            Path dir = serverRoot.resolve(p).normalize();
            if (!Files.isDirectory(dir)) {
                logger.warning("Directory not found, skipping: " + dir);
                continue;
            }
            int before = jobs.size();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String name = f.getFileName().toString();
                    boolean region = name.endsWith(".mca");
                    if ((region || name.endsWith(".dat")) && Files.isRegularFile(f)) {
                        jobs.add(new Job(f, Files.size(f), region));
                    }
                }
            }
            logger.info("  " + p + ": " + (jobs.size() - before) + " files");
        }
        return jobs;
    }

    private static void logProgress(Logger logger, FixStats stats, int done, int total, long totalBytes, long start) {
        long bytes = stats.bytesProcessed.sum();
        double elapsed = (System.nanoTime() - start) / 1e9;
        double fraction = totalBytes == 0 ? 1 : (double) bytes / totalBytes;
        String eta = fraction > 0.01 ? formatSeconds(elapsed / fraction - elapsed) : "?";
        logger.info(String.format(Locale.ROOT, "Progress: %d/%d files (%.1f%%), %d items fixed, elapsed %s, ETA %s",
                done, total, fraction * 100, stats.itemsModified.sum(), formatSeconds(elapsed), eta));
    }

    private static String formatSeconds(double seconds) {
        long s = (long) seconds;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String summary(FixStats s, Duration took, boolean dryRun) {
        return String.format(Locale.ROOT, """
                ===== Tagsmith %s in %s =====
                Files scanned:   %d (%s %d, failed %d)
                Chunks scanned:  %d (parsed %d, modified %d, failed %d)
                Items modified:  %d
                  max_stack_size removed:      %d (counts reduced to 1: %d)
                  enchantment levels reduced:  %d
                  unbreakable removed:         %d
                  attribute_modifiers removed: %d
                  potion amplifiers reduced:   %d
                """,
                dryRun ? "dry run finished" : "finished", formatSeconds(took.toMillis() / 1000.0),
                s.filesScanned.sum(), dryRun ? "would modify" : "modified", s.filesModified.sum(), s.filesFailed.sum(),
                s.chunksScanned.sum(), s.chunksParsed.sum(), s.chunksModified.sum(), s.chunksFailed.sum(),
                s.itemsModified.sum(), s.maxStackSizeRemoved.sum(), s.countsReduced.sum(),
                s.enchantmentsReduced.sum(), s.unbreakableRemoved.sum(), s.attributeModifiersRemoved.sum(),
                s.potionAmplifiersReduced.sum());
    }
}
