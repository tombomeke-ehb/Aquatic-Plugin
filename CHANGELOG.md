# Changelog

## 1.0.3 (2026-11-12)

### Changed
- **Island admin commands:** these are now listed only under `/island admin`, so admins no longer see every command twice in help and tab completion. The short form, such as `/island spawn`, still works.
- **`/island list`:** now shows each island's coordinates. Admins also get a clickable **[TP]** button behind every island.
  - Existing servers keep the old line format, because the message is already saved in their `islands.yml`.
  - To get coordinates there, delete the `list-entry:` line from the `messages:` section of `islands.yml`, then run `/aquatic reload islands`.

## 1.0.2 (2026-11-12)

### Added
- **Credits:** "Developed by tombomeke" now appears in the startup banner in the server console and in `plugin.yml` (shown by `/version AquaticSMP` and `/plugins`).
- **`/aquatic about`** (aliases `credits`, `version`): shows the version, the author and the project link. It is open to every player. Running `/aquatic` with no arguments shows the same card, followed by the admin help for staff. The admin sub-commands still need `aquatic.admin`.

## 1.0.1 (2026-11-12)

Bug-fix release. Tested on a Paper 1.21.4 server from the console: startup, reload, toggling modules, island generation, the Lost Island event and shutdown. Features that need a player in the game have not been tested yet.

### Fixed
- **Commands after a reload:** disabling or reloading a module did not remove its commands, so `/crew` and others could keep running the old copies. Commands are now cleanly removed and registered again.
- **Re-enabling a module:** modules that were switched off only because a module they depend on went off now come back when that module is turned on again. Example: `/aquatic enable crews` brings `tournament` back.
- **Lag spike from random islands:** `/island spawnrandom` no longer freezes the server for several seconds while it looks for free ocean. Spots are now checked in the background, and a few are prepared ahead of time so the Lost Island event starts instantly. New settings in `islands.yml`: `ocean-area.prefetch-spots` and `ocean-area.prefetch-radius`.
- **Bounty Hunt leftovers:** the event's extra bounties are now removed from targets nobody claimed. Turn this off with `remove-unclaimed-bounties` in `events.yml`.
- **Treasure loot:** chests now contain the Log Pose and random Devil Fruits; before, those entries used item names that didn't exist and were skipped. `devil_fruit_random` is a new item id that gives a random Devil Fruit.
- **Devil Fruit and sword abilities:**
  - A dash that killed a player could crash the task that runs abilities every tick.
  - When one ability hit set off another, the first could be wrongly counted as blocked.
- **Slow memory growth:** each module kept every finished delayed task in a list forever. Finished tasks are now cleared.
- **Server hang:** `/island discovered <player>` could freeze the server while looking up an unknown player name online. It now only uses players the server has seen before.
- **Pirate King crown in chat:** the crown prefix could show stale information.

### Documentation
- Added `COMMANDS.md`: every command and its permission, plus how to run alongside EssentialsX, TAB and chat plugins.
- Added a Configuration section to `README.md`.

## 1.0.0 (2026-11-12)

First build: economy, crews, bounty, islands, treasure, items, tournament and events modules.
