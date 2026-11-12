# AquaticSMP

**Developed by [tombomeke](https://github.com/tombomeke).**

One plugin jar for the Aquatic SMP pirate server (Paper 1.21.4+, Java 21): crews, Doubloons, bounties, procedurally generated islands with claiming and conquest, buried treasure with treasure maps, Abyss Fruits and pirate gear, the Conqueror of the Sea tournament and seven server events.

Everything is split into **modules** that can be switched on and off at runtime. Each module has its own config file with every number, toggle and message in it.

## Build

```bash
./build.sh
```

Output: `aquatic-dist/target/AquaticSMP-1.0.0.jar`. The script uses `mvn` from PATH, or IntelliJ's bundled Maven as a fallback. Java 21 is required.

## Install

1. Run a Paper 1.21.4 (or newer 1.21.x) server.
2. Put `AquaticSMP-1.0.0.jar` in `plugins/` and start the server once.
3. Optional plugins it hooks into automatically: **Vault** (other plugins see Doubloons as the server economy), **PlaceholderAPI** (`%aquatic_crew%`, `%aquatic_bounty%`, ...), **BlueMap** (island markers). Ships themselves come from **Movecraft**; this plugin does not depend on it.
4. Configure in `plugins/AquaticSMP/`:
   - `config.yml`: chat prefix and which modules are on.
   - `modules/<module>.yml`: one file per module, heavily commented.
   - `data/`: saved state (do not edit while the server runs).

> **Using EssentialsX?** EssentialsX also has `/pay`, `/balance`, `/bal`, `/money` and `/baltop`, so the two plugins fight over those names. The fix: add the EssentialsX economy commands to `disabled-commands` in `plugins/Essentials/config.yml`, install Vault, then restart. Do not turn off AquaticSMP's `economy` module instead: bounties, crew banks and claim costs need it. The full steps, plus the TAB and chat plugin overlaps, are in [COMMANDS.md → EssentialsX conflict](COMMANDS.md#essentialsx-conflict).
>
> See **[COMMANDS.md](COMMANDS.md)** for every command, what it does and its permission.

## Configuration

All files are created on first start in `plugins/AquaticSMP/`. Every option has a comment above it that explains it.

```
plugins/AquaticSMP/
├── config.yml          chat prefix + which modules are on
├── modules/
│   ├── economy.yml     currency name, starting balance, mob money, PvP loss, Vault
│   ├── crews.yml       crew size, costs, rank limits and rights, war rules, home, chat/tab tags
│   ├── bounty.yml      minimum bounty, tax, notoriety, bounty titles
│   ├── combat.yml      combat tag, respawn protection, new-player protection
│   ├── islands.yml     ocean area, island block palettes, claiming, raiding, conquest, income
│   ├── treasure.yml    loot tiers and tables, burial depth, natural spawning, map drops
│   ├── items.yml       every custom item and Abyss Fruit, ability numbers, triggers, recipes
│   ├── tournament.yml  stages, team size, rewards, crown, King perks
│   ├── events.yml      each server event, rewards, auto-scheduler
│   ├── guide.yml       new-player intro, starter kit, tips and the /guide book
│   └── sidebar.yml     the stats sidebar lines
└── data/               saved game state (don't edit while the server runs)
```

How to change things:

1. Edit the file.
2. Run `/aquatic reload`, or `/aquatic reload <module>` for just one. No restart is needed.
3. To switch a module off completely, run `/aquatic disable <module>`.

Every player-facing message is in the `messages:` section at the bottom of its module file, in [MiniMessage](https://docs.advntr.dev/minimessage/format) format, so you can reword or translate everything.

Settings most servers change first:

| What | File | Key |
| --- | --- | --- |
| Currency name and starting Doubloons | `economy.yml` | `currency:`, `starting-balance` |
| Max crew size (default 6) | `crews.yml` | `max-members` |
| Cost to found a crew or declare war | `crews.yml` | `costs:`, `war:` |
| Where random islands and treasure go | `islands.yml` | `ocean-area:` (or `/island admin area` in-game) |
| Island sizes for spawnrandom (small … huge) | `islands.yml` | `generation.island-sizes`, `generation.radius-limit` |
| Ability effects amount / colours | `items.yml` | `effects.quality`, `color:` per ability |
| Island claim price and limit | `islands.yml` | `claiming.cost`, `claiming.max-per-crew` |
| When enemies can raid or conquer | `islands.yml` | `raiding.mode`, `conquest.mode` (`always`, `war-only` or `never`) |
| Conquest capture time | `islands.yml` | `conquest.capture-seconds` |
| Passive island income | `islands.yml` | `income:` |
| Island upgrades: prices, effects, what happens on conquest | `islands.yml` | `upgrades:` |
| Capital town (houses, roads, piers) | `islands.yml` | `types.CAPITAL.town` |
| How often treasure appears | `treasure.yml` | `natural.interval-minutes` |
| One Abyss Fruit per player, second fruit refused or lethal | `items.yml` | `fruits.one-per-player`, `fruits.second-fruit` |
| Tournament entry fee, minimum crews | `tournament.yml` | `registration:` |
| Finale length (best of N) | `tournament.yml` | `finale.best-of` |
| Automatic events | `events.yml` | `scheduler.enabled`, `scheduler.interval-minutes` |
| Flag designs and the default flag | `crews.yml` | `flag.presets`, `flag.default-preset` |
| Anyone may dig up treasure on claimed islands | `islands.yml` | `treasure-digging` |
| New-player intro (on/off, first join or every join) | `guide.yml` | `intro.enabled`, `intro.mode` |
| Starter kit items, Doubloons, treasure map | `guide.yml` | `starter-kit:` |
| Tips for new players | `guide.yml` | `tips:` |
| Combat tag length, logout punishment, blocked commands | `combat.yml` | `combat-tag:` |
| New-player PvP protection time | `combat.yml` | `new-player-protection.minutes` |

## Modules

| Module | Config | What it does |
| --- | --- | --- |
| `economy` | `economy.yml` | Doubloon balances, `/pay`, `/baltop`, mob kill money, PvP death loss, Vault provider |
| `crews` | `crews.yml` | Crews with Captain / First Mate / Navigator / Quartermaster / Member, bank, home, flag, crew chat, alliances, rivalries, wars, reputation, chat and tab tags |
| `bounty` | `bounty.yml` | Player-placed bounties, automatic notoriety, bounty tiers (Rookie → Dread Tyrant), wanted posters. Needs `economy` |
| `islands` | `islands.yml` | Island generator (7 types), claiming, raid protection, flag-capture conquest, discovery, passive income, BlueMap markers |
| `treasure` | `treasure.yml` | Loot tiers, buried treasure chests, treasure maps with a red X, natural spawning and map drops |
| `items` | `items.yml` | 8 Abyss Fruits with powers and sea weakness, Abyssal Iron gear, legendary swords, flintlock, hand cannon, Tide Compass, Echo Conch, rum, Conqueror of the Sea crown |
| `tournament` | `tournament.yml` | The Conqueror of the Sea tournament: registration → qualifiers → bracket → best-of finale → coronation, Hall of Kings. Needs `crews` |
| `events` | `events.yml` | Treasure Hunt, Pirate War, Bounty Hunt, Lost Island, King's Challenge, Grand Raid, Naval Battle; optional auto-scheduler |
| `combat` | `combat.yml` | Combat tag (no logging out or teleporting away mid-fight), respawn protection, new-player PvP protection |
| `sidebar` | `sidebar.yml` | Stats sidebar: Doubloons, bounty, crew, island, fruit power, events (`/sidebar` to hide) |
| `guide` | `guide.yml` | Welcome intro, starter kit with a treasure map, tips for new players, the `/guide` book |

Modules talk to each other only through shared services, so turning one off just disables the features that need it. For example, without `crews`, islands can still be generated and discovered but not claimed.

## Admin commands

All admin commands need `aquatic.admin` (ops have it) or the module's own `aquatic.<module>.admin`.

```
/aquatic modules | enable <module> | disable <module> | reload [module]
```

## Setting up the world

A suggested order for a fresh ocean world:

1. **Set the ocean area** where random islands and treasure go. Stand in the middle and run:
   ```
   /island admin area <radius> [spacing]
   ```
2. **Spawn island.** Generate it and mark it as the neutral spawn:
   ```
   /island admin spawn capital 70 Spawn
   /island admin setspawn
   ```
   Neutral islands have no PvP, building, claiming or explosions.
3. **Scatter islands:**
   ```
   /island admin spawnrandom random 25 30 70
   ```
   This creates 25 islands of random types with radius 30–70, auto-named. Leave out the radius (`/island admin spawnrandom random 25`) to get a mix of sizes from small (100) to huge (1000). Generation runs a little each tick, so the server keeps running, and progress shows in a bar at the top of your screen. `/island admin cancel` stops it.
4. **Single islands:**
   - `/island admin spawn <tropical|plains|jungle|volcanic|snowy|desert|ancient|capital> <radius> [name]` generates at your feet.
   - `/island admin register <name> <type> <radius>` turns terrain you built yourself into an island.
5. **Manage islands:**
   - `/island admin delete <island> restore`, `/island admin regen <island>`
   - `/island admin setstatus <island> neutral`, `/island admin setowner <island> <crew|none>`
   - `/island admin rename`, `/island admin setradius`, `/island admin tp`, `/island admin shield <island> <minutes>`
6. **Treasure:**
   - `/treasure spawn <common|uncommon|rare|epic|legendary> [here|random]`
   - `/treasure map <tier> [player]` spawns a treasure and hands out a map to it.
   - `/treasure list | tp <n> | remove <n|all> | give-loot <tier>`
   - Natural spawning is configured in `treasure.yml`.
7. **Tournament arenas:**
   ```
   /tournament arena create <name>
   /tournament arena setspawn <name> 1
   /tournament arena setspawn <name> 2
   /tournament arena setspectator <name>
   /tournament setlobby
   ```
8. **Event locations:**
   - `/seaevent setlocation <event> [name]` for the King's Challenge hill and the Grand Raid spawn. Without a hill location, the King's Challenge uses the capital island.

## Running the Conqueror of the Sea tournament

| Step | Command | What happens |
| --- | --- | --- |
| 1 | `/tournament open [minutes]` | Registration opens. Crew officers run `/tournament register` and `/tournament roster add/remove <member>`. |
| 2 | `/tournament start` | Moves to the qualifiers. Crews score points for kills, treasure, claims, conquests and bounties. |
| 3 | `/tournament start` | The top crews go into the bracket. `/tournament next` runs each match (auto-advance is in config). |
| 4 | — | The finale is best-of-3 by default. The winning captain is crowned Conqueror of the Sea: crown item, prize, reputation, and your own console commands (e.g. a LuckPerms group). |

Other commands: `/tournament status | bracket | standings | cancel | forcewin <crew>`, `/tournament admin setking <player|none>`, `/hallofkings`.

## Server events

```
/seaevent start <type> [args]
/seaevent stop [type]
/seaevent list
/seaevent schedule on|off
```

Types:
- `treasure_hunt`
- `pirate_war <crewA> <crewB>`
- `bounty_hunt`
- `lost_island`
- `kings_challenge`
- `grand_raid`
- `naval_battle`

Players use `/seaevent info` and `/seaevent join`. Rewards, durations and minimum player counts are set per event in `events.yml`.

## Other admin commands

| Area | Commands |
| --- | --- |
| Economy | `/doubloons give|take|set <player> <amount>` (works for offline players) |
| Crews | `/crew admin disband|setcaptain|setrep|join|kick ...` |
| Bounty | `/bounty admin add|remove|clear <player> [amount]` |
| Items | `/aitem give <player> <id> [amount]`, `/aitem list`, `/aitem fruit set <player> <fruit|none>`, `/aitem fruit info [player]` |

`devil_fruit_random` is a special item id that gives a random Abyss Fruit. Treasure loot and event rewards can use it too.

## Player commands

| Command | |
| --- | --- |
| `/crew` (`/c`) | `create`, `invite`, `accept`, `leave`, `kick`, `promote`, `demote`, `setrank`, `transfer`, `info`, `list`, `top`, `color`, `tag`, `flag`, `home`, `sethome`, `bank`, `ally`, `rival`, `war`, `peace`, `neutral`, `friendlyfire`, `chat` |
| `/cc <msg>` | Crew chat |
| `/doubloons`, `/pay`, `/baltop` | Money |
| `/bounty set|info|top|list`, `/wanted` | Bounties and wanted posters |
| `/island info|here|list|map|discovered|top|claim|unclaim|flag` | Islands. Claiming needs Captain or First Mate. |
| `/treasure info` | Active treasure hints |
| `/fruit` | Your Abyss Fruit power and cooldown |
| `/tournament register|roster|standings|bracket|info`, `/hallofkings` | Tournament |
| `/seaevent info|join` | Events |

Every player permission is `aquatic.<module>.<feature>` and granted to everyone by default. Revoke one with LuckPerms to lock that feature.

## Gameplay notes

- **Conquest:** enemy crew members stand near an island's flag. The capture bar only fills while no defender is in range, and it decays otherwise. These are configurable in `islands.yml`:
  - war-only or always
  - offline protection with a grace period
  - minimum attackers
  - capture time
  - shield after a capture
- **Raiding** (building, breaking or opening chests on enemy islands): `raiding.mode` = `always | war-only | never`.
- **Abyss Fruits:**
  - One per player. You lose the power when in water.
  - Use the ability with **sneak + F** (swap hands), or sneak + right-click while looking at a block. Minecraft does not send right-clicks on air with an empty hand.
  - A Abyssal Iron hit shuts the power off for a few seconds.
  - Default fruit names are inspired by the anime and can be renamed in `items.yml`, along with every number.
- **Custom item textures:** every item has an optional `custom-model-data`, so you can add textures later with a resource pack or Oraxen.

## Project layout

```
aquatic-core/    plugin entry point, module system, command framework, shared API (dev.aquatic.api)
aquatic-crews/   economy, crews, bounty
aquatic-world/   islands, treasure
aquatic-events/  items, tournament, server events
aquatic-dist/    shades everything into the final jar
```

### Adding a module

1. Extend `AquaticModule`.
2. Add a `modules/<id>.yml` resource.
3. Register the class in the `MODULES` list in `AquaticPlugin`.

---

AquaticSMP is developed by **[tombomeke](https://github.com/tombomeke)**. In-game, `/aquatic about` shows the credits.
