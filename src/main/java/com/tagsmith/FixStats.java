package com.tagsmith;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe counters shared by all worker threads. */
public final class FixStats {
    public final LongAdder filesScanned = new LongAdder();
    public final LongAdder filesModified = new LongAdder();
    public final LongAdder filesFailed = new LongAdder();
    public final LongAdder chunksScanned = new LongAdder();
    public final LongAdder chunksParsed = new LongAdder();
    public final LongAdder chunksModified = new LongAdder();
    public final LongAdder chunksFailed = new LongAdder();
    public final LongAdder bytesProcessed = new LongAdder();

    public final LongAdder itemsModified = new LongAdder();
    public final LongAdder maxStackSizeRemoved = new LongAdder();
    public final LongAdder countsReduced = new LongAdder();
    public final LongAdder enchantmentsReduced = new LongAdder();
    public final LongAdder unbreakableRemoved = new LongAdder();
    public final LongAdder attributeModifiersRemoved = new LongAdder();
    public final LongAdder potionAmplifiersReduced = new LongAdder();
}
