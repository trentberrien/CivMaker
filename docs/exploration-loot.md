# Exploration bonus loot

All rewards below are **additional** to vanilla loot. They never change vanilla loot tables, weights, rolls, or generated item quantities. Container bonuses are inserted on the following tick, after vanilla fills the inventory. Any bonus overflow drops at the container. Farm Stations retain their existing independent 5% chances and now use the same container overflow protection.

Rates apply once per vanilla loot generation, not on every reopening. Existing generated inventories are untouched. Old unopened containers that still have their natural loot tables qualify. Trial Vault rates apply to each successful unlock; repeatable Trial Spawner payouts do not qualify. Trap dispensers and the spawn bonus chest do not receive bonuses.

## Settlement themes

Each currency item remains worth $2,000. Resource and gear rolls are independent. A successful resource roll selects one listed resource type uniformly, then a quantity uniformly from the inclusive range. A successful gear roll selects one listed item uniformly; its percentage is the chance for the entire gear pool.

Village house tables use their named village type. Plains houses in Meadows and profession chests use the local biome. Desert/Badlands variants share the desert theme; Savanna variants share Copper; all Taiga variants (including Snowy Taiga) share Totems; Snowy Plains, Ice Spikes, Snowy Slopes, Frozen Peaks, and Grove share Obsidian. Plains and Sunflower Plains share Iron.

| Village theme | Resource bonus | Additional gear bonus |
| --- | --- | --- |
| Desert | 15% for 1 Settlement Gunpowder | None |
| Plains | 15% for 1 Settlement Iron | None |
| Savanna | 15% for 1 Settlement Copper | None |
| Meadow | 15% for 1 Muster Amethyst | 1% for 1 Super Saddle |
| Taiga | 1% for 1 Super Totem | None |
| Snowy | 20% for 4–8 ordinary Obsidian | None |

## Structure containers

An asterisk marks generic structures whose rewards follow the local settlement biome when one applies. Their currency chances and quantity ranges remain as listed, but the resource type and gear pool follow that biome:

- Desert: Gunpowder; C4, Grenade, or Blast Rockets.
- Plains: Iron; Secure Vault Kit, Vault Door, or Defender Golem kit.
- Savanna: Copper; Lockpick, Battering Ram, Radar, or Raid Scaffolding.
- Meadow: Amethyst; Guard kit, Happy Harness, or Super Saddle.
- Taiga: replace the normal resource/gear rolls with one 1% Super Totem roll, rising to 2% for structures with a listed resource chance of at least 35%.
- Snowy: replace currency with 4–8 Obsidian (8–16 where the listed resource chance is at least 40%), keeping the listed resource chance; no separate gear roll.

For generic underground structures, a cave biome uses the surface biome above the container. Biomes outside these themes retain the default pools below. Specialized structures retain their own pools.

| Container | Resource bonus | Independent gear bonus |
| --- | --- | --- |
| Igloo basement | 50%: 8–16 Obsidian | None |
| Dungeon* | 25%: 1–2 Copper/Gunpowder | 3%: Lockpick |
| Mineshaft chest minecart* | 20%: 1–2 Copper/Iron | 2%: Lockpick |
| Abandoned Camp common chest/barrel* | 20%: 1 Copper/Amethyst | 2%: Super Saddle |
| Abandoned Camp secret chest* | 45%: 2–3 Amethyst | 5%: Radar/Super Saddle |
| Shipwreck supply/map | 15%: 1 Copper | 1%: Radar |
| Shipwreck treasure | 40%: 1–3 Copper/Iron | 3%: Radar |
| Ocean Ruins | 25%: 1–2 Copper/Amethyst | 2%: Radar |
| Buried Treasure | 75%: 2–4 of any settlement currency | 5%: Radar/Super Totem |
| Desert Pyramid | 35%: 1–3 Gunpowder | 3%: C4/Grenade |
| Jungle Temple | 40%: 1–3 Amethyst/Copper | 4%: Super Saddle |
| Ruined Portal* | 30%: 1–2 Amethyst/Gunpowder | 3%: Blast Rockets |
| Pillager Outpost* | 50%: 1–3 Gunpowder/Amethyst | 5%: Guard kit |
| Stronghold corridor/crossing/library* | 30%: 1–2 Iron/Amethyst | 3%: Radar/Lockpick |
| Nether Fortress | 40%: 1–3 Gunpowder/Iron | 4%: Grenade/Blast Rockets |
| Bastion ordinary chest | 35%: 1–3 Iron/Gunpowder | 3%: C4/Blast Rockets |
| Bastion treasure chest | 75%: 3–5 Iron/Gunpowder | 5%: C4/Grenade/Blast Rockets |
| Woodland Mansion loot chest | 50%: 2–4 Amethyst/Iron | 5%: Happy Harness/Super Totem |
| Ancient City regular chest | 30%: 2–4 Amethyst/Iron | 3%: Radar/Super Totem |
| Ancient City ice box | 20%: 4–8 Obsidian | None |
| End City treasure | 45%: 2–4 Amethyst | 4%: Happy Harness/Super Saddle |
| Trial Chamber supply/corridor/intersection/entrance containers and corridor pots | 15%: 1 Copper | 1%: Lockpick |
| Normal Trial Vault | 60%: 1–3 Copper | 5%: Radar/Lockpick |
| Ominous Trial Vault | 100%: 2–4 Copper/Iron | 8%: Radar/Lockpick |

Gear drops give one item, except Blast Rockets give 8 and Raid Scaffolding gives 64. A Guard kit drop gives one kit; shop purchases still give two. Shop rocket purchases still give 32.

Ruined Portals also roll independently for 4–8 ordinary Obsidian at 20%; Bastion treasure chests also roll for 8–16 at 25%. These are separate from the biome/resource and gear rolls.

## Archaeology

Bonuses spawn separately beside the vanilla artifact after completed brushing. The original table identity is saved on first brushing so partial excavation survives chunk reloads and restarts. Breaking or dropping suspicious blocks does not award bonuses. Suspicious blocks whose tables were already consumed before this update cannot reliably be identified and do not receive bonuses.

| Archaeology table | Bonus |
| --- | --- |
| Desert Well | 15%: 1 Settlement Gunpowder |
| Desert Pyramid | 10%: 1 Settlement Gunpowder |
| Warm/cold Ocean Ruins | 8%: 1 Settlement Copper |
| Trail Ruins common | 5%: 1 Copper/Amethyst, following local biome where applicable |
| Trail Ruins rare | 20%: 1–2 Amethyst, following local biome where applicable |
| Trail Ruins common in Taiga | No custom bonus |
| Trail Ruins rare in Taiga | 1%: 1 Super Totem |

Snowy Trail Ruins use 4–8 Obsidian at the corresponding common/rare chance. No archaeology gear roll is added.

Structures without vanilla loot containers or archaeology tables do not gain a new discovery/completion reward in this release.
