# CivMaker

CivMaker is a Paper plugin for Minecraft Java Edition 26.3 that adds faction progression, territorial conflict, and discoverable farms. Mine ore to fund a civilization, expand your capital, buy settlements for daily rewards, and defend them during scheduled raids. Players can join with the standard Java client.

## Major gameplay changes

- **Money comes from mining.** Eligible natural ores pay into a wallet. Banknotes let players carry and store money, and deaths put some of that wealth at risk.
- **Civilizations own territory and earn points.** Found a capital for $10,000, expand into neighboring chunks, and purchase natural villages for $50,000 to gain daily rewards and points.
- **Automation requires exploration.** Rare Farm Stations unlock unattended mob item drops and automatic crop harvesting in one chunk. Hostile sweep kills also require the killer to qualify for that station's faction benefit.
- **End access is faction progression.** Reach 500 points and pay an End Portal Keeper $1,000,000 to unlock End travel for your faction.
- **Raids create scheduled conflict.** Raid windows let outsiders breach eligible defenses, lockpick vaults, and capture settlements. Members can prepare guards, golems, and Raid Protection.
- **Travel, trade, and equipment support your faction.** Warp between faction portals, exchange items through a confirmed trade, and buy tools, defenses, and mount upgrades from the shop.

## Getting started

1. Mine eligible ore in Survival and check `/civ balance`.
2. Save $10,000, buy a Founding Beacon with `/civ create` or `/civ shop`, and place it to found your civilization.
3. Invite friends with `/civ invite <player>` and expand with `/civ claim mode`.
4. Explore for Farm Stations and save for settlements. Use `/civ points` to compare factions and `/civ raids` to check the next raid window.
5. Equip your faction, build up your settlements, and work toward unlocking the End.

Use `/civ help` for commands relevant to your civilization membership, role, active invites, game mode, and admin permissions. Tab completion follows the same rules. The sections below explain each system and its default prices and limits.

- [Economy](#mining-and-banknotes), [founding a civilization](#founding-and-expanding-villages), and [settlements](#settlements-and-points)
- [Farm Stations](#farm-stations), [Secure Vaults](#secure-vaults), and [player trading](#trading-items)
- [Warp portals](#warp-portals), [unlocking the End](#unlocking-the-end), and [combat deaths](#combat-deaths)
- [Raids and capture](#raid-windows) and the [equipment shop](#equipment-shop)
- [VillagerRework companion plugin](#villagerrework)
- [Server administration](#server-administration)

## Mining and banknotes

- Mine eligible ore in Survival with the appropriate tool to earn a payout per block. Check your wallet with `/civ balance`.
- CivMaker records ore locations as new Overworld and Nether chunks first load. Mining a recorded ore consumes its eligibility even with Silk Touch. Placed and piston-moved ores are ineligible. Fortune still affects normal drops.
- `/civ withdraw 10` exchanges $10,000 for ten stackable $1,000 banknotes. `/civ deposit` redeems all banknotes in the main inventory, and `/civ deposit 3` redeems three. Genuine notes carry a server item tag. Renaming ordinary paper cannot create a note.
- Carry banknotes yourself or deposit them in a Secure Vault inside your village. Ordinary storage, Ender Chests, and bundles reject banknote deposits. Hoppers and hopper minecarts cannot collect dropped banknotes. Banknotes on the ground can despawn like other dropped items. Banknotes already in ordinary storage can still be withdrawn.
- On death, you drop 10% of your wallet balance, rounded down to the nearest $1,000, plus the banknotes that drop with your inventory. If you keep your inventory, including with a Super Totem, the 10% applies to your wallet and carried banknotes combined. Large death payouts are condensed into high-value banknotes. Picking up one credits your wallet immediately, even with a full inventory. It stays on the ground if your wallet can't hold the full amount.
- The **CivMaker** sidebar shows your village membership, wallet, current raid status, and the top five villages by points. `/civ help` appears at the bottom. The vanilla client displays the sidebar on the right. CivMaker leaves an existing custom scoreboard or sidebar in place.

| Ore | Default payout per block |
| --- | ---: |
| Coal | $10 |
| Copper | $15 |
| Iron | $20 |
| Nether quartz | $25 |
| Nether gold | $35 |
| Redstone | $40 |
| Lapis lazuli | $60 |
| Gold | $100 |
| Diamond or emerald | $1,000 |
| Ancient debris | $10,000 |

Deepslate ores pay the same amount as their stone equivalents by default.

## Founding and expanding villages

1. Buy a **Founding Beacon** for **$10,000** from `/civ shop` or with `/civ create`. Place it in an unclaimed Overworld chunk outside a natural village, at Y 64 or higher by default, with clear sky above it. Select **Choose a name** in the setup menu, enter a civilization name, and select **Found civilization**. The beacon becomes your capital and can grant normal beacon effects once powered. Close the menu anytime and right-click the beacon to resume.
2. As an owner or elder, run `/civ claim mode` and walk into an unclaimed chunk that shares an edge with your capital territory. Sneak and right-click to buy the chunk you're standing in. The default price is $1,000, paid from your wallet. Run `/civ claim mode` again to leave buying mode, or use `/civ claim` for a single purchase. Ordinary members must be promoted to elder before buying plots.
3. Use `/civ claim undo` to release your most recently purchased expansion and refund its original price. Each purchase gives you one undo. The founding chunk stays claimed until the village disbands.
4. Owners and elders can use `/civ invite <online player>` to invite a player. They have ten minutes to run `/civ join`. Owners can use `/civ promote <player>` to make a faction member an elder and `/civ demote <player>` to return an elder to ordinary membership. Owners and elders can use `/civ kick <player>`: owners can remove members or elders, while elders can only remove ordinary members. The owner cannot be kicked. Promotion, demotion, and kicking also work for offline members using their cached name or UUID. Elder roles are saved across restarts and cleared when a player leaves or is kicked. Members can build and use containers in village territory. Members can use `/civ leave` to give up their membership. An owner can leave by disbanding their village. `/civ villages` shows public beacon coordinates, and `/civ info` describes the current claim or your own village.

Players without a civilization can buy multiple Founding Beacons. Each player can belong to one civilization at a time. A Founding Beacon unlocks its effects when it becomes a capital. Unused Founding Beacons stay locked, including when picked up and placed again. Leaving a civilization restores purchase eligibility. Ordinary beacons still work inside civilizations.

Plot protection covers blocks from **Y=61 through Y=200** in capital chunks and their expansions, and **Y=61 through Y=99** in settlements. Capital beacons and registered settlement bells and vaults are protected at any height.

Village owners can run `/civ disband` and then `/civ disband confirm` within 60 seconds. This releases the capital, expansions, and settlements and clears memberships and pending invites. A released Founding Beacon locks again and can be used to found a new civilization. Released capitals and expansions return to wilderness rules. Released natural villages regain unclaimed village protection. Disbanding gives no refunds.

In buying mode, the outline is blue for your village's land, green for an adjacent chunk you can afford, red for another village's land, and gray for unclaimed land you can't buy.

Use `/civ borders` to toggle a personal border display. While you're inside any village, blue particles mark its outer edges within 32 blocks horizontally. The setting is saved with your player data.

### Faction colors and banners

Each faction gets a saved color for its floating beacon label, its members' names in the Tab list, and its territory boss bar. The boss bar shows points and vault balance, using the closest Minecraft boss-bar color for custom shades.

The default faction banner uses the faction's color. To set a design, the owner holds a patterned or plain banner in their main hand and runs `/civ banner set`. Members can open **Faction Banner** in `/civ shop`, insert a banner or shield from their inventory, and pay **$100** to apply the design.

## Settlements and points

- A beacon capital starts with **100 points**, including its founding chunk. Each purchased capital chunk adds **1 point**, with an initial limit of **100 expansion chunks** per capital. An owner or elder in Survival can **sneak and right-click their capital beacon** to open a settlement-style upgrade menu. Select **Buy upgrade for $100,000** to permanently raise the civilization's limit to **200 expansion chunks**, paid from your wallet, or select **Cancel** to leave without paying. Opening the menu does not spend money. This upgrade can only be purchased once and survives restarts. Settlements and the founding chunk are separate from this limit. Undoing a purchase removes its point and frees an expansion slot. Use `/civ info` to see the current count and limit.
- Right-click the purchasing villager beside the meeting-point bell. Its nameplate shows the village type, such as **Meadow Settlement - Right-click to claim**, so Meadow villages can be distinguished from Plains villages. The purchase menu shows the price, protected chunks, and daily vault reward. An owner or elder in Survival can confirm the purchase for **$50,000** from their wallet. Purchases add **100 points** and place the Settlement Vault where the villager stood. Owners and elders can also preview or purchase at the bell with `/civ settlement` and `/civ settlement buy`.
- Unclaimed natural villages are protected across the chunks intersecting their generated pieces. Players can enter, open ordinary doors, ring the bell, and trade with villagers. The whole footprint must be unclaimed to purchase it, and settlement chunks cannot be used as expansion anchors.
- Blocks within a **16-block horizontal radius**, starting **three blocks below the bell** and extending to the world height limit, are protected from building and breaking before and after purchase, including during raids.
- The territory banner shows village points alongside wealth. Use `/civ points` to see the top villages by points.

### Settlement vaults

The nameplate below the bell shows the settlement type and its owning village. The vault has its own floating label. Owners of older settlements without a placed vault can look at their bell and run `/civ settlement vault` to place one in a clear spot nearby.

Once placed, the vault produces its reward every Minecraft day (24,000 world ticks, about 20 minutes at normal speed). Currency settlements produce five items per day, worth $2,000 each toward eligible shop purchases. Each daily batch occupies its own slot. Five slots hold five days of currency deposits, for 25 items worth $50,000 in total. Production follows the world's day-night clock, so sleeping or advancing world time can bring the next deposit sooner. An unloaded vault catches up when its chunk loads, up to five daily batches. Production for days when it is full is lost. Village members can open it, but only elders and owners can withdraw settlement currency such as Settlement Copper, Iron, Gunpowder, and Muster Amethyst. Ordinary members can still withdraw non-currency rewards such as Obsidian and Super Totems. During a raid, outsiders can use a lockpick to loot it. Hoppers and players cannot deposit items, and hoppers cannot withdraw them.

| Village type | Daily reward | Use |
| --- | --- | --- |
| Desert | 5 Settlement Gunpowder | Grenades, C4, and Blast Rockets |
| Plains | 5 Settlement Iron | Secure Vault Kits, Vault Doors, and Defender Golems |
| Savanna | 5 Settlement Copper | Lockpicks and Battering Rams |
| Taiga | 1 Super Totem | Keep your items on death and drop 10% of carried money |
| Snowy plains | 64 Obsidian | Ordinary building material |
| Meadow | 5 Muster Amethyst | Pillager Guards, Happy Harnesses, and Super Saddles |

Meadow settlements use the plains village structure and are identified by the bell's biome. Settlement currencies glint and work as shop payments. Muster Amethyst has a purple shard icon, and Settlement Iron has a raw iron icon.

Existing Taiga and Snowy vaults adopt these deposit types after upgrading and restarting. Items already stored in their vaults are preserved.

## VillagerRework

[VillagerRework](VillagerRework/README.md) is an optional companion plugin included in this repository for Paper 26.3 and Java 25. It loads its trade pack automatically in existing and new worlds.

- **Trades depend on location.** Villagers normally trade only in their home biome group. With CivMaker, Jungle villagers trade inside Savanna settlements and Swamp villagers inside Meadow settlements. Regional trade pools follow the settlement footprint, including natural villages before purchase.
- **Librarians and armorers use regional offers.** The plugin applies Minecraft's bundled Villager Trade Rebalance tables with custom Master librarian books: Efficiency V in Desert, Unbreaking III in Jungle and Savanna, Protection IV in Plains, Silk Touch in Snow, Mending in Swamp and Meadow, and Fortune III in Taiga. Savanna villagers can also roll Jungle offers, and Meadow villagers can roll Swamp offers.
- **Common librarian books are rebalanced.** Sweeping Edge is in the Plains pool (also shared by Meadow), Respiration is in Snow, and Curse of Vanishing is in Desert. Fire Protection and Thorns are in Taiga; Fire Aspect and Flame are in Desert. These moves affect librarian books; regional armorer enchantments stay the same. See the [full book pools](VillagerRework/README.md#common-librarian-book-pools).
- **Settlement ownership can be required.** Standalone trading is the default. Operators or players with `villagerrework.admin` can run `/villagerrework settlement true` to restrict trading to purchased settlements they personally own. Faction members and visitors cannot trade under this rule. Use `/villagerrework settlement false` to disable it or `/villagerrework settlement` to check it. Changes apply immediately and are saved in `plugins/VillagerRework/config.yml`.

The first eligible interaction rebuilds saved librarian, armorer, and cartographer offers while preserving profession, level, experience, and reputation. Refreshed offers have fresh stock and can replace custom trades. Wandering traders are outside the villager restrictions.

To build both plugins, run these commands from the repository root with JDK 25 and Maven:

```sh
mvn install
mvn -f VillagerRework/pom.xml package
```

Copy `target/civmaker-0.9.0.jar` and `VillagerRework/target/villager-rework-0.2.3.jar` into your server's `plugins` directory and restart. See the [VillagerRework guide](VillagerRework/README.md) for the full trade rules and configuration.

## Farm Stations

A **Farm Station** is a tagged lodestone with an enchanted glint. It has a **5% chance** to appear as bonus loot in a regular Ancient City chest, any of the four Bastion chest types, or an ominous Trial Chamber vault unlocked with an **Ominous Trial Key**.

### Placement and ownership

Place the station to activate its chunk. Each chunk can contain one station, and its benefit covers that chunk's full height. You can place a station in wilderness and own one without belonging to a faction. The player who places it owns it, and its faction identity follows that player's current membership.

The lodestone is protected against other players breaking it, explosions, pistons, and mob destruction. Its protection applies to the station block itself. The owner can mine it with a suitable pickaxe between raid windows to recover the tagged item. Recovering it ends the chunk's farming benefit. The station's name survives recovery and placement.

### Mob drops and sword sweeps

Outside a Farm Station chunk, mobs drop items when they took player-caused damage within the last **100 game ticks**, about **five seconds** at normal tick speed. A station allows normal mob item drops from unattended kills in its chunk. The check uses the chunk where the mob dies.

Secondary sword sweep kills have an additional rule for mobs capable of attacking players. This includes hostile mobs and neutral mobs such as calm Endermen, spiders, piglins, wolves, and iron golems.

| Kill | Item drops and XP |
| --- | --- |
| Player directly kills a mob | Normal rewards |
| Secondary sword sweep kills a hostile or neutral mob outside a station chunk | Both suppressed |
| Secondary sword sweep kills a hostile or neutral mob inside a station chunk | Normal rewards for members of the station owner's faction |
| Secondary sword sweep kills a hostile or neutral mob in an unaffiliated owner's station chunk | Normal rewards for the player who placed that station |
| Secondary sword sweep kills a passive animal such as a cow or sheep | Normal rewards |

The sweep rule applies to secondary hits from any sword. The mob directly targeted by the swing follows the direct-kill rule. The faction check uses actual membership. Other players' hostile sweep kills in a station chunk yield neither items nor XP.

### Crop harvesting

Automatic harvesting of supported crops produces items in a Farm Station chunk. Outside those chunks, automated breaks such as pistons, water harvesting, and unsupported crop collapses suppress the crop drops. The check uses the plant's chunk, so each growing chunk of a larger farm needs its own station. Collection systems can carry harvested items across chunk borders.

Hand harvesting works throughout the world. Breaking a lower bamboo or sugar cane segment by hand also allows the upper segments that fall as a result to drop their items.

Supported crops include bamboo, sugar cane, cactus, kelp, wheat, carrots, potatoes, beetroot, nether wart, cocoa, pumpkins, melons, sweet berries, glow berries, mushrooms, chorus plants, twisting vines, and weeping vines. Pumpkin and melon stems are included. Other renewable outputs, such as logs, eggs, dispenser-sheared wool, honey, bartering rewards, and furnace XP, use separate production paths outside these crop and mob-death rules.

### Naming and finding stations

As the owner, hold a supported crop item or mob drop in your **main hand** and right-click the station to set its name. The item stays in your hand. Crop items use the crop name, and mob drops use a corresponding mob name.

| Held item | Station name |
| --- | --- |
| Bamboo | Bamboo Farm |
| Sugar cane | Sugar Cane Farm |
| Bone | Skeleton Farm |
| Ender pearl | Enderman Farm |
| Gunpowder | Creeper Farm |
| Iron ingot | Iron Golem Farm |

A floating nametag shows the farm name above the owner's faction name in that faction's color. For an unaffiliated owner, it shows the player's name in gray when available. The Radar marks the chunk with an `F` in the faction's color, using the same map slot as a territory `#`. Hover over the tile to see the farm name and owner.

## Secure Vaults

Use a **Secure Vault Kit** on a chest, barrel, or shulker box inside your own claim. The entire container, including both halves of a double chest, must be inside your village. A floating nameplate identifies the vault, and its banknotes count toward the village balance. Withdrawals and theft reduce that balance. Previously unloaded vaults are counted when their chunks load.

Only elders and owners can take banknotes or settlement currency out of their civilization's Secure Vaults. Ordinary members can still deposit funds and use non-currency contents. This restriction also covers shift-clicks, hotbar swaps, double-click collection, and containers holding currency. Breaking a vault containing currency cannot bypass the rank requirement. Raid lockpicking keeps its existing loot rules.

## Trading items

Run `/trade <player>` in Survival. The other player has one minute to click the request in chat or run `/trade <your name>` to accept.

- Click an inventory stack to offer it on the left. Click your own offer to take it back. Left-click moves a stack and right-click moves one item. The other player's offer appears on the right.
- Click the red confirm button in the middle to turn it green. The trade completes once both players confirm and have room for what they will receive. Changing either offer clears both confirmations.
- Close the window to cancel and return offered items. If your inventory fills up, make room and run `/trade --recover` to collect the rest. If you die while trading, your offer follows the server's inventory-on-death rules.

## Warp portals

1. Build and light a Nether portal inside your faction's capital or purchased settlement.
2. Buy a **Warp Portal** kit for **$10,000** under Utility in `/civ shop`. Hold it in your main hand and right-click the top of a solid floor block within four blocks of the portal. Leave two clear blocks above the floor for the trader and arriving players. Each capital and each settlement can host one warp trader.
3. Right-click the trader and choose another destination in your faction. Both portals must be lit. Each successful warp starts a personal 60-second cooldown. Only members of the owning faction can use its traders.

Your mount, mobs you’re leading, and nearby tamed pets that are standing can travel with you. Leave room by the destination trader for larger mobs. If you share a mount with another rider, dismount before warping.

Faction owners can sneak and right-click their trader to recover the kit. If a settlement changes hands during a raid, its warp trader goes with it.

Warp traders stay visible and show their faction's color in their nametag. An Eye of Ender marks a destination where raiders were inside the settlement's capture ring in the last five minutes.

## Unlocking the End

Find the **End Portal Keeper** in a stronghold portal room. Once your faction reaches **500 points**, any member can right-click the keeper and confirm a **$1,000,000** payment from their own wallet. The keeper activates the portal, and the purchase permanently unlocks End travel for the whole faction.

Members can ask a keeper to activate another stronghold portal for free. Entry through a lit portal requires an unlocked faction. Players can leave the End through its exit portal as usual.

## Combat deaths

After being killed by another player, players respawn at their usual location and wait in place as spectators until the combat timeout ends. Player kills include player-fired projectiles and player-sourced TNT. Mob deaths, environmental deaths, and self-inflicted deaths do not trigger the timeout, even after recent combat damage. The timeout starts at death and lasts 60 seconds by default. Reconnecting doesn't reset either timer.

## Raid windows

Run `/civ raids` to see the current raid status and upcoming windows in UTC. Windows follow the server's saved schedule and can run while defenders are offline.

During a raid, outsiders can use lockpicks on eligible containers and Vault Doors. A successful pick on a Secure Vault or Settlement Vault reveals a loot menu with hidden slots. Left-click a slot to take its entire stack. You can loot up to five slots per session. Picked ordinary storage opens its usual inventory for the session.

### Settlement capture

A living Survival or Adventure player from an enemy village can capture during a raid within 16 blocks of the settlement bell, measured in three dimensions. The first enemy presence alerts the server, and the boss bar shows progress in the acting village's color. Multiple factions stall the bar. An empty point holds its progress, and unaffiliated players cannot capture or contest.

An attacker captures in 60 seconds. Defenders alone erase progress at twice the capture rate. A different attacking faction must erase the first attacker's progress at the normal rate before gaining its own. Progress clears when the raid closes or the server restarts.

During a raid, a particle ring in the owner's color marks the 16-block radius at bell height. A second ring in the claimant's color shrinks toward the bell as capture advances, holds when progress pauses, and expands when progress is erased.

Capture transfers the claims, points, vaults and contents, Vault Doors, and future production to the winner. Former defenders lose their posts. The server announces the capture and launches a harmless firework in the winner's color. The former owner can try to reclaim the settlement in the same window. Capital beacons are outside the capture system.

### Raid Protection

Buy a faction-bound Nether Star in `/civ shop`. A member of the faction that bought it can right-click with the star in their main hand in Survival during a raid. This consumes the star and shields the faction's claims from capture, lockpicking, and breaching for the rest of that window. The default price is $10,000. Each faction has separate rolling seven-day cooldowns for purchases and activations.

Use `/civ raidprotection` to see the current rules.

## Equipment shop

Run `/civ shop` in Survival to browse Raid Gear, Village Defense, Mount Upgrades, and Utility. Hover over an item to see its payment options. Left-click to pay from your wallet, normally **$10,000**, or right-click an eligible item to pay **5 of the matching settlement currency** from your inventory. Use raid tools from your main hand.

The Founding Beacon appears alone in the center of the top row before you join or create a faction. Once you belong to a faction, the Faction Banner station replaces it in the same slot.

| Item | Use |
| --- | --- |
| Pillager Guard | Right-click the top of a solid block inside your own village to post a pillager with a visible `guard` nametag. During active raids, it fires at eligible raiders it can see within four chunks (64 blocks), even across the village border. Rockets damage only targeted enemies and mobs they're riding. |
| Defender Golem | Buy a kit for $10,000 or 5 Settlement Iron under Village Defense, then right-click your faction's settlement bell with it in your main hand. Each bell can have one defender. Leave a clear 3-by-3 spot with six blocks of headroom near the bell. The golem is twice normal size, has 100 health (50 hearts), and stays within 16 blocks of the bell. It swings in melee and throws 1½-block-wide boulders at targets up to 24 blocks away. Both attacks deal normal iron golem hit damage. The golem is immune to arrows and to splash and lingering potions. A living golem in the ring contests enemy capture in either mode. Its boss health bar appears at the top of the screen within 32 blocks. |
| Lockpick | A glinting stick. Spend one to start a puzzle with a fixed 14-pane trail. Click the glowing yellow pane in order as completed panes turn green. Each correct click has a 5% break chance, giving one lockpick a 48.77% chance of finishing all 14 steps. Solving it opens a Vault Door or grants one session of storage access. |
| C4 | Right-click a block face to detonate at TNT strength. The blast can break unclaimed blocks at any time. Targeting a village claim requires an active raid against that village. The blast spares secure vaults and claim anchors, deals no direct damage to players or mobs, and cannot start a TNT chain. |
| Grenade | Right-click to launch primed TNT in the direction you're looking, including from a Happy Ghast. After a five-second fuse it uses C4 block damage rules and can damage enemy Defender Golems during the same raid. To damage a village claim, throw it during a raid and have it detonate inside the enemy claim before that raid window ends. |
| Blast Rockets | A stack of 64 fireworks with a flight duration of 2 and a large-ball burst. Fire one from a crossbow to damage entities in a TNT-sized blast. An unarmored player can be taken out in one close hit. |
| Battering Ram | A glinting blaze rod. Breaks both halves of a door in a claim open to raids, outside the protected settlement center. |
| Happy Harness | Equip this yellow harness on a Happy Ghast to give it five times its normal max health and double its flying speed while a player pilots it. |
| Super Saddle | Equipped camels gain triple max health and match the fastest horse's normal riding speed under a player's control. They keep the boost when stepping down blocks. Charging and dashing suspend the speed boost. Equipped horses gain the highest natural movement speed (0.3375), jump strength (1.0), and 96 max health (48 hearts), matching a normal camel wearing the saddle. Right-click it in the shop to pay 5 Muster Amethyst. |
| Secure Vault Kit | A glinting iron ingot. Reinforces a chest, barrel, or shulker box in your own village. Raiders see stained-glass placeholders and left-click to take an entire stack from each of up to five slots. Reinforced storage blocks hopper transfers while in a claim. |
| Vault Door | A glinting iron door that can be placed in your own village. Village members can open and close it by hand. A lockpick can open it during a raid, and a ram can break it. Visitors can operate ordinary doors in claims. |
| Super Totem | Carry it anywhere in your inventory. When you die, one Super Totem is consumed and you respawn with the rest of your items. Death and experience loss follow the usual rules, even if you hold it in either hand. |
| Radar | Hold this compass in either hand for a private chat map every second. The map covers your chunk and five chunks in each direction. North stays at the top, and the aqua arrow at the center points in the direction you're facing. Colored `#` tiles show each village's territory. Farm Station chunks use `F` in the owning faction's color, or gray for an unaffiliated owner. Yellow `B` markers show registered settlement bells. Red `X` markers show outsiders outside their own village, and gold `G` markers show loaded enemy guards, including those inside their village. Hover over a tile for its block range, any marked bell coordinates, and its Farm Station name and owner. |

Sneak-right-click one of your faction's settlement defenders to toggle between Always Hostile and Retaliate Only for the whole settlement. The golem and every guard posted in its claims share that setting, including newly deployed defenders. Settlements default to Retaliate Only. Guards in capital claims have individual settings and default to Always Hostile. In Retaliate Only mode, damage from an eligible raider makes the defender they hit hostile to the raider's entire faction for the raid. Unaffiliated raiders trigger retaliation individually. Changing the stance clears the settlement defenders' retaliation targets.

Offensive tools can affect another village's claim while raids are active. A storage session ends when you close it, leave reach, or raiding stops.
Leaving the lockpicking puzzle ends the attempt. Each correct click is accepted at least four server ticks after the previous one.

Loading fireworks into a Multishot crossbow consumes three rockets. If you have fewer than three, the crossbow stays unloaded and you keep your rockets.

## Server administration

### Build and install

1. Install JDK 25 and Maven, then run `mvn package` from this directory. This preserves older versioned JARs in `target/`.
2. Copy `target/civmaker-0.9.0.jar` into your Paper 26.3 server's `plugins` directory and start the server.
3. Edit `plugins/CivMaker/config.yml` by hand or with the [local config editor](website/README.md#edit-the-civmaker-config). Restart after editing.

### Optional Xaero map companion

The [CivMaker Companion](companion/README.md) adds village-colored plot overlays, ownership details, Farm Station chunk highlights, and capital/settlement/farm markers to Xaero's Minimap and World Map. It is a client-only Fabric mod for the Minecraft 26.3 CurseForge profile. Install companion 0.4.0 in the profile's `mods` folder and use CivMaker 0.9.0 or newer on the Paper server. Players without the companion can keep playing normally.

Your faction's claim overlays, capital/settlement waypoints, farms, and teammates remain visible on Xaero's maps without a Radar, as do farms you personally own. A genuine CivMaker Radar in the main inventory, hotbar, or offhand reveals other factions' claims/waypoints and enemy farms/players. The server checks possession every tick and withdraws foreign information when the item is lost. A renamed compass does not count. Holding the Radar in either hand is still required for its chat scan.

Use `/civmap` to toggle overlays, `/civmap farms` to toggle farm highlights and markers, `/civmap markers` to toggle landmarks, `/civmap status` to check the connection, and `/civmap refresh` to request an update. Farm chunks have gold borders and `F` waypoints showing the station name, owner, and owner's faction. The server sends public ownership and registered farm information for the current world; vault contents, balances, and unpurchased natural settlements are excluded. Set `companion-map.enabled: false` in the server config to disable map data.

### Configuration and upgrades

Default prices, mining payouts, claim settings, Raid Protection, and warp timers are configured in `plugins/CivMaker/config.yml`. Restart after editing the file.

Existing civilizations keep their claims and memberships when upgrading to 0.7.0. Their expansion limit stays at 100 until an elder or owner purchases the beacon upgrade. Elder roles and purchased upgrades are saved automatically; no manual data migration is needed.

Set `warp-portal.cooldown-seconds` and `warp-portal.combat-death-timeout-seconds` in `plugins/CivMaker/config.yml`. Set either value to `0` to disable that timer, then restart the server to apply the changes.

Chunks generated before CivMaker was installed are ineligible because their natural and placed ores cannot be reliably distinguished. Use a fresh world or explore new chunks on an existing server.

Old registered treasury chests convert when their chunks load and keep their contents. Vaults upgraded from the real-time production schedule start their Minecraft-day cycle when first loaded and keep their stored items.

Registered Farm Stations from earlier builds are indexed when their chunks load. Lodestones placed before station registration was added must be replaced using a tagged Farm Station.

Treat Creative access and world-editing tools as trusted admin access.

### Admin commands

- `/civ admin add Alex` grants the CivMaker admin role. Use `/civ admin remove Alex` to revoke it. The server console and operators can manage this role using a known player name or UUID. Role assignments survive restarts and grant access to CivMaker wallet, raid, and village disband commands.
- `/civ disbandvillage <village name>` previews disbanding any village, including villages whose owners are offline. Run `/civ disbandvillage --confirm` within 60 seconds to release its claims and clear memberships and pending invites. CivMaker admins and the server console can use this command.
- `/civ setbalance Alex 10000` sets an online player's wallet to $10,000. Enter a whole number without commas or a dollar sign. Use `0` to empty the wallet.
- `/civ locatemeadow` searches from your Overworld position for a village with a Meadow bell inside the world border and reports its coordinates.
- `/civ allegiance Ashford` lets an admin test as a member of Ashford. Use `/civ allegiance --outsider` to raid your own village without a second village, and `/civ allegiance` to restore your original membership. Testing allegiance resets when the plugin restarts.
- `/civ raids on` forces raids open, `/civ raids off` forces them closed, and `/civ raids auto` returns to scheduled windows. `/civ raids toggle` flips whether raids are active right now. The override is saved across restarts.
- `/civ raidmode settlements` opens raids in settlement claims and limits guard combat to settlement posts and targets. `/civ raidmode all` opens all claims, and `/civ raidmode none` closes all claims. `/civ raidmode toggle` cycles through settlements, all, and none. Use `/civ raids auto` to resume the schedule.

### Testing commands

- `/civ farmstation test` gives an in-game CivMaker admin one tagged, glinting Farm Station. Make room in your inventory before running it.
- To test production, leave an empty slot, look at the vault from within five blocks, and run `/civ testdeposit` as a CivMaker admin. The targeted vault's next stack is due in 1,200 world ticks (about one minute at normal speed). Its Minecraft-day cycle then continues from that test deposit.
- Operators can run `/trade test` to trade with an imaginary player who offers 10 dirt and stays confirmed. Confirming the test trade exchanges any items you offer for the dirt.

### Scheduling raid windows

A CivMaker admin or server operator can schedule one or more windows. Use ISO 8601 times with an explicit offset. For example, this window runs from Saturday at 1 p.m. through Sunday at 10 p.m. in New York on the dates shown.

```text
/civ schedule 2026-11-14T13:00:00-05:00 2026-11-15T22:00:00-05:00
```

`/civ cancelraid <start>` cancels a window by its start time. Windows are saved across restarts, and can run while defenders are offline.

#### Nightly raids

Admins can set a daily window with one command. This example opens raids from 8 to 10 p.m. New York time.

```text
/civ schedule nightly 20:00 22:00 America/New_York
/civ raids auto
```

The time zone defaults to the server's time zone. Windows can cross midnight. A one-off window replaces any nightly occurrence it overlaps. Use `/civ schedule nightly off` to remove the daily schedule and `/civ raids` to see upcoming times.

### Raid Protection controls

- `/civ raidprotection on` or `off` controls purchases and activations. Active shields expire with their window.
- `/civ raidprotection price 10000` sets the cash price.
- `/civ raidprotection cooldown 168` sets both cooldowns in hours.

### World Atlas

Run `node website/server.mjs` and open `http://127.0.0.1:8787/` to preview the atlas with a demo map. Load `plugins/CivMaker/public-v1.json` in the atlas to view your server's claims, rankings, and beacon and bell locations. The local atlas server can also follow the file as Paper updates it. See the setup guide for live feeds and terrain renders. [World Atlas setup](website/README.md)

The export includes `schemaVersion`, `generatedAt`, `worlds`, `villages`, `raidWindows`, and `raidMode`. Village records include IDs, owner IDs and cached names when available, hex colors, member and claim counts, public vault balances, points, settlements, beacon locations, and `timesRaided`. A looting session increments `timesRaided` on its first successful withdrawal from a vault. Each settlement lists its structure type, bell location, and fixed plots. Claims use chunk coordinates `x` and `z` with a world UUID. A chunk at `x` spans block X coordinates `16*x` through `16*x+15`, including for negative coordinates. Raid window timestamps are UTC.

Review the export before sharing it. It includes player identifiers, vault balances, and in-game coordinates. Keep `plugins/CivMaker/state.json` with the private server data.

### Server data and backups

CivMaker periodically saves village records and cached balances, including the last known totals in unloaded chunks, and flushes them on a clean stop. Paper saves each wallet with its player's inventory, and stores vault contents, ore provenance, and Farm Station positions, owners, and names with world data. The plugin also saves its Farm Station lookup index in `state.json`. Restore the plugin data and the world/player saves from the same backup when rolling back a server.
