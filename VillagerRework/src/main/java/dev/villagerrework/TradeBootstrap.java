package dev.villagerrework;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TradeBootstrap implements PluginBootstrap {
    private static final String PACK_NAME = "biome_trades";
    static final String PACK_ID = "VillagerRework/" + PACK_NAME;
    private static final String VANILLA_PACK = "data/minecraft/datapacks/trade_rebalance/";

    @Override
    public void bootstrap(BootstrapContext context) {
        try {
            Files.createDirectories(context.getDataDirectory());
            Path pack = context.getDataDirectory().resolve(PACK_NAME + ".zip");
            writeTradePack(pack);
            context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
                try {
                    var discovered = event.registrar().discoverPack(pack, PACK_NAME,
                            config -> config.autoEnableOnServerStart(true).position(true, Datapack.Position.TOP));
                    if (discovered == null) {
                        context.getLogger().error("Paper could not discover the villager trade pack.");
                    }
                } catch (IOException error) {
                    context.getLogger().error("Could not register the villager trade pack.", error);
                }
            });
        } catch (IOException error) {
            throw new IllegalStateException("Could not prepare the villager trade pack.", error);
        }
    }

    private static void writeTradePack(Path destination) throws IOException {
        var resource = Bukkit.class.getClassLoader().getResource(VANILLA_PACK + "pack.mcmeta");
        if (resource == null || !(resource.openConnection() instanceof JarURLConnection connection)) {
            throw new IOException("Paper's built-in trade_rebalance pack is missing.");
        }
        connection.setUseCaches(false);
        try (var server = connection.getJarFile();
             var pack = new ZipOutputStream(Files.newOutputStream(destination))) {
            // Omit the experimental feature flag and structure loot overrides.
            pack.putNextEntry(new ZipEntry("pack.mcmeta"));
            pack.write("""
                    {"pack":{"description":"VillagerRework biome trades","min_format":121,"max_format":121}}
                    """.getBytes(StandardCharsets.UTF_8));
            pack.closeEntry();

            var entries = server.entries();
            int copied = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(VANILLA_PACK)) {
                    continue;
                }
                String path = entry.getName().substring(VANILLA_PACK.length());
                if (!path.startsWith("data/minecraft/villager_trade/")
                        && !path.startsWith("data/minecraft/tags/villager_trade/")
                        && !path.startsWith("data/minecraft/tags/enchantment/trades/")) {
                    continue;
                }
                // Cartographer trades are supplied by the base game and patched below.
                pack.putNextEntry(new ZipEntry(path));
                try (var contents = server.getInputStream(entry)) {
                    writeTradeData(pack, path, contents.readAllBytes());
                }
                pack.closeEntry();
                copied++;
            }
            if (copied == 0) {
                throw new IOException("Paper's built-in trade_rebalance pack contains no supported trade data.");
            }
            var cartographerTrades = server.entries();
            while (cartographerTrades.hasMoreElements()) {
                var entry = cartographerTrades.nextElement();
                String path = entry.getName();
                if (!path.startsWith("data/minecraft/villager_trade/cartographer/") || !path.endsWith(".json")) {
                    continue;
                }
                pack.putNextEntry(new ZipEntry(path));
                try (var contents = server.getInputStream(entry)) {
                    writeTradeData(pack, path, contents.readAllBytes());
                }
                pack.closeEntry();
            }
        }
    }

    private static void writeTradeData(ZipOutputStream pack, String path, byte[] bytes) throws IOException {
        JsonObject data = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (path.endsWith("/enchantment/trades/taiga_common.json")) {
            data.getAsJsonArray("values").add("minecraft:sharpness");
        } else if (path.endsWith("/villager_trade/librarian/level_5.json")) {
            data.getAsJsonArray("values").remove(JsonParser.parseString(
                    "\"minecraft:librarian/5/emerald_and_book_savanna_enchanted_book\""));
        } else if (path.contains("/villager_trade/librarian/5/emerald_and_book_")) {
            updateMasterBook(path, data);
        } else {
            addRegionalPools(data);
        }
        pack.write(data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void updateMasterBook(String path, JsonObject data) {
        String variant = path.substring(path.lastIndexOf("emerald_and_book_") + "emerald_and_book_".length(),
                path.lastIndexOf("_enchanted_book"));
        switch (variant) {
            case "plains" -> data.add("merchant_predicate", regionPredicate("plains"));
            case "jungle" -> addRegionAlternatives(data, List.of("savanna"));
            case "swamp" -> addRegionAlternatives(data, List.of("meadow"));
            default -> { }
        }
        switch (variant) {
            case "desert" -> setBookLevel(data, "efficiency", 5);
            case "plains" -> setBookLevel(data, "protection", 4);
            case "jungle" -> setBookLevel(data, "unbreaking", 3);
            case "taiga" -> setBookLevel(data, "fortune", 3);
            default -> { }
        }
    }

    private static void setBookLevel(JsonObject data, String enchantment, int level) {
        var modifiers = data.getAsJsonArray("given_item_modifier");
        modifiers.get(0).getAsJsonObject().getAsJsonObject("enchantments").addProperty("minecraft:" + enchantment, level);
        modifiers.get(1).getAsJsonObject().getAsJsonObject("item_filter").getAsJsonObject("predicates")
                .getAsJsonArray("minecraft:stored_enchantments").get(0).getAsJsonObject().addProperty("levels", level);
        var price = data.getAsJsonObject("wants").getAsJsonObject("count").getAsJsonArray("inputs");
        price.set(0, new com.google.gson.JsonPrimitive(2 + 3 * level));
        price.get(1).getAsJsonObject().addProperty("max", Math.min(4 + 10 * level, 64 - (2 + 3 * level)));
    }

    private static void addRegionalPools(JsonObject data) {
        var merchant = data.getAsJsonObject("merchant_predicate");
        if (merchant == null || !merchant.has("predicate")) {
            return;
        }
        var predicates = merchant.getAsJsonObject("predicate").getAsJsonObject("minecraft:predicates");
        if (predicates == null || !predicates.has("minecraft:villager/variant")) {
            return;
        }
        var variants = predicates.get("minecraft:villager/variant");
        var regions = new java.util.ArrayList<String>(2);
        if (includesVariant(variants, "jungle") || includesVariant(variants, "savanna")) {
            regions.add("savanna");
        }
        if (includesVariant(variants, "swamp") || includesVariant(variants, "plains")) {
            regions.add("meadow");
        }
        if (!regions.isEmpty()) {
            addRegionAlternatives(data, regions);
        }
    }

    private static boolean includesVariant(JsonElement variants, String variant) {
        var key = new com.google.gson.JsonPrimitive("minecraft:" + variant);
        return variants.isJsonArray() ? variants.getAsJsonArray().contains(key) : variants.equals(key);
    }

    private static void addRegionAlternatives(JsonObject data, List<String> regions) {
        var terms = new JsonArray();
        terms.add(data.get("merchant_predicate"));
        regions.forEach(region -> terms.add(regionPredicate(region)));
        var any = new JsonObject();
        any.addProperty("type", "minecraft:any_of");
        any.add("terms", terms);
        data.add("merchant_predicate", any);
    }

    private static JsonElement regionPredicate(String region) {
        return JsonParser.parseString("""
                {"type":"minecraft:entity_properties","entity":"this",
                 "predicate":{"nbt":"{Tags:[\\"villagerrework.%s\\"]}"}}
                """.formatted(region));
    }
}
