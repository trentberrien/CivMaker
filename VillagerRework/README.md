# VillagerRework

VillagerRework is an optional plugin for Paper 26.3 and Java 25. Put `target/villager-rework-0.2.2.jar` in the server's `plugins` folder and restart. The plugin automatically loads the trade pack on existing and new worlds.

## Home biomes and trades

A villager can trade when its variant matches the biome group at its feet. The plugin uses Minecraft's seven variant groups. For example, a desert villager can trade in both desert and badlands, a swamp villager in both swamp and mangrove swamp, and a plains villager in plains and other Overworld biomes that default to the plains variant. Villager trading is blocked in the Nether and End. Every profession can still work in every biome group.

The plugin applies the librarian and armorer tables from Mojang's Villager Trade Rebalance as bundled in Paper 26.3. Librarians sell books from their biome's pool, and each Master has one guaranteed book. Armorers have regional offers such as jungle and swamp chainmail, savanna cursed diamond armor, and taiga armor swaps. The game already includes the regional cartographer maps and banners and the wandering trader changes.

| Villager group | Guaranteed Master librarian book |
| --- | --- |
| Desert | Efficiency V |
| Jungle | Unbreaking III |
| Plains | Protection IV |
| Savanna | Unbreaking III |
| Snow | Silk Touch |
| Swamp | Mending |
| Taiga | Fortune III |

The first eligible interaction rebuilds saved offers for librarians, armorers, and cartographers. Villagers keep their profession, level, experience, and reputation. The rebuilt offers have fresh stock and can differ from saved or custom offers. The refreshed offers are saved with the villager.

## Savanna and Meadow trade pools

Ordinary Savanna villagers can roll Jungle offers alongside their local offers. Ordinary Meadow villagers can roll Swamp offers alongside their local offers. Master librarians guarantee Unbreaking III in Savanna and Mending in Meadow. Protection IV belongs to Plains, and Sharpness is a random Taiga librarian offer.

With CivMaker 0.2.10 or newer, regional trades follow the settlement footprint, including natural villages before purchase. The bell distinguishes Meadow from Plains. The optional ownership rule still controls who can trade. Standalone use follows the local biome. Existing offers refresh on the next eligible interaction and when villagers move between trade regions.

[Mojang's 23w31a announcement](https://www.minecraft.net/en-us/article/minecraft-snapshot-23w31a) · [Java 1.20.2 experiment](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-2) · [Java 1.21.5 cartographer and wandering trader changes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-5)

## Optional CivMaker settlement rule

Standalone use is the default. Operators and players with `villagerrework.admin` can manage the rule in game.

- `/villagerrework settlement` shows the current setting.
- `/villagerrework settlement true` requires the player to own the purchased settlement where the villager is standing. This requires CivMaker 0.2.2 or later.
- `/villagerrework settlement false` turns off the ownership requirement.

Changes take effect immediately and are saved in `plugins/VillagerRework/config.yml`. To set the rule before startup, use the same setting in that file.

```yaml
require-owned-settlement: true
```

The villager's current position determines the settlement. The owner can trade there when the biome rule also passes. Members and visitors are blocked, as are trades on capital-only or unclaimed plots. If the option is on and CivMaker's ownership check is unavailable, villager trading is blocked and the server logs an error. Wandering traders are outside the villager restrictions.

## Build from source

With Java 25 and Maven, run these commands from the [CivMaker repository root](../README.md):

```sh
mvn clean install
mvn -f VillagerRework/pom.xml clean package
```

The CivMaker dependency provides the settlement API at build time. Install `VillagerRework/target/villager-rework-0.2.2.jar` in your server's `plugins` folder alongside CivMaker, then restart. VillagerRework can also run without CivMaker when the settlement ownership requirement is off.
