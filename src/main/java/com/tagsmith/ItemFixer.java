package com.tagsmith;

import com.tagsmith.nbt.Tag;
import com.tagsmith.nbt.Tag.CompoundTag;
import com.tagsmith.nbt.Tag.ListTag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks an entire NBT tree and fixes every item stack it finds.
 * <p>
 * Instead of listing every place an item can live (Items, equipment, Passengers, bundle_contents,
 * container, block_entity_data, villager trades, ender chests, ...) the walker visits every compound
 * in the tree and treats anything shaped like an item stack as one. That makes nested containers,
 * mobs riding minecarts, shulkers inside bundles inside chests, etc. all covered automatically.
 * <p>
 * Instances are stateless apart from their settings and may be shared between threads.
 */
public final class ItemFixer {

    public record Settings(
            Set<String> stackSizeItems,
            int maxEnchantLevel,
            int reducedEnchantLevel,
            boolean includeStoredEnchantments,
            boolean removeUnbreakable,
            boolean removeAttributeModifiers,
            boolean fixLegacyFormat,
            PotionRules potionRules
    ) {}

    /**
     * Custom potion effect amplifiers above {@code maxAmplifier} (or the effect's override) or below 0
     * are set to {@code replacementAmplifier}. Override keys are full effect ids, e.g. minecraft:resistance.
     */
    public record PotionRules(
            boolean enabled,
            int maxAmplifier,
            int replacementAmplifier,
            Map<String, Integer> maxAmplifierOverrides
    ) {}

    /** Numeric effect ids used by the pre-1.20.2 CustomPotionEffects format. */
    private static final String[] LEGACY_EFFECT_IDS = {
            null, "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
            "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing",
            "invisibility", "blindness", "night_vision", "hunger", "weakness", "poison", "wither", "health_boost",
            "absorption", "saturation", "glowing", "levitation", "luck", "unluck", "slow_falling",
            "conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village", "darkness"
    };

    private final Settings settings;
    private final byte[][] prefilterPatterns;

    public ItemFixer(Settings settings) {
        this.settings = settings;
        // Every key this fixer can act on contains one of these byte sequences. NBT keys are stored as
        // plain (modified UTF-8) bytes, so a chunk whose decompressed bytes contain none of them cannot
        // need changes and does not have to be parsed at all.
        List<String> patterns = new ArrayList<>();
        patterns.add("nchantments"); // enchantments, stored_enchantments, Enchantments, StoredEnchantments
        if (!settings.stackSizeItems().isEmpty()) patterns.add("max_stack_size");
        if (settings.removeUnbreakable()) patterns.add("nbreakable");
        if (settings.removeAttributeModifiers()) {
            patterns.add("attribute_modifiers");
            patterns.add("AttributeModifiers");
        }
        if (settings.potionRules().enabled()) {
            patterns.add("custom_effects");        // 1.20.5+ potion_contents
            patterns.add("custom_potion_effects"); // 1.20.2 - 1.20.4
            patterns.add("CustomPotionEffects");   // older
        }
        this.prefilterPatterns = patterns.stream()
                .map(p -> p.getBytes(StandardCharsets.US_ASCII))
                .toArray(byte[][]::new);
    }

    /** Cheap check on raw uncompressed NBT bytes: false means the data definitely needs no changes. */
    public boolean mightNeedFixing(byte[] nbt) {
        for (byte[] pattern : prefilterPatterns) {
            if (indexOf(nbt, pattern) >= 0) return true;
        }
        return false;
    }

    /**
     * Fixes every item in the tree.
     *
     * @param root     the tree to fix in place
     * @param location human-readable location prefix for report lines (file, chunk)
     * @param report   receives one line per modified item
     * @return true if anything changed
     */
    public boolean fixTree(CompoundTag root, String location, List<String> report, FixStats stats) {
        Walk walk = new Walk(location, report, stats);
        walk.path.add("");
        return walk.visit(root);
    }

    private final class Walk {
        final String location;
        final List<String> report;
        final FixStats stats;
        final ArrayList<String> path = new ArrayList<>();

        Walk(String location, List<String> report, FixStats stats) {
            this.location = location;
            this.report = report;
            this.stats = stats;
        }

        boolean visit(Tag tag) {
            boolean changed = false;
            if (tag instanceof CompoundTag compound) {
                int last = path.size() - 1;
                String segment = path.get(last);
                String label = describe(compound);
                if (label != null) path.set(last, segment + label);

                if (looksLikeItem(compound)) {
                    changed |= fixItem(compound);
                }
                for (Map.Entry<String, Tag> entry : compound.map().entrySet()) {
                    Tag child = entry.getValue();
                    if (child instanceof CompoundTag || child instanceof ListTag) {
                        path.add(entry.getKey());
                        changed |= visit(child);
                        path.removeLast();
                    }
                }
                path.set(last, segment);
            } else if (tag instanceof ListTag list
                    && (list.elementType() == Tag.COMPOUND || list.elementType() == Tag.LIST)) {
                List<Tag> values = list.values();
                for (int i = 0; i < values.size(); i++) {
                    path.add("[" + i + "]");
                    changed |= visit(values.get(i));
                    path.removeLast();
                }
            }
            return changed;
        }

        private boolean fixItem(CompoundTag item) {
            String id = item.getString("id");
            List<String> actions = new ArrayList<>(2);

            CompoundTag components = item.getCompound("components");
            if (components != null) {
                // 1. Stackable totems / bundles.
                if (settings.stackSizeItems().contains(id) && removeComponent(components, "max_stack_size")) {
                    actions.add("removed max_stack_size");
                    stats.maxStackSizeRemoved.increment();
                    Tag count = item.get("count");
                    if (count != null && Tag.isNumeric(count) && Tag.asDouble(count) > 1) {
                        actions.add("count " + (long) Tag.asDouble(count) + " -> 1");
                        item.put("count", Tag.numericOfSameType(count, 1));
                        stats.countsReduced.increment();
                    }
                }
                // 2. Over-levelled enchantments.
                fixEnchantmentComponent(components, "enchantments", actions);
                if (settings.includeStoredEnchantments()) {
                    fixEnchantmentComponent(components, "stored_enchantments", actions);
                }
                // 3. Unbreakable.
                if (settings.removeUnbreakable() && removeComponent(components, "unbreakable")) {
                    actions.add("removed unbreakable");
                    stats.unbreakableRemoved.increment();
                }
                // 4. Attribute modifiers.
                if (settings.removeAttributeModifiers() && removeComponent(components, "attribute_modifiers")) {
                    actions.add("removed attribute_modifiers");
                    stats.attributeModifiersRemoved.increment();
                }
                // 5. Custom potion effect amplifiers (potions, splash/lingering potions, tipped arrows).
                if (settings.potionRules().enabled()
                        && getComponent(components, "potion_contents") instanceof CompoundTag contents) {
                    fixEffectList(contents.getList("custom_effects"), "id", "amplifier", actions);
                }
            }

            // Pre-1.20.5 item format, found in chunks that haven't been loaded since the upgrade.
            CompoundTag legacy = settings.fixLegacyFormat() ? item.getCompound("tag") : null;
            if (legacy != null) {
                fixLegacyEnchantments(legacy, "Enchantments", actions);
                if (settings.includeStoredEnchantments()) {
                    fixLegacyEnchantments(legacy, "StoredEnchantments", actions);
                }
                if (settings.removeUnbreakable() && legacy.remove("Unbreakable") != null) {
                    actions.add("removed legacy Unbreakable");
                    stats.unbreakableRemoved.increment();
                }
                if (settings.removeAttributeModifiers() && legacy.remove("AttributeModifiers") != null) {
                    actions.add("removed legacy AttributeModifiers");
                    stats.attributeModifiersRemoved.increment();
                }
                if (settings.potionRules().enabled()) {
                    fixEffectList(legacy.getList("custom_potion_effects"), "id", "amplifier", actions); // 1.20.2 - 1.20.4
                    fixEffectList(legacy.getList("CustomPotionEffects"), "Id", "Amplifier", actions);   // older
                }
            }

            if (actions.isEmpty()) return false;
            stats.itemsModified.increment();
            report.add(location + " | " + currentPath() + " | " + id + " | " + String.join(", ", actions));
            return true;
        }

        private String currentPath() {
            StringBuilder sb = new StringBuilder();
            for (String segment : path) {
                if (!sb.isEmpty() && !segment.startsWith("[")) sb.append('.');
                sb.append(segment);
            }
            return sb.toString();
        }

        private void fixEnchantmentComponent(CompoundTag components, String name, List<String> actions) {
            Tag tag = getComponent(components, name);
            if (!(tag instanceof CompoundTag enchantments)) return;
            // 1.20.5 - 1.21.4 wrap the levels as {levels:{...}, show_in_tooltip:...}; 1.21.5+ store them directly.
            CompoundTag levels = enchantments.getCompound("levels");
            if (levels == null) levels = enchantments;
            for (Map.Entry<String, Tag> entry : levels.map().entrySet()) {
                Tag level = entry.getValue();
                if (Tag.isNumeric(level) && Tag.asDouble(level) > settings.maxEnchantLevel()) {
                    actions.add(entry.getKey() + " " + (long) Tag.asDouble(level) + " -> " + settings.reducedEnchantLevel());
                    entry.setValue(Tag.numericOfSameType(level, settings.reducedEnchantLevel()));
                    stats.enchantmentsReduced.increment();
                }
            }
        }

        private void fixEffectList(ListTag effects, String idKey, String amplifierKey, List<String> actions) {
            if (effects == null) return;
            PotionRules rules = settings.potionRules();
            for (Tag element : effects.values()) {
                if (!(element instanceof CompoundTag effect)) continue;
                Tag amplifier = effect.get(amplifierKey); // absent means 0
                if (amplifier == null || !Tag.isNumeric(amplifier)) continue;
                // The game stores the amplifier as an unsigned byte, so -1b means 255.
                long value = amplifier instanceof Tag.ByteTag b ? Byte.toUnsignedInt(b.value()) : (long) Tag.asDouble(amplifier);
                String effectId = effectId(effect.get(idKey));
                int max = effectId == null ? rules.maxAmplifier()
                        : rules.maxAmplifierOverrides().getOrDefault(effectId, rules.maxAmplifier());
                if (value >= 0 && value <= max) continue;
                actions.add("effect " + effectId + " amplifier " + value + " -> " + rules.replacementAmplifier());
                effect.put(amplifierKey, Tag.numericOfSameType(amplifier, rules.replacementAmplifier()));
                stats.potionAmplifiersReduced.increment();
            }
        }

        private void fixLegacyEnchantments(CompoundTag legacy, String key, List<String> actions) {
            ListTag list = legacy.getList(key);
            if (list == null) return;
            for (Tag element : list.values()) {
                if (!(element instanceof CompoundTag enchantment)) continue;
                Tag level = enchantment.get("lvl");
                if (level != null && Tag.isNumeric(level) && Tag.asDouble(level) > settings.maxEnchantLevel()) {
                    actions.add("legacy " + enchantment.getString("id") + " " + (long) Tag.asDouble(level)
                            + " -> " + settings.reducedEnchantLevel());
                    enchantment.put("lvl", Tag.numericOfSameType(level, settings.reducedEnchantLevel()));
                    stats.enchantmentsReduced.increment();
                }
            }
        }
    }

    /** An item stack has a string id plus a count and/or components (modern) or Count (legacy). */
    private static boolean looksLikeItem(CompoundTag tag) {
        if (!(tag.get("id") instanceof Tag.StringTag)) return false;
        Map<String, Tag> map = tag.map();
        return map.containsKey("count") || map.containsKey("components") || map.containsKey("Count");
    }

    /** Short label for block entities and entities so report paths say what and where they are. */
    private static String describe(CompoundTag tag) {
        String id = tag.getString("id");
        if (id == null) return null;
        Tag x = tag.get("x"), y = tag.get("y"), z = tag.get("z");
        if (x != null && y != null && z != null && Tag.isNumeric(x) && Tag.isNumeric(y) && Tag.isNumeric(z)) {
            return String.format(Locale.ROOT, "{%s @ %d %d %d}", id,
                    (long) Tag.asDouble(x), (long) Tag.asDouble(y), (long) Tag.asDouble(z));
        }
        ListTag pos = tag.getList("Pos");
        if (pos != null && pos.values().size() == 3 && Tag.isNumeric(pos.values().get(0))) {
            return String.format(Locale.ROOT, "{%s @ %.1f %.1f %.1f}", id,
                    Tag.asDouble(pos.values().get(0)), Tag.asDouble(pos.values().get(1)),
                    Tag.asDouble(pos.values().get(2)));
        }
        return null;
    }

    private static String effectId(Tag id) {
        if (id instanceof Tag.StringTag s) {
            String value = s.value().toLowerCase(Locale.ROOT);
            return value.contains(":") ? value : "minecraft:" + value;
        }
        if (id != null && Tag.isNumeric(id)) {
            int n = (int) Tag.asDouble(id);
            if (n > 0 && n < LEGACY_EFFECT_IDS.length) return "minecraft:" + LEGACY_EFFECT_IDS[n];
        }
        return null;
    }

    private static Tag getComponent(CompoundTag components, String name) {
        Tag tag = components.get("minecraft:" + name);
        return tag != null ? tag : components.get(name);
    }

    private static boolean removeComponent(CompoundTag components, String name) {
        boolean removed = components.remove("minecraft:" + name) != null;
        removed |= components.remove(name) != null;
        return removed;
    }

    static int indexOf(byte[] data, byte[] pattern) {
        byte first = pattern[0];
        int max = data.length - pattern.length;
        outer:
        for (int i = 0; i <= max; i++) {
            if (data[i] != first) continue;
            for (int j = 1; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
