package dev.civmaker;

import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CivStoreUpgradeTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "CIVMAKER_UPGRADE_SOURCE", matches = ".+")
    void existingServerSnapshotKeepsClaimsMembershipsAndConfig() throws Exception {
        Path snapshot = Path.of(System.getenv("CIVMAKER_UPGRADE_SOURCE"));
        Files.copy(snapshot.resolve("state.json"), directory.resolve("state.json"));
        Files.copy(snapshot.resolve("config.yml"), directory.resolve("config.yml"));
        String config = Files.readString(directory.resolve("config.yml"));
        assertFalse(YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()).getKeys(true).isEmpty());
        var before = JsonParser.parseString(Files.readString(directory.resolve("state.json"))).getAsJsonObject();
        CivStore store = new CivStore(directory);
        assertFalse(before.getAsJsonObject("villages").isEmpty());
        for (var entry : before.getAsJsonObject("villages").entrySet()) {
            var saved = entry.getValue().getAsJsonObject();
            var village = store.village(UUID.fromString(entry.getKey()));
            assertNotNull(village);
            if (!saved.has("expandedClaims")) {
                assertEquals(100, village.capitalExpansionLimit());
            }
        }
        var upgraded = store.villages().iterator().next();
        upgraded.expandedClaims = true;
        store.save();
        var after = JsonParser.parseString(Files.readString(directory.resolve("state.json"))).getAsJsonObject();
        for (var entry : before.getAsJsonObject("villages").entrySet()) {
            var saved = entry.getValue().getAsJsonObject();
            var loaded = after.getAsJsonObject("villages").getAsJsonObject(entry.getKey());
            for (String field : new String[]{"name", "owner", "beacon", "members", "plots"}) {
                assertEquals(saved.get(field), loaded.get(field), field);
            }
        }
        assertEquals(200, new CivStore(directory).village(upgraded.id).capitalExpansionLimit());
        assertEquals(config, Files.readString(directory.resolve("config.yml")));
    }
}
