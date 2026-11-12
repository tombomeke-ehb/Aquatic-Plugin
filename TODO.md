# AquaticSMP: To do

The roadmap for the next versions. Details are still open and will be worked out per feature.

## Planned features

### 1. Island management and development
Owned islands should become something a crew builds up over time, not just a claim.
- [ ] **Island levels / upgrades** bought with Doubloons from the crew bank. For example: more passive income, a bigger protected radius, a stronger conquest defence (longer capture time), and more members allowed to build.
- [ ] **Island buildings or "facilities"** that each give a bonus:
  - a dock: faster boats, or a respawn point
  - a market: extra income
  - a watchtower: alerts when enemies land
  - a vault: a safe crew storage
- [ ] **Resources per island type**, for example volcanic → ores, jungle → wood/food, desert → treasure chance.
- [ ] **`/island manage` GUI** (chest menu) to see stats, buy upgrades, set permissions per rank and collect income.
- [ ] **Island stats:** income history, visitors, times attacked.
- [ ] Decide what happens to upgrades when an island is conquered: keep them, lose some, or the conqueror gets them.

### 2. Shop
- [ ] **Server shop** (`/shop`) as a chest GUI, with categories: tools, food, ship parts, pirate gear, rare items.
- [ ] Buy and sell for Doubloons. Prices go in a `shop.yml`, and support custom items (`item:<id>`).
- [ ] **Configurable in-game:** admins add items, set prices and change categories through the GUI, without editing YAML.
- [ ] Optional: limited stock, rotating daily deals, rank- or Pirate King-only items.
- [ ] Optional: player shops / trading posts on neutral islands.
- [ ] Decide whether the shop replaces or works alongside EssentialsX `/sell` and `/worth`.

### 3. Haki
- [ ] **Armament Haki (Busoshoku):** a toggle that adds damage and lets you **hit Logia devil fruit users**. A Logia user (for example `mera_mera`, `goro_goro` or `hie_hie`) should dodge normal hits unless the attacker uses Haki or Sea Prism.
- [ ] **Observation Haki (Kenbunshoku):** sense nearby players through walls (glowing), a short dodge window, and warnings when someone targets you.
- [ ] **Conqueror's Haki (Haoshoku):** **only some players have it**, either at random on first join, or rare and earned. It is an area blast that knocks back and stuns weaker players, and its power scales with your Haki level.
- [ ] **Training and progression:** Haki XP from fighting, using Haki, training dummies or special training islands. Levels unlock longer duration, more power and lower cooldowns.
- [ ] Haki energy / stamina bar, so it can't be used non-stop.
- [ ] `/haki` command: status, levels and toggles, plus admin commands to set levels or grant Conqueror's Haki.
- [ ] Add a "logia" flag to devil fruits in `items.yml`, so the dodge rule knows which fruits it applies to.

### 4. More and better abilities
- [ ] More abilities per devil fruit (2–3 per fruit), switched with a key or the hotbar slot.
- [ ] **Awakening:** a stronger version of a fruit after a lot of use.
- [ ] More fruits: Paramecia, Zoan and Logia variety.
- [ ] Better visuals: particles, sounds, short animations.
- [ ] Balance pass: damage, cooldowns, PvP vs PvE values.

### 5. More treasure items
- [ ] More unique loot: legendary weapons and armour sets, accessories with passive buffs, ship upgrades, Haki training scrolls, rare cosmetics such as hats and titles.
- [ ] Item rarities shown in the name and lore.
- [ ] Set bonuses for wearing matching pirate gear.

### 6. In-game configuration
- [ ] **Admin GUI** (`/aquatic config`) to change the most-used settings in-game: module toggles, costs, cooldowns, treasure loot, shop prices and item stats. Changes are saved to the YAML files.
- [ ] Edit loot tables in-game: hold an item → `/treasure loot add <tier> [weight]`.
- [ ] Create custom items in-game: hold an item → `/aitem create <id>`, then set its type and ability.

## Still open from before

- [ ] **Test in-game** everything that needs players:
  - crews, claiming and conquest
  - Devil Fruits and gear
  - tournament matches
  - treasure maps
  - PvP events, combat tag, starter kit and tips
- [ ] **Movecraft integration:** ships owned by a crew, only the crew can pilot them, and Naval Battle counts real ships.
- [ ] **Personal world map:** only shows islands you have discovered (BlueMap currently shows everything).
- [ ] **Seasons:** a command to start a new season, resetting bounties, reputation and/or islands.
- [ ] **Known limitations:**
  - PvP is allowed in tournament matches even on neutral islands.
  - A totem of undying doesn't save you in a tournament match.
  - Fruit abilities can't be triggered by right-clicking empty air (use sneak + F).
  - The offline-raid grace period resets after a restart.
- [ ] **Shared API:** add events for alliance, peace, rank change, island unclaim and conquest start, plus economy transfer and name lookup.
- [ ] **Automated tests** for the core and the most important rules.
- [ ] **Messages after an update:** improved messages don't show on servers whose config already has the old text. Consider a "config version" with automatic migration.
