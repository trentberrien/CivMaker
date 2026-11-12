package dev.civmaker;

import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
    private YamlConfiguration config;
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
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
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

    private String help(CommandSender sender, String... args) {
        clearInvocations(sender);
        assertTrue(commands.onCommand(sender, null, "civ", args));
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(messages.capture());
        return String.join("\n", messages.getAllValues());
    }

    private List<String> completions(CommandSender sender) {
        return commands.onTabComplete(sender, null, "civ", new String[]{""});
    }

    @Test void outsidersSeeFoundingAndPublicHelpWithoutManagementCommands() {
        String help = help(outsider, "help");
        assertTrue(help.contains("/civ create buys a Founding Beacon"));
        assertTrue(help.contains("/civ balance | withdraw <notes> | deposit [notes]"));
        assertTrue(help.contains("/civ villages | points | raids | raidprotection"));
        assertTrue(help.contains("/civ shop"));
        for (String hidden : List.of("claim", "settlement", "invite", "join", "leave", "promote", "demote",
                "kick", "disband", "banner", "schedule", "cancelraid", "raidmode", "admin")) {
            assertFalse(help.contains(hidden), hidden);
            assertFalse(completions(outsider).contains(hidden), hidden);
        }
        assertTrue(commands.onTabComplete(outsider, null, "civ", new String[]{"claim", ""}).isEmpty());
        assertTrue(commands.onTabComplete(outsider, null, "civ", new String[]{"schedule", ""}).isEmpty());
    }

    @Test void membersSeeLeaveButOnlyLeadersSeeManagementHelp() {
        for (Player player : List.of(member, elder, owner)) {
            String help = help(player, "help");
            boolean manager = player != member;
            boolean isOwner = player == owner;
            assertEquals(manager, help.contains("/civ claim"));
            assertEquals(manager, help.contains("/civ settlement"));
            assertEquals(manager, help.contains("/civ invite"));
            assertEquals(manager, help.contains("/civ kick"));
            assertEquals(manager, help.contains("upgrade to 200 expansion claims"));
            assertEquals(isOwner, help.contains("/civ promote"));
            assertEquals(isOwner, help.contains("demote <player>"));
            assertEquals(isOwner, help.contains("/civ disband"));
            assertEquals(isOwner, help.contains("/civ banner"));
            assertEquals(!isOwner, help.contains("/civ leave"));
            assertFalse(help.contains("/civ create"));
            assertFalse(help.contains("/civ join"));
            List<String> available = completions(player);
            assertEquals(manager, available.contains("claim"));
            assertEquals(manager, available.contains("invite"));
            assertEquals(manager, available.contains("kick"));
            assertEquals(isOwner, available.contains("promote"));
            assertEquals(isOwner, available.contains("disband"));
            assertEquals(!isOwner, available.contains("leave"));
        }
        faction.expandedClaims = true;
        assertFalse(help(owner, "help").contains("upgrade to 200 expansion claims"));
    }

    @Test void joinHelpAppearsOnlyWhileAnEligiblePlayerHasAnInvite() {
        assertFalse(help(outsider, "help").contains("/civ join"));
        run(elder, "invite", "Outsider");
        assertTrue(help(outsider, "help").contains("/civ join"));
        assertTrue(completions(outsider).contains("join"));
        run(outsider, "join");
        assertFalse(help(outsider, "help").contains("/civ join"));
        assertFalse(completions(outsider).contains("join"));
        run(outsider, "leave");
        assertFalse(help(outsider, "help").contains("/civ join"));
        assertTrue(help(outsider, "help").contains("/civ create"));
    }

    @Test void nonSurvivalHelpHidesSurvivalCommandsButKeepsRoleCommands() {
        run(owner, "invite", "Outsider");
        for (GameMode mode : List.of(GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR)) {
            for (Player player : List.of(outsider, owner)) {
                when(player.getGameMode()).thenReturn(mode);
                String help = help(player, "help");
                assertTrue(help.contains("/civ balance"));
                for (String hidden : List.of("withdraw", "deposit", "create", "join", "claim", "settlement", "invite", "shop")) {
                    assertFalse(help.contains(hidden), hidden);
                    assertFalse(completions(player).contains(hidden), hidden);
                }
                assertEquals(player == owner, help.contains("/civ disband"));
            }
        }
    }

    @Test void adminHelpUsesPermissionOrStoredRoleAndSeparatesAdminManagement() {
        when(owner.hasPermission("civmaker.admin")).thenReturn(true);
        store.setAdmin(outsider.getUniqueId(), true);
        for (Player player : List.of(owner, outsider)) {
            String help = help(player, "help");
            assertTrue(help.contains("/civ schedule <start ISO-8601>"));
            assertTrue(help.contains("/civ schedule nightly"));
            assertTrue(help.contains("/civ raidprotection <on|off|price amount|cooldown hours>"));
            assertTrue(help.contains("/civ allegiance"));
            assertTrue(help.contains("/civ farmstation test"));
            assertFalse(help.contains("/civ admin"));
            assertTrue(completions(player).containsAll(List.of("schedule", "cancelraid", "raidmode", "farmstation")));
            assertFalse(completions(player).contains("admin"));
        }
        when(member.hasPermission("civmaker.admin.manage")).thenReturn(true);
        String help = help(member, "help");
        assertTrue(help.contains("/civ admin <add|remove>"));
        assertFalse(help.contains("/civ schedule"));
        assertTrue(completions(member).contains("admin"));
    }

    @Test void consoleHelpAndCompletionsOmitCommandsThatRequireAPlayer() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission("civmaker.admin")).thenReturn(true);
        when(console.hasPermission("civmaker.admin.manage")).thenReturn(true);
        String help = help(console, "help");
        assertTrue(help.contains("/civ schedule"));
        assertTrue(help.contains("/civ admin"));
        for (String hidden : List.of("balance", "create", "claim", "settlement", "invite", "join", "leave",
                "shop", "allegiance", "testdeposit", "farmstation", "locatemeadow")) {
            assertFalse(help.contains("/civ " + hidden), hidden);
            assertFalse(completions(console).contains(hidden), hidden);
        }
    }

    @Test void defaultAndUnknownCommandsUseTheSameFilteredHelp() {
        String expected = help(member, "help");
        assertEquals(expected, help(member));
        assertEquals(expected, help(member, "unknown"));
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
        assertTrue(faction.elders.contains(outsider.getUniqueId()));
        Player recruit = player("Recruit");
        run(owner, "invite", "Recruit");
        run(recruit, "join");
        assertSame(faction, store.registeredVillage(recruit.getUniqueId()));
        assertTrue(faction.elders.contains(recruit.getUniqueId()));
    }

    @Test void disablingDefaultElderStatusMakesNewInviteesOrdinaryMembers() {
        config.set("village.invite-as-elder", false);
        run(owner, "invite", "Outsider");
        run(outsider, "join");
        assertSame(faction, store.registeredVillage(outsider.getUniqueId()));
        assertFalse(faction.elders.contains(outsider.getUniqueId()));
        assertTrue(faction.elders.contains(elder.getUniqueId()));
        run(outsider, "invite", "Owner");
        verify(outsider).sendMessage("Only a faction owner or elder can use this command.");
    }

    @Test void inviteRankUsesTheSettingAtAcceptance() {
        run(owner, "invite", "Outsider");
        config.set("village.invite-as-elder", false);
        run(outsider, "join");
        assertFalse(faction.elders.contains(outsider.getUniqueId()));
        run(outsider, "leave");
        run(owner, "invite", "Outsider");
        config.set("village.invite-as-elder", true);
        run(outsider, "join");
        assertTrue(faction.elders.contains(outsider.getUniqueId()));
    }

    @Test void invitedElderRoleAndOwnerDemotionPersistAcrossRestarts() throws Exception {
        run(owner, "invite", "Outsider");
        run(outsider, "join");
        verify(outsider).sendMessage("You joined Test faction as an elder.");
        store.save();
        assertTrue(new CivStore(directory).village(faction.id).elders.contains(outsider.getUniqueId()));
        run(owner, "demote", "Outsider");
        run(outsider, "invite", "Owner");
        verify(outsider).sendMessage("Only a faction owner or elder can use this command.");
        store.save();
        assertFalse(new CivStore(directory).village(faction.id).elders.contains(outsider.getUniqueId()));
        assertFalse(faction.elders.contains(member.getUniqueId()), "Existing ordinary members keep their rank");
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

    @Test void leavingAndRejoiningUsesConfiguredDefaultRank() {
        run(elder, "leave");
        assertFalse(faction.elders.contains(elder.getUniqueId()));
        run(owner, "invite", "Elder");
        run(elder, "join");
        assertSame(faction, store.registeredVillage(elder.getUniqueId()));
        assertTrue(faction.elders.contains(elder.getUniqueId()));
        config.set("village.invite-as-elder", false);
        run(elder, "leave");
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
