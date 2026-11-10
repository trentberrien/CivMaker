package dev.civmaker;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;

final class FarmProduce {
    enum Crop {
        BAMBOO(BlockFace.UP), SUGAR_CANE(BlockFace.UP), CACTUS(BlockFace.UP), KELP(BlockFace.UP),
        TWISTING_VINES(BlockFace.UP), WEEPING_VINES(BlockFace.DOWN), GLOW_BERRIES(BlockFace.DOWN),
        WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA, PUMPKIN, MELON, SWEET_BERRIES,
        BROWN_MUSHROOM, RED_MUSHROOM, CHORUS(BlockFace.UP);

        final BlockFace growthDirection;

        Crop() {
            this(null);
        }

        Crop(BlockFace growthDirection) {
            this.growthDirection = growthDirection;
        }
    }

    private FarmProduce() {}

    static Crop crop(Material block) {
        return switch (block) {
            case BAMBOO, BAMBOO_SAPLING -> Crop.BAMBOO;
            case SUGAR_CANE -> Crop.SUGAR_CANE;
            case CACTUS -> Crop.CACTUS;
            case KELP, KELP_PLANT -> Crop.KELP;
            case TWISTING_VINES, TWISTING_VINES_PLANT -> Crop.TWISTING_VINES;
            case WEEPING_VINES, WEEPING_VINES_PLANT -> Crop.WEEPING_VINES;
            case CAVE_VINES, CAVE_VINES_PLANT -> Crop.GLOW_BERRIES;
            case WHEAT -> Crop.WHEAT;
            case CARROTS -> Crop.CARROTS;
            case POTATOES -> Crop.POTATOES;
            case BEETROOTS -> Crop.BEETROOTS;
            case NETHER_WART -> Crop.NETHER_WART;
            case COCOA -> Crop.COCOA;
            case PUMPKIN, PUMPKIN_STEM, ATTACHED_PUMPKIN_STEM -> Crop.PUMPKIN;
            case MELON, MELON_STEM, ATTACHED_MELON_STEM -> Crop.MELON;
            case SWEET_BERRY_BUSH -> Crop.SWEET_BERRIES;
            case BROWN_MUSHROOM, BROWN_MUSHROOM_BLOCK, MUSHROOM_STEM -> Crop.BROWN_MUSHROOM;
            case RED_MUSHROOM, RED_MUSHROOM_BLOCK -> Crop.RED_MUSHROOM;
            case CHORUS_PLANT, CHORUS_FLOWER -> Crop.CHORUS;
            default -> null;
        };
    }

    static String farmName(Material item) {
        String subject = switch (item) {
            case BAMBOO -> "Bamboo";
            case SUGAR_CANE -> "Sugar Cane";
            case CACTUS -> "Cactus";
            case KELP -> "Kelp";
            case TWISTING_VINES -> "Twisting Vine";
            case WEEPING_VINES -> "Weeping Vine";
            case GLOW_BERRIES -> "Glow Berry";
            case WHEAT, WHEAT_SEEDS -> "Wheat";
            case CARROT -> "Carrot";
            case POTATO, POISONOUS_POTATO -> "Potato";
            case BEETROOT, BEETROOT_SEEDS -> "Beetroot";
            case NETHER_WART -> "Nether Wart";
            case COCOA_BEANS -> "Cocoa Bean";
            case PUMPKIN, PUMPKIN_SEEDS -> "Pumpkin";
            case MELON, MELON_SLICE, MELON_SEEDS -> "Melon";
            case SWEET_BERRIES -> "Sweet Berry";
            case BROWN_MUSHROOM, RED_MUSHROOM, BROWN_MUSHROOM_BLOCK, RED_MUSHROOM_BLOCK, MUSHROOM_STEM -> "Mushroom";
            case CHORUS_FLOWER, CHORUS_FRUIT -> "Chorus";
            case ROTTEN_FLESH, ZOMBIE_HEAD -> "Zombie";
            case BONE, ARROW, SKELETON_SKULL -> "Skeleton";
            case GUNPOWDER, CREEPER_HEAD -> "Creeper";
            case STRING, SPIDER_EYE -> "Spider";
            case ENDER_PEARL -> "Enderman";
            case BLAZE_ROD -> "Blaze";
            case SLIME_BALL -> "Slime";
            case MAGMA_CREAM -> "Magma Cube";
            case GHAST_TEAR -> "Ghast";
            case SHULKER_SHELL -> "Shulker";
            case WITHER_SKELETON_SKULL -> "Wither Skeleton";
            case IRON_INGOT, POPPY -> "Iron Golem";
            case GOLD_NUGGET, GOLD_INGOT -> "Zombified Piglin";
            case INK_SAC -> "Squid";
            case GLOW_INK_SAC -> "Glow Squid";
            case BEEF, LEATHER -> "Cow";
            case PORKCHOP -> "Pig";
            case MUTTON -> "Sheep";
            case CHICKEN, FEATHER, EGG, BLUE_EGG, BROWN_EGG -> "Chicken";
            case RABBIT, RABBIT_HIDE, RABBIT_FOOT -> "Rabbit";
            case COPPER_INGOT -> "Drowned";
            case PRISMARINE_SHARD, PRISMARINE_CRYSTALS -> "Guardian";
            case REDSTONE, GLOWSTONE_DUST, SUGAR, GLASS_BOTTLE -> "Witch";
            case COD -> "Cod";
            case SALMON -> "Salmon";
            case PUFFERFISH -> "Pufferfish";
            case TROPICAL_FISH -> "Tropical Fish";
            case PHANTOM_MEMBRANE -> "Phantom";
            case BREEZE_ROD -> "Breeze";
            case ARMADILLO_SCUTE -> "Armadillo";
            case TURTLE_SCUTE -> "Turtle";
            case HONEYCOMB, HONEY_BOTTLE -> "Bee";
            case NAUTILUS_SHELL -> "Drowned";
            default -> Tag.WOOL.isTagged(item) ? "Sheep" : null;
        };
        return subject == null ? null : subject + " Farm";
    }
}
