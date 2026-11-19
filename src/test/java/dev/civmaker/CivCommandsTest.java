package dev.civmaker;

import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class CivCommandsTest {
    @TempDir Path directory;
    private CivStore store;
    private CivCommands commands;
    private FactionMobs factionMobs;
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
        factionMobs = mock(FactionMobs.class);
        commands = new CivCommands(plugin, store, null, null, null, null, null, null, null, null, null, null, null,
                factionMobs);
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

    @Test void tameIsAvailableToAllSurvivalMembersAndRoutesOptionalNames() {
        for (Player player : List.of(member, elder, owner)) {
            assertTrue(help(player, "help").contains("/civ tame [name]"));
            assertTrue(completions(player).contains("tame"));
            run(player, "tame");
            verify(factionMobs).tame(eq(player), aryEq(new String[]{"tame"}));
        }
        run(member, "tame", "Silver", "Wind");
        verify(factionMobs).tame(eq(member), aryEq(new String[]{"tame", "Silver", "Wind"}));
        assertFalse(help(outsider, "help").contains("/civ tame"));
        assertFalse(completions(outsider).contains("tame"));
        when(member.getGameMode()).thenReturn(GameMode.CREATIVE);
        assertFalse(help(member, "help").contains("/civ tame"));
        assertFalse(completions(member).contains("tame"));
        clearInvocations(factionMobs);
        run(member, "tame");
        verifyNoInteractions(factionMobs);
        verify(member).sendMessage("Switch to Survival to use this command.");
    }

    @Test void outsidersSeeFoundingAndPublicHelpWithoutManagementCommands() {
        String help = help(outsider, "help");
        assertTrue(help.contains("/civ create buys a Founding Beacon"));
        assertTrue(help.contains("/civ balance | withdraw <notes> | deposit [notes]"));
        assertTrue(help.contains("/civ villages | points | raids | raidprotection"));
        assertTrue(help.contains("/civ shop"));
        for (String hidden : List.of("claim", "settlement", "invite", "join", "leave", "promote", "demote",
                "kick", "disband", "banner", "schedule", "cancelraid", "raidmode", "admin")) {
            assertFalse(help.matches("(?s).*\\b" + hidden + "\\b.*"), hidden);
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
                    assertFalse(help.matches("(?s).*\\b" + hidden + "\\b.*"), hidden);
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

    @Test void incentiveCommandsAreAvailableToAllPlayersAndConsole() {
        for (CommandSender sender : List.of(owner, member, outsider, mock(CommandSender.class))) {
            assertTrue(completions(sender).containsAll(List.of("kills", "settlements", "egg", "dragonegg")));
            assertTrue(help(sender, "help").contains("/civ kills | settlements | egg"));
        }
        when(outsider.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertTrue(completions(outsider).containsAll(List.of("kills", "settlements", "egg")));
        for (String action : List.of("kills", "settlements", "egg", "dragonegg")) {
            String message = help(outsider, action, "extra");
            assertEquals("Use /civ " + (action.equals("dragonegg") ? "egg" : action) + ".", message);
        }
    }

    private void recordKills(Player player, int count) {
        store.setRaidMode(CivStore.RaidMode.ON);
        for (int i = 0; i < count; i++) {
            store.recordRaidKill(player.getUniqueId(), outsider.getUniqueId(), System.currentTimeMillis());
        }
    }

    @Test void killLeaderboardUsesIndividualTotalsSharedRanksOfflineNamesAndCurrentFactions() {
        recordKills(owner, 5);
        recordKills(elder, 3);
        recordKills(otherElder, 3);
        recordKills(member, 5);
        store.removeMember(faction, member.getUniqueId());
        when(server.getPlayer(member.getUniqueId())).thenReturn(null);
        String message = help(outsider, "kills");
        assertTrue(message.contains("1. Member — 5 kills | No faction"));
        assertTrue(message.contains("1. Owner — 5 kills | Test faction"));
        assertTrue(message.contains("3. Elder — 3 kills | Test faction"));
        assertTrue(message.contains("3. OtherElder — 3 kills | Test faction"));
        assertTrue(message.indexOf("1. Member") < message.indexOf("1. Owner"));
        assertFalse(message.contains("16 kills"));
        assertFalse(message.contains("Outsider —"));
    }

    @Test void killLeaderboardHandlesEmptyCountsAndLimitsResultsToTen() {
        assertEquals("No raid-hour player kills have been recorded yet.", help(outsider, "kills"));
        for (int i = 1; i <= 12; i++) {
            Player player = player("Killer" + i);
            store.addMember(faction, player.getUniqueId());
            recordKills(player, i);
        }
        String message = help(outsider, "kills");
        assertEquals(11, message.lines().count());
        assertTrue(message.contains("1. Killer12 — 12 kills"));
        assertTrue(message.contains("10. Killer3 — 3 kills"));
        assertFalse(message.contains("Killer2 —"));
    }

    @Test void settlementLeaderboardCountsOwnershipAndUpdatesAfterCapture() {
        var rival = new CivStore.Village("Rival", UUID.randomUUID(),
                new CivStore.Position(faction.beacon.world(), 32, 80, 0));
        store.addVillage(rival);
        var first = new CivStore.Settlement(null, new CivStore.Position(faction.beacon.world(), 64, 80, 0),
                java.util.Set.of(new CivStore.Plot(faction.beacon.world(), 4, 0)));
        var second = new CivStore.Settlement(null, new CivStore.Position(faction.beacon.world(), 96, 80, 0),
                java.util.Set.of(new CivStore.Plot(faction.beacon.world(), 6, 0)));
        store.addSettlement(faction, first);
        store.addSettlement(rival, second);
        String tied = help(outsider, "settlements");
        assertTrue(tied.contains("1. Rival — 1 settlement"));
        assertTrue(tied.contains("1. Test faction — 1 settlement"));
        store.transferSettlement(first, faction, rival);
        String captured = help(outsider, "settlements");
        assertTrue(captured.contains("1. Rival — 2 settlements"));
        assertTrue(captured.contains("2. Test faction — 0 settlements"));
        store.disband(rival);
        store.disband(faction);
        assertEquals("No villages have been founded yet.", help(outsider, "settlements"));
    }

    private Block displayedEgg() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("overworld");
        when(server.getWorld(faction.beacon.world())).thenReturn(world);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        Block beacon = mock(Block.class);
        when(beacon.getType()).thenReturn(Material.BEACON);
        when(world.getBlockAt(0, 80, 0)).thenReturn(beacon);
        Block egg = mock(Block.class);
        when(egg.getType()).thenReturn(Material.DRAGON_EGG);
        when(world.getBlockAt(0, 81, 0)).thenReturn(egg);
        return egg;
    }

    @Test void eggCommandShowsActualEggCoordinatesDefenseProgressAndPermanentProtection() {
        displayedEgg();
        store.observeEgg(faction, true, System.currentTimeMillis() - 1000);
        faction.eggDefendedRaids = 2;
        String message = help(outsider, "egg");
        assertEquals("Dragon Egg — Test faction | overworld | X 0, Y 81, Z 0 | Raids defended: 2/3", message);
        faction.eggProtected = true;
        assertTrue(help(outsider, "dragonegg").endsWith("Permanently protected"));
    }

    @Test void eggCommandDoesNotReportAStaleHolderOrInventUntrackedLocations() {
        Block egg = displayedEgg();
        store.observeEgg(faction, true, System.currentTimeMillis() - 1000);
        when(egg.getType()).thenReturn(Material.AIR);
        String message = help(outsider, "egg");
        assertTrue(message.contains("No faction is displaying the dragon egg"));
        assertTrue(message.contains("location is unknown while carried, stored, or placed elsewhere"));
        assertFalse(message.contains("X 0"));
        assertFalse(faction.dragonEgg);
    }

    @Test void incentiveQueriesSendOnlyPrivateRepliesToTheRequester() {
        recordKills(owner, 1);
        displayedEgg();
        clearInvocations(owner, elder, otherElder, member, outsider, server);
        for (String action : List.of("kills", "settlements", "egg")) run(outsider, action);
        verify(outsider, atLeast(3)).sendMessage(anyString());
        for (Player player : List.of(owner, elder, otherElder, member)) {
            verify(player, never()).sendMessage(anyString());
        }
        verify(server, never()).broadcastMessage(anyString());
        verify(server, never()).broadcast(any(net.kyori.adventure.text.Component.class));
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
