# AquaticSMP: Commands

## What this plugin does

AquaticSMP turns a survival server into a a pirate world. In one plugin:

- **Pirate crews**: Captains, First Mates, Navigators and Quartermasters, with a shared treasury, a crew home and a flag. Crews can form alliances, declare rivalries and go to war.
- **Doubloons**: the server currency. Shop and job plugins can use it too, through Vault.
- **Bounties**: put a price on a pirate's head. Infamous killers get wanted posters and titles from *Rookie* up to *Emperor*.
- **Islands**: generate tropical, jungle, volcanic, snowy, desert, ancient and capital islands with one command. Crews plant their flag on an island to claim it and defend it. Enemy crews take it over by holding the flag.
- **Treasure**: buried chests with loot tiers, and treasure maps with a red X.
- **Pirate gear**: Abyss Fruits with powers (and the sea's curse), Abyssal Iron weapons, legendary swords, flintlocks, cannons, the Tide Compass, rum and more.
- **The Conqueror of the Sea tournament**: registration, qualifiers, an elimination bracket and a best-of-3 finale. The winning captain is crowned Conqueror of the Sea and enters the Hall of Kings.
- **Server events**: Treasure Hunt, Pirate War, Bounty Hunt, Lost Island, King's Challenge, Grand Raid and Naval Battle.

Every feature is a **module** you can turn on or off. All numbers, costs and messages are in the config files.

## How to read this

- In-game, every help page (`/crew help`, `/island help`, ...) can be hovered for details and clicked to fill in the command.

- `<value>` is required, `[value]` is optional, and `a|b` means pick one.
- **Player** permissions are given to everyone by default. Remove one with LuckPerms to lock that feature.
- **Admin** permissions are given to ops only. `aquatic.admin` grants every admin command.
- Each command works only while its module is on (see `/aquatic modules`).
- Every command also has a long form, `/aquatic:<command>`, which keeps working when another plugin uses the same name. See [EssentialsX conflict](#essentialsx-conflict).

---

## Server management (core)

| Command | What it does | Permission |
| --- | --- | --- |
| `/aquatic` or `/aquatic about` | Plugin version and credits (developed by tombomeke). Admins also see the admin help | Everyone |
| `/aquatic commands` | Every command in the plugin, clickable (aliases `cmds`, `all`) | Everyone |
| `/aquatic modules` | List all modules and whether they are on | `aquatic.admin` |
| `/aquatic enable <module>` | Turn a module on (saved in `config.yml`) | `aquatic.admin` |
| `/aquatic disable <module>` | Turn a module off; modules that depend on it go off too | `aquatic.admin` |
| `/aquatic reload [module]` | Reload configs for all modules or just one, with no restart | `aquatic.admin` |
| `/aquatic resetconfig <module> confirm` | Replace a module's config with the newest defaults. The old file is kept as a `.bak` | `aquatic.admin` |

Aliases: `/aq`, `/aquaticsmp`. Module names: `economy`, `crews`, `bounty`, `combat`, `islands`, `treasure`, `items`, `tournament`, `events`, `guide`, `sidebar`, `shop`.

---

## Economy: Doubloons (`economy`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/doubloons [player]` | Show your Doubloons, or another player's | Player. Checking others needs `aquatic.economy.balance.others` |
| `/pay <player> <amount>` | Send Doubloons to another pirate | Player: `aquatic.economy.pay` |
| `/baltop [page]` | The richest pirates | Player |
| `/doubloons give <player> <amount>` | Add Doubloons (works for offline players) | Admin: `aquatic.economy.admin` |
| `/doubloons take <player> <amount>` | Remove Doubloons | Admin: `aquatic.economy.admin` |
| `/doubloons set <player> <amount>` | Set an exact balance | Admin: `aquatic.economy.admin` |

Aliases:
- `/doubloons`: `/balance`, `/bal`, `/money`
- `/baltop`: `/doubloonstop`, `/richest`

Amounts accept `k`, `m` and `b`, e.g. `/pay Zoro 2.5k`.

---

## Crews (`crews`)

`/crew` (alias `/c`)

**Membership**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew create <name> [tag]` | Found a new pirate crew (may cost Doubloons) | Anyone without a crew |
| `/crew invite <player>` | Invite a player (the invite expires after a while) | Officers |
| `/crew accept [crew]` | Accept an invite | Invited player |
| `/crew deny [crew]` | Decline an invite | Invited player |
| `/crew leave` | Leave your crew. A captain must `transfer` first, unless they are the last member | Members |
| `/crew kick <player>` | Throw a member overboard | Officers |
| `/crew disband [confirm]` | Disband the crew. The bank is refunded to the captain | Captain |

**Ranks**

Ranks are Captain, First Mate, Navigator, Quartermaster and Member. `crews.yml` sets limits per rank and what each rank may do.

| Command | What it does | Who |
| --- | --- | --- |
| `/crew promote <player>` | One rank up (Member → Navigator → First Mate) | Captain |
| `/crew demote <player>` | One rank down | Captain |
| `/crew setrank <player> <rank>` | Give a specific rank, e.g. Quartermaster | Captain |
| `/crew transfer <player>` | Hand the captaincy to someone else | Captain |

**Information**

| Command | What it does |
| --- | --- |
| `/crew info [crew\|player]` | Members, ranks, reputation, relations and war score |
| `/crew list [page]` | All crews |
| `/crew top [page]` | Most famous crews by reputation |

**Identity and base**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew color <color>` | Crew colour for the name and chat tag | Officers |
| `/crew tag <tag>` | Short tag shown in chat and the tab list | Officers |
| `/crew flag` | Show your flag. With a banner in your hand: make it the crew flag | Members / officers |
| `/crew flag set` | Use the banner in your hand as the crew flag. Design any banner you like in a loom | Officers |
| `/crew flag color <dye>` | New base colour, same design (`red`, `black`, `light_blue`, ...) | Officers |
| `/crew flag preset <name>` | Use a ready-made design | Officers |
| `/crew flag presets` | List the designs: `crew_colors`, `jolly_roger`, `crossbones`, `blood_skull`, `sea_kraken`, `sun_pirates`, `navy` | Everyone |
| `/crew flag get` | Get a copy of the flag banner | Members |
| `/crew flag reset` | Back to the default flag in the crew colour | Officers |
| `/crew sethome` | Set the crew home where you stand | Officers |
| `/crew home` | Teleport home after a warmup. Moving or taking damage cancels it; there is a cooldown | Members (`aquatic.crews.home`) |

**Chat, friendly fire and bank**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew chat` | Toggle whether your normal messages go to crew chat | Members |
| `/cc [message]` | Send one crew chat message, or toggle crew chat (alias `/crewchat`) | Members |
| `/crew friendlyfire` | Toggle whether crewmates can hurt each other (alias `ff`) | Captain |
| `/crew bank balance` | Show the crew treasury | Members |
| `/crew bank deposit <amount>` | Put Doubloons in the treasury | Members |
| `/crew bank withdraw <amount>` | Take Doubloons out | First Mate and Quartermaster by default |

**Diplomacy**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew ally <crew>` | Propose an alliance, or accept one (both sides must agree) | Officers |
| `/crew rival <crew>` | Declare a rival | Officers |
| `/crew neutral <crew>` | End an alliance or rivalry | Officers |
| `/crew war <crew>` | Declare war. Announced server-wide, can cost Doubloons from the bank, and opens up raiding and conquest | Officers |
| `/crew peace <crew>` | Offer or accept peace. Both sides must agree, after a minimum war duration | Officers |

**Admin** (`aquatic.crews.admin`)

| Command | What it does |
| --- | --- |
| `/crew admin disband <crew>` | Force-disband a crew |
| `/crew admin setcaptain <crew> <player>` | Change the captain |
| `/crew admin setrep <crew> <amount>` | Set reputation |
| `/crew admin join <crew> [player]` | Force-add yourself or a player to a crew |
| `/crew admin kick <player>` | Force-remove a player from their crew |

---

## Bounties (`bounty`, needs `economy`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/bounty set <player> <amount>` | Put a price on a pirate's head. You pay it, plus a tax. Aliases: `place`, `add` | Player: `aquatic.bounty.set` |
| `/bounty info [player]` | A pirate's bounty and title (Rookie, Rising Tide, Warlord, Emperor, ...) | Player |
| `/bounty top [page]` | Most wanted pirates | Player |
| `/bounty list [page]` | Every active bounty, online pirates first | Player |
| `/wanted [player]` | Get a "WANTED: DEAD OR ALIVE" poster item | Player: `aquatic.bounty.wanted` |
| `/bounty admin add <player> <amount>` | Raise a bounty for free | Admin: `aquatic.bounty.admin` |
| `/bounty admin remove <player> <amount>` | Lower a bounty | Admin |
| `/bounty admin clear <player>` | Remove a bounty | Admin |

Alias: `/bounties`.

Killing a pirate with a bounty pays you the full amount. Crewmates and allies of the victim cannot claim it. PvP kills also raise the killer's own bounty, and this grows on a killstreak.

---

## Islands (`islands`)

`/island` (aliases `/is`, `/islands`)

**Player commands**

| Command | What it does | Permission |
| --- | --- | --- |
| `/island info [island]` | Type, size, status, owner and shield of an island | `aquatic.islands.info` |
| `/island here` | Which island you are standing on | `aquatic.islands.info` |
| `/island list [page]` | All islands with their coordinates and distance. Admins get a clickable **[TP]** button per island | `aquatic.islands.list` |
| `/island map` | Nearest islands with direction and distance (alias `near`) | `aquatic.islands.map` |
| `/island discovered [player]` | Islands you have discovered | `aquatic.islands.discovered` |
| `/island top` | Crews ranked by islands owned | `aquatic.islands.top` |
| `/island claim` | Plant your crew flag on the unclaimed island you stand on (officers, may cost Doubloons) | `aquatic.islands.claim` |
| `/island unclaim [confirm]` | Abandon the island you stand on | `aquatic.islands.claim` |
| `/island flag` | Move or re-place your crew flag to where you stand | `aquatic.islands.flag` |
| `/island manage [island]` | Your island's stats and upgrade menu | `aquatic.islands.manage` |
| `/island upgrade <treasury\|fortress\|watchtower\|harbor\|barracks\|wellspring>` | Buy the next upgrade level for the island you stand on (Captain/First Mate, paid from the crew bank) | `aquatic.islands.manage` |

**Admin commands** (`aquatic.islands.admin`)

Run `/island admin` for this list in-game. The short form without `admin` (e.g. `/island spawn`) also works, but only `/island admin` shows these in help and tab completion.

| Command | What it does |
| --- | --- |
| `/island admin spawn <type> <radius> [name]` | Generate an island at your position. Types: `tropical`, `plains`, `jungle`, `volcanic`, `snowy`, `desert`, `ancient`, `capital` |
| `/island admin spawnrandom <type\|random> <count> [minRadius] [maxRadius]` | Scatter many islands over the ocean area, auto-named and spaced apart |
| `/island admin area <radius> [spacing]` | Set the ocean area for random islands and treasure, centred on you |
| `/island admin register <name> <type> <radius>` | Turn terrain you already built into an island (no generation) |
| `/island admin delete <island> [restore]` | Remove an island. `restore` turns it back into ocean |
| `/island admin regen <island> [newseed]` | Wipe and regenerate the terrain |
| `/island admin cancel [island]` | Stop island generation, including a running `spawnrandom`. Islands that were half built stay registered; remove them with `/island admin delete <island> restore` |
| `/island admin setspawn` | Make the island you stand on the neutral spawn island |
| `/island admin setstatus <island> <unclaimed\|neutral>` | Change status. Neutral = safe zone: no PvP, building, claiming or explosions |
| `/island admin setowner <island> <crew\|none>` | Give an island to a crew, or free it |
| `/island admin rename <island> <new name>` | Rename an island |
| `/island admin setradius <island> <radius>` | Change the protected radius |
| `/island admin shield <island> <minutes>` | Protect an island from conquest for a while |
| `/island admin tp <island>` | Teleport to an island |

Bypass all island protection: `aquatic.islands.bypass`.

**Island upgrades:** Treasury (more income), Fortress (slower capture), Watchtower (alerts and glowing intruders), Harbor (faster swimming, water breathing), Barracks (crew respawn point) and Wellspring (healing). In `/island manage` you can also limit building to officers. They're bought from the crew bank. After a conquest, every upgrade drops a level.

**Conquest:** stand near an enemy island's flag with your crew. The capture bar fills while no defender is close and drains otherwise. When it is full, the island is yours.

---

## Treasure (`treasure`)

`/treasure` (alias `/tr`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/treasure info` | Rumours about how much treasure is buried and where | Player: `aquatic.treasure.info` |
| `/treasure spawn <tier> [here\|random]` | Bury a treasure where you stand or at a random spot. Tiers: `common`, `uncommon`, `rare`, `epic`, `legendary` | Admin: `aquatic.treasure.admin` |
| `/treasure map <tier> [player]` | Bury a treasure and give a treasure map to it | Admin |
| `/treasure list` | All active treasures, numbered | Admin |
| `/treasure tp <number>` | Teleport to a treasure from the list | Admin |
| `/treasure remove <number\|all>` | Remove treasures, chest included | Admin |
| `/treasure give-loot <tier>` | Preview a loot roll (the items go to you) | Admin |

Right-click a treasure map to get a "warmer / colder" hint.

---

## Items and Abyss Fruits (`items`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/fruit` | Your Abyss Fruit power, its ability and cooldown (alias `/devilfruit`) | Player: `aquatic.items.fruit` |
| `/aitem give <player> <id> [amount]` | Give a custom item | Admin: `aquatic.items.admin` |
| `/aitem list` | All custom item ids | Admin |
| `/aitem fruit set <player> <fruit\|none>` | Give or remove a Abyss Fruit power directly | Admin |
| `/aitem fruit info [player]` | Show a player's power | Admin |

Aliases: `/aquaticitem`, `/pirateitem`.

Default item ids:
- **Abyss Fruits:** `gomu_gomu`, `mera_mera`, `hie_hie`, `goro_goro`, `suke_suke`, `ope_ope`, `yami_yami`, `ushi_ushi_bison`
- **Weapons:** `sea_prism_blade`, `seastone_cuffs`, `wado_ichimonji`, `shusui`, `yoru`, `flintlock`, `hand_cannon`, `cannonball`
- **Tools and other:** `log_pose`, `den_den_mushi`, `rum`, `pirate_king_crown`
- **Special:** `devil_fruit_random` gives a random Abyss Fruit

**Using a Abyss Fruit power:** sneak + **F** (swap hands), or sneak + right-click while looking at a block. You cannot use powers while in water.

---

## Conqueror of the Sea tournament (`tournament`, needs `crews`)

`/tournament` (aliases `/tourney`, `/pkt`)

**Player commands**

| Command | What it does | Permission |
| --- | --- | --- |
| `/tournament register` | Register your crew (officers) | `aquatic.tournament.register` |
| `/tournament unregister` | Withdraw your crew | `aquatic.tournament.register` |
| `/tournament roster [add\|remove <member>]` | Show or choose your crew's fighters | `aquatic.tournament.register` |
| `/tournament info` | Current stage and the reigning Conqueror of the Sea | `aquatic.tournament.view` |
| `/tournament standings` | Qualifier points | `aquatic.tournament.view` |
| `/tournament bracket` | The elimination bracket | `aquatic.tournament.view` |
| `/hallofkings` | Every past Conqueror of the Sea with date, crew and season (alias `/kings`) | `aquatic.tournament.view` |

**Staff commands** (`aquatic.tournament.admin`)

| Command | What it does |
| --- | --- |
| `/tournament open [minutes]` | Open registration. 0 = open until you run `start` |
| `/tournament start` | Move to the next stage: registration → qualifiers → bracket |
| `/tournament next` | Start the next bracket match |
| `/tournament status` | Detailed state of the tournament |
| `/tournament forcewin <crew>` | Award the current or next match to a crew |
| `/tournament cancel` | Cancel the tournament |
| `/tournament arena create <name>` | Create an arena |
| `/tournament arena setspawn <name> <1\|2>` | Set team 1 or team 2 spawn at your position |
| `/tournament arena setspectator <name>` | Set where eliminated players watch |
| `/tournament arena delete <name>` | Delete an arena |
| `/tournament arena list` | List arenas |
| `/tournament setlobby` | Where players return after a match |
| `/tournament admin setking <player\|none>` | Set or clear the Conqueror of the Sea by hand |

Nobody loses items in a match. Position, health and inventory are restored afterwards.

---

## Combat (`combat`)

These rules keep PvP fair. Tournament matches use their own rules instead.

- **Combat tag:** when you hit a player or get hit, both of you are *in combat* for 15 seconds. A red bar shows at the top of the screen.
  - You cannot use escape commands such as `/crew home`, `/spawn`, `/home`, `/tpa` or `/warp`, or teleport by command.
  - Logging out in combat kills you: your items drop and your attacker gets the kill and any bounty.
- **Respawn protection:** after respawning you are immune to other players for 10 seconds, so nobody can camp spawn. Attacking someone ends it.
- **New-player protection:** for your first 30 minutes of playtime you can't attack players and they can't attack you. Use `/pvp on` to end it early.

| Command | What it does | Permission |
| --- | --- | --- |
| `/pvp` | Your protection and combat status (alias `/combat`) | Everyone |
| `/pvp on [confirm]` | Give up new-player protection early | Player: `aquatic.combat.optout` |
| `/pvp status [player]` | Status of yourself, or another player (admins) | Everyone |
| `/pvp untag <player>` | Remove a player's combat tag | Admin: `aquatic.combat.admin` |
| `/pvp protect <player>` | Give back new-player protection, if they are still under the playtime limit | Admin: `aquatic.combat.admin` |

Staff with `aquatic.combat.bypass` never get tagged. Everything is in `combat.yml`, including the list of blocked commands.

---

## Harbor Market (`shop`, needs `economy`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/shop [category]` | Open the market (alias `/market`). Left-click buy, shift-left buy a stack, right-click sell, shift-right sell all | Everyone |
| `/shop sell` | Sell everything like the item in your hand | Player: `aquatic.shop.sell` |
| `/shop admin add <category> <buy> [sell] [amount]` | Add the item in your hand to a category | Admin: `aquatic.shop.admin` |
| `/shop admin list <category>` | List a category's items with their numbers | Admin |
| `/shop admin price <category> <number> <buy> <sell>` | Change a price (0 = not bought / not sold) | Admin |
| `/shop admin remove <category> <number>` | Remove an item | Admin |
| `/shop admin category <create\|delete> <id> [icon] [slot]` | Add or remove a category | Admin |

---

## Sidebar (`sidebar`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/sidebar [on\|off]` | Show or hide the stats sidebar (alias `/sb`) | Everyone |

All lines are configurable in `sidebar.yml`.

---

## Guide and intro (`guide`)

New players get a short intro on their first join: titles and chat tips about crews, islands and flags, treasure digging, bounties, Abyss Fruits and the Conqueror of the Sea. When it ends, the guide book opens.

| Command | What it does | Permission |
| --- | --- | --- |
| `/guide` | Open the pirate's guide book (aliases `/tutorial`, `/pirateguide`) | Everyone |
| `/guide <chapter>` | Open one chapter: `welcome`, `crews`, `islands`, `treasure`, `bounties`, `fruits`, `pirate-king` | Everyone |
| `/guide chapters` | List the chapters | Everyone |
| `/guide intro` | Watch the intro again | Player: `aquatic.guide.use` |
| `/guide skip` | Stop the intro | Everyone |
| `/guide tips [on\|off]` | Turn the tips in chat on or off | Everyone |
| `/guide kit <player>` | Give a player the starter kit | Admin: `aquatic.guide.admin` |
| `/guide play <player>` | Play the intro for a player | Admin: `aquatic.guide.admin` |
| `/guide reset <player\|all>` | Show the intro again on their next join | Admin: `aquatic.guide.admin` |

Brand-new players also get a **starter kit**: a boat, food, a stone shovel and sword, a Tide Compass, 100 Doubloons, and a **treasure map to a real buried treasure**, so treasure hunting is the first thing they learn.

New players (under 5 hours of playtime) also get a short **tip** in chat every 10 minutes.

All texts, timing, kit items and tips are in `guide.yml`. For testing, set `intro.mode: every-join`.

---

## Server events (`events`)

`/seaevent` (aliases `/event`, `/sevent`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/seaevent info` | Running events and time left | Player: `aquatic.events.info` |
| `/seaevent join [type]` | Join a running event (teleports you there if configured) | Player: `aquatic.events.join` |
| `/seaevent start <type> [args]` | Start an event | Admin: `aquatic.events.admin` |
| `/seaevent stop [type] [finish]` | Stop events. `finish` = end with results and rewards | Admin |
| `/seaevent list` | All event types | Admin |
| `/seaevent schedule <on\|off>` | Toggle automatic random events | Admin |
| `/seaevent setlocation <event> [name] [remove]` | Save your position as a location for an event | Admin |

Event types:

| Type | What happens |
| --- | --- |
| `treasure_hunt` | A legendary treasure is buried, with hints that narrow down over time. First to dig it up wins |
| `pirate_war <crewA> <crewB>` | An official timed war between two crews with a kill scoreboard. Without names, two rival or warring crews are picked |
| `bounty_hunt` | Extra bounties go on the most wanted pirates. The hunter who claims the most wins. Unclaimed event bounties are removed afterwards |
| `lost_island` | A new island rises from the sea, full of high-tier treasure, and sinks again when the event ends |
| `kings_challenge` | King of the hill on the capital island, or on a location you set. The crew that holds it longest wins |
| `grand_raid` | Waves of Tidewatch soldiers attack, ending with a High Warden boss. Rewards are split by damage dealt |
| `naval_battle` | Kills at sea or in boats score points for your crew. Cannons can be handed out at the start |

---

## EssentialsX conflict

EssentialsX and AquaticSMP both have economy commands. Command names used by both plugins:

| Shared name | AquaticSMP | EssentialsX |
| --- | --- | --- |
| `/balance`, `/bal`, `/money` | Doubloons balance | Essentials balance |
| `/pay` | Pays Doubloons | Pays Essentials money |
| `/baltop` | Doubloons ranking | Essentials ranking |

When two plugins use the same name, whichever registers first gets the short name. That depends on plugin load order, so players could end up using a different currency from one restart to the next. Both versions are always reachable through the long forms: `/aquatic:pay` and `/essentials:pay`.

**Recommended: keep AquaticSMP's Doubloons and turn off EssentialsX's economy commands.**

AquaticSMP's own features (bounties, crew bank, claim costs, war costs, rewards) always use Doubloons, so this is the only setup where everything uses one currency.

1. In `plugins/Essentials/config.yml`, add the economy commands to `disabled-commands`:
   ```yaml
   disabled-commands:
     - balance
     - balancetop
     - pay
     - eco
   ```
2. Install **Vault** and leave `vault.enabled: true` in `modules/economy.yml`. AquaticSMP registers Doubloons with Vault at the highest priority. Every Vault-based plugin, including EssentialsX's `/sell` and `/worth` and shop or job plugins, then pays in Doubloons.
3. Restart the server, not just reload, so the short names are re-registered.
4. Check: `/bal` should answer with the AquaticSMP prefix and "Doubloons".

The rest of EssentialsX (homes, warps, `/tpa`, kits, ...) keeps working normally.

**Not recommended: turning off the `economy` module instead.**

- The `bounty` module needs `economy` and turns off with it.
- Crew bank, claim and war costs, Doubloon treasure and event money rewards stop working, because they don't read EssentialsX balances.

Only do this if you don't want an economy in AquaticSMP at all.

**Other overlaps:**
- **Tab list:** AquaticSMP's crew tags in the tab list can fight with the **TAB** plugin. If you use TAB, set `tablist.enabled: false` in `crews.yml` and use the `%aquatic_crew_tag%` placeholder (needs PlaceholderAPI) in TAB instead.
- **Chat:** with an EssentialsX Chat format plugin, both may try to format the message. Either set `chat.prefix-enabled: false` in `crews.yml` or put `%aquatic_crew_tag%` in your chat plugin's format.
