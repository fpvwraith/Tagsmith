# Tagsmith

A Paper/Purpur 1.21.11 plugin that scans all player data, block entity data and entity data on disk (before the worlds load) and fixes illegal items. It checks items nested at any depth: shulkers, bundles, entity equipment, passengers, villager trades, ender chests and so on.

## Rules
- `totem_of_undying` / bundles with `max_stack_size`: the component is removed and the count set to 1
- Enchantments above level 18 are set to level 10 (stored enchantments on books too)
- `unbreakable` is removed
- `attribute_modifiers` is removed
- Custom potion effect amplifiers outside 0–14 are set to 3 (for resistance, outside 0–3 → 3)

## Usage
1. Back up your worlds.
2. Drop `Tagsmith-x.y.z.jar` into `plugins/` and start the server. The scan runs during startup, spread across all CPU cores.
3. When the scan succeeds, `run-on-next-startup` is set back to `false`. To run it again, use `/tagsmith arm` and restart.

Set `dry-run: true` in `config.yml` to only write the report. Originals of modified files go to `plugins/Tagsmith/backups/`, and reports go to `plugins/Tagsmith/reports/`.

## Building
```
./gradlew build
```
