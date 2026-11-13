package dev.civmaker;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

final class Wilderness implements CommandExecutor, Listener {
    private static final int MAX_ATTEMPTS = 64;
    private static final double PLAYER_MARGIN = 0.31;
    private static final int WORLD_LIMIT = 29_999_984;

    private final CivMaker plugin;
    private final CivStore store;
    private final CombatTimeout combat;
    private final Map<UUID, Search> searches = new HashMap<>();

    Wilderness(CivMaker plugin, CivStore store, CombatTimeout combat) {
        this.plugin = plugin;
        this.store = store;
        this.combat = combat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /wilderness.");
        } else if (args.length != 0) {
            player.sendMessage("Usage: /wilderness");
        } else if (!canTravel(player)) {
            player.sendMessage("You cannot use /wilderness while dead or in combat timeout.");
        } else if (searches.containsKey(player.getUniqueId())) {
            player.sendMessage("A wilderness destination search is already running for you.");
        } else {
            Search search = new Search(player, player.getWorld());
            searches.put(player.getUniqueId(), search);
            player.sendMessage("Finding a safe wilderness location inside the world border...");
            search.next();
        }
        return true;
    }

    private boolean canTravel(Player player) {
        return !player.isDead() && !combat.locked(player);
    }

    private final class Search {
        private final Player player;
        private final World world;
        private int attempts;

        private Search(Player player, World world) {
            this.player = player;
            this.world = world;
        }

        private boolean active() {
            if (!plugin.isEnabled() || searches.get(player.getUniqueId()) != this) {
                return false;
            }
            if (!player.isOnline() || player.getWorld() != world || !canTravel(player)) {
                finish("Wilderness travel cancelled.");
                return false;
            }
            return true;
        }

        private void next() {
            if (!active()) return;
            Bounds bounds = Bounds.inside(world.getWorldBorder());
            if (attempts++ >= MAX_ATTEMPTS || bounds == null) {
                finish("Could not find a safe wilderness location inside the world border. Try again.");
                return;
            }
            var random = ThreadLocalRandom.current();
            int x = random.nextInt(bounds.minX(), bounds.maxX() + 1);
            int z = random.nextInt(bounds.minZ(), bounds.maxZ() + 1);
            if (store.claimAt(new CivStore.Plot(world.getUID(), x >> 4, z >> 4)) != null) {
                plugin.getServer().getScheduler().runTask(plugin, this::next);
                return;
            }
            world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) -> {
                if (!plugin.isEnabled()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!active()) return;
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not load a wilderness destination.", error);
                        finish("Could not load a wilderness destination. Try again later.");
                        return;
                    }
                    Location destination = chunk != null && world.isChunkLoaded(x >> 4, z >> 4)
                            ? landing(world, x, z) : null;
                    if (destination == null || store.claimAt(CivStore.Plot.at(destination)) != null) {
                        next();
                        return;
                    }
                    destination.setYaw(player.getYaw());
                    destination.setPitch(player.getPitch());
                    boolean teleported = player.teleport(destination, TeleportCause.COMMAND);
                    finish(teleported ? "Teleported to the wilderness." : "Wilderness teleport was cancelled.");
                });
            });
        }

        private void finish(String message) {
            searches.remove(player.getUniqueId(), this);
            if (player.isOnline()) player.sendMessage(message);
        }
    }

    // Integer block centers with enough space for the player's entire width inside the border.
    record Bounds(int minX, int maxX, int minZ, int maxZ) {
        static Bounds inside(WorldBorder border) {
            Location center = border.getCenter();
            double half = border.getSize() / 2;
            int minX = (int) Math.ceil(Math.max(-WORLD_LIMIT, center.getX() - half) + PLAYER_MARGIN - 0.5);
            int maxX = (int) Math.floor(Math.min(WORLD_LIMIT, center.getX() + half) - PLAYER_MARGIN - 0.5);
            int minZ = (int) Math.ceil(Math.max(-WORLD_LIMIT, center.getZ() - half) + PLAYER_MARGIN - 0.5);
            int maxZ = (int) Math.floor(Math.min(WORLD_LIMIT, center.getZ() + half) - PLAYER_MARGIN - 0.5);
            return minX <= maxX && minZ <= maxZ ? new Bounds(minX, maxX, minZ, maxZ) : null;
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    static Location landing(World world, int x, int z) {
        Bounds bounds = Bounds.inside(world.getWorldBorder());
        if (bounds == null || !bounds.contains(x, z)) return null;
        int top = Math.min(world.getMaxHeight() - 3,
                world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING));
        // Search below the Nether roof instead of stranding players on bedrock above it.
        boolean nether = world.getEnvironment() == World.Environment.NETHER;
        if (nether) top = Math.min(top, world.getMinHeight() + world.getLogicalHeight() - 3);
        for (int y = top; y >= world.getMinHeight(); y--) {
            Block floor = world.getBlockAt(x, y, z);
            if (safeFloor(floor) && world.getBlockAt(x, y + 1, z).isEmpty()
                    && world.getBlockAt(x, y + 2, z).isEmpty()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
            if (!nether) break;
        }
        return null;
    }

    private static boolean safeFloor(Block floor) {
        Material type = floor.getType();
        if (!floor.isSolid() || floor.isLiquid()) return false;
        return switch (type) {
            case MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, POINTED_DRIPSTONE, POWDER_SNOW -> false;
            default -> floor.getBoundingBox().getMaxY() == floor.getY() + 1;
        };
    }

    @EventHandler
    void quit(PlayerQuitEvent event) {
        searches.remove(event.getPlayer().getUniqueId());
    }

    void close() {
        searches.clear();
    }
}
