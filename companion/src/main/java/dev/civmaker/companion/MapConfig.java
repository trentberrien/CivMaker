package dev.civmaker.companion;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MapConfig {
    public static MapConfig INSTANCE = new MapConfig();
    public boolean enabled = true;
    public boolean markers = true;
    public boolean farms = true;
    public int fillOpacity = 28;
    public int borderOpacity = 90;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("civmaker-companion.json");
    }

    static void load() {
        if (Files.exists(path())) {
            try (var reader = Files.newBufferedReader(path())) {
                MapConfig config = new GsonBuilder().create().fromJson(reader, MapConfig.class);
                if (config != null) { INSTANCE = config; }
            } catch (Exception error) {
                CivMakerCompanion.LOG.warn("Could not read CivMaker map settings; using defaults", error);
            }
        }
        INSTANCE.fillOpacity = Math.clamp(INSTANCE.fillOpacity, 0, 100);
        INSTANCE.borderOpacity = Math.clamp(INSTANCE.borderOpacity, 0, 100);
        MapState.INSTANCE.setEnabled(INSTANCE.enabled);
        MapState.INSTANCE.setFarmsEnabled(INSTANCE.farms);
    }

    void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(this));
        } catch (Exception error) {
            CivMakerCompanion.LOG.warn("Could not save CivMaker map settings", error);
        }
    }

    public int fillAlpha() { return fillOpacity * 255 / 100; }
    public int borderAlpha() { return borderOpacity * 255 / 100; }
}
