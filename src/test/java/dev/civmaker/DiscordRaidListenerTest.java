package dev.civmaker;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.Mockito.*;

class DiscordRaidListenerTest {
    @TempDir Path directory;
    private CivMaker plugin;
    private CivStore store;
    private CivStore.Village faction;
    private DiscordRaidListener listener;
    private Player attacker;
    private Player defender;
    private Block block;
    private Location location;

    @BeforeEach void setup() throws Exception {
        plugin = mock(CivMaker.class);
        store = new CivStore(directory);
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        attacker = player("Raider");
        defender = player("Defender");
        faction = new CivStore.Village("Ashford", defender.getUniqueId(), new CivStore.Position(worldId, 0, 80, 0));
        store.addVillage(faction);
        store.setRaidMode(CivStore.RaidMode.ON);
        block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(80);
        location = new Location(world, 0, 80, 0);
        when(world.getBlockAt(0, 80, 0)).thenReturn(block);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(defender.getLocation()).thenReturn(location);
        listener = new DiscordRaidListener(plugin, store);
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return player;
    }

    @Test void onlyEnemySurvivalBlockBreaksReportRaidActivity() {
        listener.blockBreak(new BlockBreakEvent(block, attacker));
        verify(plugin).discordRaidAlert(faction, "Raider", location, "block_break");
        clearInvocations(plugin);
        listener.blockBreak(new BlockBreakEvent(block, defender));
        when(attacker.getGameMode()).thenReturn(GameMode.CREATIVE);
        listener.blockBreak(new BlockBreakEvent(block, attacker));
        verifyNoInteractions(plugin);
    }

    @Test void directAndProjectileAttacksOnDefendersReportButFriendlyOrZeroDamageDoesNot() {
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(defender);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(5.0);
        listener.combat(event);
        verify(plugin).discordRaidAlert(faction, "Raider", location, "combat");
        clearInvocations(plugin);
        Projectile arrow = mock(Projectile.class);
        when(arrow.getShooter()).thenReturn(attacker);
        when(event.getDamager()).thenReturn(arrow);
        listener.combat(event);
        verify(plugin).discordRaidAlert(faction, "Raider", location, "combat");
        clearInvocations(plugin);
        store.addMember(faction, attacker.getUniqueId());
        listener.combat(event);
        store.removeMember(faction, attacker.getUniqueId());
        when(event.getFinalDamage()).thenReturn(0.0);
        listener.combat(event);
        verifyNoInteractions(plugin);
    }
}
