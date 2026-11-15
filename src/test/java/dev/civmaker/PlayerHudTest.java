package dev.civmaker;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerHudTest {
    @TempDir Path directory;

    @Test void leaderboardCutsAtTenCharacters() {
        assertEquals("Oak", PlayerHud.leaderboardName("Oak"));
        assertEquals("TenLetters", PlayerHud.leaderboardName("TenLetters"));
        assertEquals("Long Facti", PlayerHud.leaderboardName("Long Faction Name"));
    }

    @Test void renamingRefreshesTheExistingSidebarAndTerritoryBanner() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
        CivStore store = new CivStore(directory);
        UUID owner = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var village = new CivStore.Village("Original", owner, new CivStore.Position(world.getUID(), 0, 80, 0));
        store.addVillage(village);
        CivMaker plugin = mock(CivMaker.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 80, 0.5));
        when(player.playerListName()).thenReturn(Component.text("Alex"));
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        ScoreboardManager manager = mock(ScoreboardManager.class);
        when(server.getScoreboardManager()).thenReturn(manager);
        Scoreboard main = mock(Scoreboard.class);
        Scoreboard board = mock(Scoreboard.class);
        when(manager.getMainScoreboard()).thenReturn(main);
        when(manager.getNewScoreboard()).thenReturn(board);
        when(player.getScoreboard()).thenReturn(main);
        doAnswer(call -> { when(player.getScoreboard()).thenReturn(call.getArgument(0)); return null; })
                .when(player).setScoreboard(any(Scoreboard.class));
        Objective objective = mock(Objective.class);
        Criteria dummy = Criteria.DUMMY;
        when(board.registerNewObjective(anyString(), eq(dummy), any(Component.class))).thenReturn(objective);
        Map<String, Score> scores = new HashMap<>();
        when(objective.getScore(anyString())).thenAnswer(call -> scores.computeIfAbsent(call.getArgument(0), key -> mock(Score.class)));
        PlayerHud hud = new PlayerHud(plugin, store, mock(Wallets.class));
        hud.refresh();
        var bannerCaptor = org.mockito.ArgumentCaptor.forClass(BossBar.class);
        verify(player).showBossBar(bannerCaptor.capture());
        village.name = "Long Faction Name";
        hud.refresh();
        var textCaptor = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(scores.get("civmaker_village"), times(2)).customName(textCaptor.capture());
        assertEquals("Village: Long Faction Name", plain(textCaptor.getValue()));
        verify(scores.get("civmaker_rank_0"), times(2)).customName(textCaptor.capture());
        assertEquals("1. Long Facti 100 pts", plain(textCaptor.getValue()));
        assertTrue(plain(bannerCaptor.getValue().name()).contains("Long Faction Name"));
        assertEquals("Long Faction Name", village.name);
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
