package com.tagsmith;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public final class Tagsmith extends JavaPlugin {

    private static final String RUN_KEY = "run-on-next-startup";

    /**
     * onLoad runs while the server is starting, before any world is loaded, so the region and
     * player files are not open and cannot be overwritten by the server while we edit them.
     */
    @Override
    public void onLoad() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        if (!config.getBoolean(RUN_KEY, false)) {
            getLogger().info("Scan not armed (" + RUN_KEY + ": false). Use /tagsmith arm and restart to run it.");
            return;
        }
        if (!Bukkit.getWorlds().isEmpty()) {
            getLogger().severe("Worlds are already loaded (plugin reload?). Refusing to touch world files; restart the server instead.");
            return;
        }

        ItemFixer.Settings settings = new ItemFixer.Settings(
                normalizeIds(config.getStringList("rules.stack-size-items")),
                config.getInt("rules.enchantments.max-level", 18),
                config.getInt("rules.enchantments.reduced-level", 10),
                config.getBoolean("rules.enchantments.include-stored-enchantments", true),
                config.getBoolean("rules.remove-unbreakable", true),
                config.getBoolean("rules.remove-attribute-modifiers", true),
                config.getBoolean("rules.fix-legacy-format", true),
                new ItemFixer.PotionRules(
                        config.getBoolean("rules.potion-effects.enabled", true),
                        config.getInt("rules.potion-effects.max-amplifier", 14),
                        config.getInt("rules.potion-effects.replacement-amplifier", 3),
                        amplifierOverrides(config.getConfigurationSection("rules.potion-effects.max-amplifier-overrides"))));

        List<String> paths = config.getStringList("paths");
        boolean dryRun = config.getBoolean("dry-run", false);
        ScanRunner.Options options = new ScanRunner.Options(
                Bukkit.getWorldContainer().toPath(), paths, config.getInt("threads", 0), dryRun,
                config.getBoolean("backup-modified-files", true), getDataFolder().toPath(), settings);

        getLogger().info("Starting scan. The server will finish starting once it is done.");
        try {
            boolean clean = ScanRunner.run(options, getLogger());
            if (dryRun) {
                getLogger().info("Dry run complete; scan stays armed. Set dry-run: false and restart to apply the changes.");
            } else {
                config.set(RUN_KEY, false);
                saveConfig();
                getLogger().info((clean ? "Scan complete." : "Scan complete WITH ERRORS (see warnings above).")
                        + " " + RUN_KEY + " has been set to false.");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Scan aborted; " + RUN_KEY + " left as true", e);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "arm" -> {
                getConfig().set(RUN_KEY, true);
                saveConfig();
                sender.sendMessage("Tagsmith armed: the scan will run on the next server startup"
                        + (getConfig().getBoolean("dry-run") ? " (DRY RUN)." : "."));
            }
            case "disarm" -> {
                getConfig().set(RUN_KEY, false);
                saveConfig();
                sender.sendMessage("Tagsmith disarmed.");
            }
            case "status" -> sender.sendMessage("Tagsmith: " + RUN_KEY + " = " + getConfig().getBoolean(RUN_KEY)
                    + ", dry-run = " + getConfig().getBoolean("dry-run"));
            default -> {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> amplifierOverrides(ConfigurationSection section) {
        Map<String, Integer> out = new HashMap<>();
        if (section == null) return out;
        for (String key : section.getKeys(false)) {
            String id = key.trim().toLowerCase();
            out.put(id.contains(":") ? id : "minecraft:" + id, section.getInt(key));
        }
        return out;
    }

    private static Set<String> normalizeIds(List<String> ids) {
        Set<String> out = new HashSet<>();
        for (String id : ids) {
            String lower = id.trim().toLowerCase();
            out.add(lower.contains(":") ? lower : "minecraft:" + lower);
        }
        return out;
    }
}
