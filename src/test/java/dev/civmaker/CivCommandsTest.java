package dev.civmaker;

import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CivCommandsTest {
    @TempDir Path directory;
    private CivStore store;
    private CivCommands commands;
    private Server server;
    private CivStore.Village faction;
    private Player owner;
    private Player elder;
    private Player otherElder;
    private Player member;
    private Player outsider;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        CivMaker plugin = mock(CivMaker.class);
        server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        commands = new CivCommands(plugin, store, null, null, null, null, null, null, null, null, null, null, null);
        owner = player("Owner");
        elder = player("Elder");
        otherElder = player("OtherElder");
        member = player("Member");
        outsider = player("Outsider");
        faction = new CivStore.Village("Test faction", owner.getUniqueId(),
                new CivStore.Position(UUID.randomUUID(), 0, 80, 0));
        store.addVillage(faction);
        for (Player player : List.of(elder, otherElder, member)) {
            store.addMember(faction, player.getUniqueId());
        }
        store.setElder(faction, elder.getUniqueId(), true);
        store.setElder(faction, otherElder.getUniqueId(), true);
        store.dirty = false;
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        when(server.getOfflinePlayer(id)).thenReturn(player);
        when(server.getOfflinePlayerIfCached(name)).thenReturn(player);
        return player;
    }

    private void run(Player sender, String... args) {
        assertTrue(commands.onCommand(sender, null, "civ", args));
    }

    @Test void ownerCanPromoteAndDemoteAndNotifyMember() {
        run(owner, "promote", "Member");
        assertTrue(faction.elders.contains(member.getUniqueId()));
        assertTrue(store.dirty);
        verify(member).sendMessage("You were promoted to elder in Test faction by Owner.");
        run(owner, "demote", "Member");
        assertFalse(faction.elders.contains(member.getUniqueId()));
        assertSame(faction, store.registeredVillage(member.getUniqueId()));
    }

    @Test void nonOwnersCannotPromoteOrDemote() {
        for (Player sender : List.of(elder, member, outsider)) {
            run(sender, "promote", "Member");
            run(sender, "demote", "OtherElder");
        }
        assertFalse(faction.elders.contains(member.getUniqueId()));
        assertTrue(faction.elders.contains(otherElder.getUniqueId()));
        assertFalse(store.dirty);
    }

    @Test void ownerAndForeignPlayersCannotHaveTheirRoleChanged() {
        run(owner, "promote", "Owner");
        run(owner, "demote", "Owner");
        run(owner, "promote", "Outsider");
        run(owner, "demote", "Outsider");
        assertFalse(faction.elders.contains(owner.getUniqueId()));
        assertFalse(faction.elders.contains(outsider.getUniqueId()));
        assertFalse(store.dirty);
    }

    @Test void repeatedRoleChangesAreRejectedWithoutDirtyingState() {
        run(owner, "promote", "Elder");
        run(owner, "demote", "Member");
        verify(owner).sendMessage("Elder is already an elder.");
        verify(owner).sendMessage("Member is not an elder.");
        assertFalse(store.dirty);
    }

    @Test void ownersAndEldersCanInviteAndRecruitsCanJoin() {
        run(elder, "invite", "Outsider");
        run(outsider, "join");
        assertSame(faction, store.registeredVillage(outsider.getUniqueId()));
        assertFalse(faction.elders.contains(outsider.getUniqueId()));
        Player recruit = player("Recruit");
        run(owner, "invite", "Recruit");
        run(recruit, "join");
        assertSame(faction, store.registeredVillage(recruit.getUniqueId()));
    }

    @Test void ordinaryAndDemotedMembersCannotInvite() {
        run(member, "invite", "Outsider");
        run(outsider, "join");
        assertNull(store.registeredVillage(outsider.getUniqueId()));
        run(owner, "demote", "Elder");
        run(elder, "invite", "Outsider");
        run(outsider, "join");
        assertNull(store.registeredVillage(outsider.getUniqueId()));
    }

    @Test void eldersCanKickOrdinaryMembersAndNotifyThem() {
        run(elder, "kick", "Member");
        assertNull(store.registeredVillage(member.getUniqueId()));
        assertFalse(faction.members.contains(member.getUniqueId()));
        assertTrue(store.dirty);
        verify(member).sendMessage("You were kicked from Test faction by Elder.");
    }

    @Test void eldersCannotKickOwnersEldersOrThemselves() {
        for (String name : List.of("Owner", "OtherElder", "Elder")) {
            run(elder, "kick", name);
        }
        assertEquals(4, faction.members.size());
        assertEquals(2, faction.elders.size());
        assertFalse(store.dirty);
    }

    @Test void ownersCanKickEldersAndMembersButCannotKickThemselves() {
        run(owner, "kick", "Elder");
        run(owner, "kick", "Member");
        run(owner, "kick", "Owner");
        assertNull(store.registeredVillage(elder.getUniqueId()));
        assertNull(store.registeredVillage(member.getUniqueId()));
        assertFalse(faction.elders.contains(elder.getUniqueId()));
        assertSame(faction, store.registeredVillage(owner.getUniqueId()));
    }

    @Test void ordinaryMembersCannotKickAndForeignPlayersCannotBeKicked() {
        run(member, "kick", "Elder");
        run(outsider, "kick", "Member");
        run(elder, "kick", "Outsider");
        run(owner, "kick", "Outsider");
        assertEquals(4, faction.members.size());
        assertFalse(store.dirty);
    }

    @Test void offlineMembersCanBePromotedDemotedAndKickedByNameOrUuid() {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        UUID id = UUID.randomUUID();
        when(offline.getUniqueId()).thenReturn(id);
        when(offline.getName()).thenReturn("Offline");
        when(server.getOfflinePlayerIfCached("Offline")).thenReturn(offline);
        when(server.getOfflinePlayer(id)).thenReturn(offline);
        store.addMember(faction, id);
        run(owner, "promote", "Offline");
        assertTrue(faction.elders.contains(id));
        run(owner, "demote", id.toString());
        assertFalse(faction.elders.contains(id));
        run(elder, "kick", "Offline");
        assertNull(store.registeredVillage(id));
    }

    @Test void leavingAndRejoiningDoesNotRestoreElderRole() {
        run(elder, "leave");
        assertFalse(faction.elders.contains(elder.getUniqueId()));
        run(owner, "invite", "Elder");
        run(elder, "join");
        assertSame(faction, store.registeredVillage(elder.getUniqueId()));
        assertFalse(faction.elders.contains(elder.getUniqueId()));
    }

    @Test void testingAllegianceDoesNotGrantFactionManagement() {
        store.setAllegiance(outsider.getUniqueId(), faction);
        when(outsider.hasPermission("civmaker.admin")).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(outsider.getUniqueId())).thenReturn(outsider);
            run(outsider, "promote", "Member");
            run(outsider, "demote", "Elder");
            run(outsider, "kick", "Member");
            run(outsider, "invite", "Owner");
        }
        assertEquals(4, faction.members.size());
        assertEquals(2, faction.elders.size());
        assertFalse(store.dirty);
        verify(outsider, times(2)).sendMessage("Only a faction owner or elder can use this command.");
    }

    @Test void completionsOfferOnlyEligibleFactionMembersIncludingOfflinePlayers() {
        assertEquals(List.of("Member"), commands.onTabComplete(owner, null, "civ", new String[]{"promote", ""}));
        assertEquals(List.of("Elder", "OtherElder"), commands.onTabComplete(owner, null, "civ", new String[]{"demote", ""}));
        assertEquals(List.of("Member"), commands.onTabComplete(elder, null, "civ", new String[]{"kick", ""}));
        assertTrue(commands.onTabComplete(member, null, "civ", new String[]{"kick", ""}).isEmpty());
        assertTrue(commands.onTabComplete(elder, null, "civ", new String[]{"promote", ""}).isEmpty());
        when(server.getPlayer(member.getUniqueId())).thenReturn(null);
        assertEquals(List.of("Member"), commands.onTabComplete(elder, null, "civ", new String[]{"kick", "M"}));
    }

    @Test void invalidArgumentsDoNotChangeMembershipOrRoles() {
        for (String action : List.of("promote", "demote", "kick")) {
            run(owner, action);
            run(owner, action, "Member", "extra");
            run(owner, action, "Missing");
        }
        assertEquals(4, faction.members.size());
        assertEquals(2, faction.elders.size());
        assertFalse(store.dirty);
    }

    @Test void rolesSurviveSaveAndReloadAndOldSavesDefaultToNoElders() throws Exception {
        run(owner, "promote", "Member");
        store.save();
        CivStore reloaded = new CivStore(directory);
        assertEquals(faction.elders, reloaded.village(faction.id).elders);
        assertSame(reloaded.village(faction.id), reloaded.registeredVillage(member.getUniqueId()));
        var file = directory.resolve("state.json");
        var legacy = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        legacy.getAsJsonObject("villages").getAsJsonObject(faction.id.toString()).remove("elders");
        Files.writeString(file, legacy.toString());
        CivStore upgraded = new CivStore(directory);
        assertTrue(upgraded.village(faction.id).elders.isEmpty());
        assertEquals(faction.members, upgraded.village(faction.id).members);
        upgraded.save();
        assertTrue(new CivStore(directory).village(faction.id).elders.isEmpty());
    }
}
