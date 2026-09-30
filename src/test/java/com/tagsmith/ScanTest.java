package com.tagsmith;

import com.tagsmith.nbt.NbtIO;
import com.tagsmith.nbt.Tag;
import com.tagsmith.nbt.Tag.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ScanTest {

    static final ItemFixer.Settings SETTINGS = new ItemFixer.Settings(
            Set.of("minecraft:totem_of_undying", "minecraft:bundle"), 18, 10, true, true, true, true,
            new ItemFixer.PotionRules(true, 14, 3, java.util.Map.of("minecraft:resistance", 3)));

    // ---------- helpers ----------

    static CompoundTag compound(Object... kv) {
        CompoundTag c = new CompoundTag();
        for (int i = 0; i < kv.length; i += 2) c.put((String) kv[i], (Tag) kv[i + 1]);
        return c;
    }

    static ListTag list(byte type, Tag... values) {
        ListTag l = new ListTag(type);
        l.values().addAll(List.of(values));
        return l;
    }

    static StringTag s(String v) { return new StringTag(v); }

    static IntTag i(int v) { return new IntTag(v); }

    static CompoundTag item(String id, int count, CompoundTag components) {
        CompoundTag c = compound("id", s(id), "count", i(count));
        if (components != null) c.put("components", components);
        return c;
    }

    static CompoundTag badSword() {
        return item("minecraft:netherite_sword", 1, compound(
                "minecraft:enchantments", compound("minecraft:sharpness", i(255), "minecraft:looting", i(3),
                        "minecraft:unbreaking", i(18)),
                "minecraft:unbreakable", compound(),
                "minecraft:attribute_modifiers", list(Tag.COMPOUND, compound("type", s("minecraft:attack_damage"))),
                "minecraft:custom_name", s("keep me")));
    }

    static byte[] regionFile(CompoundTag[] chunks, int compression) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(8192);
        int sector = 2;
        for (int idx = 0; idx < chunks.length; idx++) {
            if (chunks[idx] == null) continue;
            byte[] c = Compression.compress(compression, NbtIO.write(new NbtIO.Root("", chunks[idx])));
            byte[] payload = ByteBuffer.allocate(c.length + 5).putInt(c.length + 1).put((byte) compression).put(c).array();
            int sectors = (payload.length + 4095) / 4096;
            header.putInt(idx * 4, (sector << 8) | sectors);
            header.putInt(4096 + idx * 4, 12345 + idx);
            body.write(payload);
            body.write(new byte[sectors * 4096 - payload.length]);
            sector += sectors;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header.array());
        out.write(body.toByteArray());
        return out.toByteArray();
    }

    static CompoundTag readChunk(byte[] region, int idx) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(region);
        int entry = b.getInt(idx * 4);
        if (entry == 0) return null;
        int pos = (entry >>> 8) * 4096;
        int len = b.getInt(pos);
        int type = region[pos + 4];
        return NbtIO.read(Compression.decompress(type, region, pos + 5, len - 1)).tag();
    }

    ScanRunner.Options options(Path root, boolean dryRun) {
        return new ScanRunner.Options(root, List.of("world/region", "world/entities", "world/playerdata"),
                4, dryRun, true, root.resolve("plugins/Tagsmith"), SETTINGS);
    }

    // ---------- tests ----------

    @Test
    void fixesNestedItemsInRegionAndKeepsOtherChunksIntact(@TempDir Path root) throws Exception {
        Path regionDir = Files.createDirectories(root.resolve("world/region"));

        // Shulker box item containing a bundle containing a bad sword, inside a chest.
        CompoundTag bundle = item("minecraft:bundle", 1, compound(
                "minecraft:bundle_contents", list(Tag.COMPOUND, badSword())));
        CompoundTag shulker = item("minecraft:shulker_box", 1, compound(
                "minecraft:container", list(Tag.COMPOUND, compound("slot", i(0), "item", bundle))));
        CompoundTag chest = compound("id", s("minecraft:chest"), "x", i(5), "y", i(64), "z", i(-3),
                "Items", list(Tag.COMPOUND, withSlot(shulker, 0),
                        withSlot(item("minecraft:totem_of_undying", 64, compound("minecraft:max_stack_size", i(64))), 1)));

        CompoundTag[] chunks = new CompoundTag[1024];
        chunks[0] = compound("DataVersion", i(4671), "block_entities", list(Tag.COMPOUND, chest));
        // An untouched chunk with bulky random data, to check byte-for-byte preservation.
        long[] noise = new Random(1).longs(3000).toArray();
        chunks[37] = compound("DataVersion", i(4671), "sections", list(Tag.COMPOUND, compound("data", new LongArrayTag(noise))));
        // Pre-1.20.5 chunk with a legacy item.
        CompoundTag legacyItem = compound("id", s("minecraft:diamond_pickaxe"), "Count", new ByteTag((byte) 1),
                "tag", compound("Unbreakable", new ByteTag((byte) 1),
                        "Enchantments", list(Tag.COMPOUND, compound("id", s("minecraft:efficiency"), "lvl", new ShortTag((short) 32767)))));
        chunks[1023] = compound("DataVersion", i(3465), "block_entities", list(Tag.COMPOUND,
                compound("id", s("minecraft:barrel"), "x", i(0), "y", i(0), "z", i(0), "Items", list(Tag.COMPOUND, legacyItem))));

        byte[] original = regionFile(chunks, Compression.ZLIB);
        Path file = regionDir.resolve("r.0.0.mca");
        Files.write(file, original);

        // Dry run: nothing changes.
        assertTrue(ScanRunner.run(options(root, true), Logger.getAnonymousLogger()));
        assertArrayEquals(original, Files.readAllBytes(file));

        assertTrue(ScanRunner.run(options(root, false), Logger.getAnonymousLogger()));
        byte[] fixed = Files.readAllBytes(file);

        CompoundTag c0 = readChunk(fixed, 0);
        CompoundTag fixedChest = (CompoundTag) c0.getList("block_entities").values().get(0);
        CompoundTag fixedShulker = (CompoundTag) fixedChest.getList("Items").values().get(0);
        CompoundTag fixedBundle = fixedShulker.getCompound("components").getList("minecraft:container")
                .values().stream().map(t -> ((CompoundTag) t).getCompound("item")).findFirst().orElseThrow();
        CompoundTag sword = (CompoundTag) fixedBundle.getCompound("components").getList("minecraft:bundle_contents").values().get(0);
        CompoundTag comps = sword.getCompound("components");
        CompoundTag ench = comps.getCompound("minecraft:enchantments");
        assertEquals(10, ((IntTag) ench.get("minecraft:sharpness")).value());
        assertEquals(3, ((IntTag) ench.get("minecraft:looting")).value());
        assertEquals(18, ((IntTag) ench.get("minecraft:unbreaking")).value());
        assertNull(comps.get("minecraft:unbreakable"));
        assertNull(comps.get("minecraft:attribute_modifiers"));
        assertEquals("keep me", comps.getString("minecraft:custom_name"));

        CompoundTag totem = (CompoundTag) fixedChest.getList("Items").values().get(1);
        assertEquals(1, ((IntTag) totem.get("count")).value());
        assertNull(totem.getCompound("components").get("minecraft:max_stack_size"));
        assertEquals(1, ((IntTag) totem.get("Slot")).value()); // unrelated keys untouched

        // Untouched chunk identical; timestamps preserved.
        assertArrayEquals(noise, ((LongArrayTag) ((CompoundTag) readChunk(fixed, 37).getList("sections").values().get(0)).get("data")).value());
        assertEquals(12345 + 37, ByteBuffer.wrap(fixed).getInt(4096 + 37 * 4));

        CompoundTag legacy = (CompoundTag) ((CompoundTag) readChunk(fixed, 1023).getList("block_entities").values().get(0))
                .getList("Items").values().get(0);
        assertNull(legacy.getCompound("tag").get("Unbreakable"));
        assertEquals(10, ((ShortTag) ((CompoundTag) legacy.getCompound("tag").getList("Enchantments").values().get(0)).get("lvl")).value());

        // Backup of the original exists; running again changes nothing.
        try (var s = Files.walk(root.resolve("plugins/Tagsmith/backups"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().equals("r.0.0.mca")));
        }
        assertTrue(ScanRunner.run(options(root, false), Logger.getAnonymousLogger()));
        assertArrayEquals(fixed, Files.readAllBytes(file));
    }

    @Test
    void fixesPlayerDataAndEntityPassengers(@TempDir Path root) throws Exception {
        Path players = Files.createDirectories(root.resolve("world/playerdata"));
        CompoundTag player = compound(
                "Inventory", list(Tag.COMPOUND, withSlot(badSword(), 0)),
                "EnderItems", list(Tag.COMPOUND, withSlot(item("minecraft:enchanted_book", 1, compound(
                        "minecraft:stored_enchantments", compound("minecraft:mending", i(100)))), 0)),
                "equipment", compound("head", item("minecraft:diamond_helmet", 1, compound("minecraft:unbreakable", compound()))));
        Path dat = players.resolve("00000000-0000-0000-0000-000000000000.dat");
        Files.write(dat, Compression.compress(Compression.GZIP, NbtIO.write(new NbtIO.Root("", player))));

        Path entities = Files.createDirectories(root.resolve("world/entities"));
        CompoundTag zombie = compound("id", s("minecraft:zombie"), "Pos", list(Tag.DOUBLE, new DoubleTag(1), new DoubleTag(2), new DoubleTag(3)),
                "equipment", compound("mainhand", badSword()));
        CompoundTag minecart = compound("id", s("minecraft:minecart"), "Passengers", list(Tag.COMPOUND, zombie));
        CompoundTag[] chunks = new CompoundTag[1024];
        chunks[5] = compound("Entities", list(Tag.COMPOUND, minecart));
        Files.write(entities.resolve("r.-1.2.mca"), regionFile(chunks, Compression.LZ4));

        assertTrue(ScanRunner.run(options(root, false), Logger.getAnonymousLogger()));

        CompoundTag p = NbtIO.read(Compression.decompress(Compression.GZIP, Files.readAllBytes(dat), 0, (int) Files.size(dat))).tag();
        CompoundTag inv = (CompoundTag) p.getList("Inventory").values().get(0);
        assertEquals(10, ((IntTag) inv.getCompound("components").getCompound("minecraft:enchantments").get("minecraft:sharpness")).value());
        CompoundTag book = (CompoundTag) p.getList("EnderItems").values().get(0);
        assertEquals(10, ((IntTag) book.getCompound("components").getCompound("minecraft:stored_enchantments").get("minecraft:mending")).value());
        assertNull(p.getCompound("equipment").getCompound("head").getCompound("components").get("minecraft:unbreakable"));

        CompoundTag chunk = readChunk(Files.readAllBytes(entities.resolve("r.-1.2.mca")), 5);
        CompoundTag z = (CompoundTag) ((CompoundTag) chunk.getList("Entities").values().get(0)).getList("Passengers").values().get(0);
        assertNull(z.getCompound("equipment").getCompound("mainhand").getCompound("components").get("minecraft:unbreakable"));

        String report;
        try (var s = Files.list(root.resolve("plugins/Tagsmith/reports"))) {
            report = Files.readString(s.findFirst().orElseThrow());
        }
        assertTrue(report.contains("Passengers[0]{minecraft:zombie @ 1.0 2.0 3.0}.equipment.mainhand"), report);
    }

    @Test
    void oversizedChunkGoesToExternalFileAndBack(@TempDir Path root) throws Exception {
        Path regionDir = Files.createDirectories(root.resolve("world/region"));
        // ~1.5 MB of incompressible data plus one bad item -> must live in c.X.Z.mcc
        byte[] noise = new byte[1_500_000];
        new Random(7).nextBytes(noise);
        CompoundTag chunk = compound("blob", new ByteArrayTag(noise),
                "block_entities", list(Tag.COMPOUND, compound("id", s("minecraft:chest"), "x", i(0), "y", i(0), "z", i(0),
                        "Items", list(Tag.COMPOUND, badSword()))));
        byte[] compressed = Compression.compress(Compression.ZLIB, NbtIO.write(new NbtIO.Root("", chunk)));
        Files.write(regionDir.resolve("c.32.0.mcc"), compressed);
        ByteBuffer region = ByteBuffer.allocate(8192 + 4096);
        region.putInt(0, (2 << 8) | 1);
        region.putInt(8192, 1).put(8196, (byte) (Compression.ZLIB | 0x80));
        Files.write(regionDir.resolve("r.1.0.mca"), region.array());

        assertTrue(ScanRunner.run(options(root, false), Logger.getAnonymousLogger()));

        byte[] mcc = Files.readAllBytes(regionDir.resolve("c.32.0.mcc"));
        CompoundTag fixed = NbtIO.read(Compression.decompress(Compression.ZLIB, mcc, 0, mcc.length)).tag();
        CompoundTag sword = (CompoundTag) ((CompoundTag) fixed.getList("block_entities").values().get(0)).getList("Items").values().get(0);
        assertNull(sword.getCompound("components").get("minecraft:unbreakable"));
        assertArrayEquals(noise, ((ByteArrayTag) fixed.get("blob")).value());
        byte[] r = Files.readAllBytes(regionDir.resolve("r.1.0.mca"));
        assertEquals((byte) (Compression.ZLIB | 0x80), r[8196]);
    }

    static CompoundTag effect(String id, byte amplifier) {
        return compound("id", s(id), "amplifier", new ByteTag(amplifier), "duration", i(600));
    }

    static int amplifier(Tag effect) {
        return Byte.toUnsignedInt(((ByteTag) ((CompoundTag) effect).get("amplifier")).value());
    }

    @Test
    void clampsCustomPotionEffectAmplifiers(@TempDir Path root) throws Exception {
        Path players = Files.createDirectories(root.resolve("world/playerdata"));
        CompoundTag potion = item("minecraft:splash_potion", 1, compound("minecraft:potion_contents", compound(
                "custom_effects", list(Tag.COMPOUND,
                        effect("minecraft:strength", (byte) 14),     // at limit: kept
                        effect("minecraft:strength", (byte) 15),     // -> 3
                        effect("minecraft:speed", (byte) -1),        // 255 -> 3
                        effect("minecraft:resistance", (byte) 3),    // at resistance limit: kept
                        effect("minecraft:resistance", (byte) 4),    // -> 3
                        effect("minecraft:regeneration", (byte) 0),  // kept
                        compound("id", s("minecraft:haste"), "duration", i(20)))))); // no amplifier (= 0): kept
        CompoundTag legacyArrow = compound("id", s("minecraft:tipped_arrow"), "Count", new ByteTag((byte) 1),
                "tag", compound("CustomPotionEffects", list(Tag.COMPOUND,
                        compound("Id", new ByteTag((byte) 11), "Amplifier", new ByteTag((byte) 100)),    // resistance -> 3
                        compound("Id", new ByteTag((byte) 5), "Amplifier", new ByteTag((byte) 10)))));  // strength: kept
        CompoundTag player = compound("Inventory", list(Tag.COMPOUND, withSlot(potion, 0), withSlot(legacyArrow, 1)));
        Path dat = players.resolve("11111111-1111-1111-1111-111111111111.dat");
        Files.write(dat, Compression.compress(Compression.GZIP, NbtIO.write(new NbtIO.Root("", player))));

        assertTrue(ScanRunner.run(options(root, false), Logger.getAnonymousLogger()));

        CompoundTag p = NbtIO.read(Compression.decompress(Compression.GZIP, Files.readAllBytes(dat), 0, (int) Files.size(dat))).tag();
        List<Tag> effects = ((CompoundTag) p.getList("Inventory").values().get(0)).getCompound("components")
                .getCompound("minecraft:potion_contents").getList("custom_effects").values();
        assertEquals(List.of(14, 3, 3, 3, 3, 0), effects.subList(0, 6).stream().map(ScanTest::amplifier).toList());
        assertNull(((CompoundTag) effects.get(6)).get("amplifier"));
        assertEquals(600, ((IntTag) ((CompoundTag) effects.get(1)).get("duration")).value());

        List<Tag> legacy = ((CompoundTag) p.getList("Inventory").values().get(1)).getCompound("tag")
                .getList("CustomPotionEffects").values();
        assertEquals(3, ((ByteTag) ((CompoundTag) legacy.get(0)).get("Amplifier")).value());
        assertEquals(10, ((ByteTag) ((CompoundTag) legacy.get(1)).get("Amplifier")).value());
    }

    static CompoundTag withSlot(CompoundTag item, int slot) {
        item.put("Slot", i(slot));
        return item;
    }
}
