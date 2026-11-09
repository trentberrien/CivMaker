package dev.civmaker;

import dev.civmaker.CivStore.RaidMode;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

final class PlayerHud implements Listener {
    private static final int RANK_COUNT = 5;
    private record Banner(Village village, int color, long balance, long points, BossBar bar) {}
    private record ListName(int color, Component original, Component applied) {}
    private record Standing(String name, int color, long points) {}

    private static final class Sidebar {
        final Scoreboard board;
        final Score villageLine;
        final Score walletLine;
        final Score raidLine;
        final Score[] ranks = new Score[RANK_COUNT];
        List<Standing> ranking = List.of();
        Village village;
        int color = -1;
        long balance = -1;
        Component raidStatus;

        Sidebar(ScoreboardManager manager) {
            board = manager.getNewScoreboard();
            var objective = board.registerNewObjective("civmaker_wallet", Criteria.DUMMY,
                    Component.text("CivMaker", NamedTextColor.GOLD));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            villageLine = line(objective, "civmaker_village", 3);
            walletLine = line(objective, "civmaker_balance", 2);
            raidLine = line(objective, "civmaker_raids", 1);
            line(objective, "civmaker_leaderboard", 0).customName(Component.text("Village Points", NamedTextColor.GOLD));
            line(objective, "civmaker_help", -RANK_COUNT - 1)
                    .customName(Component.text("/civ help", NamedTextColor.YELLOW));
            for (int i = 0; i < ranks.length; i++) {
                ranks[i] = objective.getScore("civmaker_rank_" + i);
            }
        }

        private static Score line(Objective objective, String entry, int order) {
            Score line = objective.getScore(entry);
            line.setScore(order);
            line.numberFormat(NumberFormat.blank());
            return line;
        }
    }

    private final CivMaker plugin;
    private final CivStore store;
    private final Wallets wallets;
    private final Map<UUID, Banner> banners = new HashMap<>();
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    private final Map<UUID, ListName> listNames = new HashMap<>();

    PlayerHud(CivMaker plugin, CivStore store, Wallets wallets) {
        this.plugin = plugin;
        this.store = store;
        this.wallets = wallets;
    }

    void refresh() {
        ScoreboardManager manager = plugin.getServer().getScoreboardManager();
        List<Standing> ranking = rankings();
        Component raidStatus = raidStatus();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Village membership = store.memberOf(player.getUniqueId());
            updateBanner(player, store.claimAt(player.getLocation()));
            updateListName(player, membership);
            if (manager != null) {
                updateSidebar(player, manager, membership, ranking, raidStatus);
            }
        }
    }

    private List<Standing> rankings() {
        return StreamSupport.stream(store.villages().spliterator(), false)
                .map(village -> new Standing(village.name, village.color, village.points()))
                .sorted(Comparator.comparingLong(Standing::points).reversed().thenComparing(Standing::name))
                .limit(RANK_COUNT).toList();
    }

    private Component raidStatus() {
        boolean active = store.activeWindow(System.currentTimeMillis()) != null;
        String status = store.raidMode() == RaidMode.SETTLEMENTS ? "Active (settlements)" : active ? "Active" : "Inactive";
        return Component.text("Raids: ", NamedTextColor.GRAY)
                .append(Component.text(status, active ? NamedTextColor.RED : NamedTextColor.GREEN));
    }

    private void updateBanner(Player player, Village village) {
        UUID id = player.getUniqueId();
        Banner banner = banners.get(id);
        long balance = village == null ? 0 : store.villageBalance(village);
        long points = village == null ? 0 : village.points();
        if (banner != null && banner.village() == village) {
            if (banner.color() != village.color || banner.balance() != balance || banner.points() != points) {
                banner.bar().name(bannerText(village, balance, points));
                banner.bar().color(VillageColors.bar(village.color));
                banners.put(id, new Banner(village, village.color, balance, points, banner.bar()));
            }
            return;
        }
        if (banner != null) {
            player.hideBossBar(banner.bar());
            banners.remove(id);
        }
        if (village != null) {
            BossBar bar = BossBar.bossBar(bannerText(village, balance, points),
                    1, VillageColors.bar(village.color), BossBar.Overlay.PROGRESS);
            banners.put(id, new Banner(village, village.color, balance, points, bar));
            player.showBossBar(bar);
        }
    }

    private static Component bannerText(Village village, long balance, long points) {
        return Component.text("You are in " + village.name + " • " + money(balance) + " • " + points + " pts",
                TextColor.color(village.color));
    }

    private static String money(long balance) {
        return String.format(Locale.US, "$%,d", balance);
    }

    private void updateListName(Player player, Village membership) {
        UUID id = player.getUniqueId();
        ListName previous = listNames.get(id);
        if (membership == null) {
            if (previous != null) {
                if (previous.applied().equals(player.playerListName())) {
                    player.playerListName(previous.original());
                }
                listNames.remove(id);
            }
            return;
        }
        if (previous != null && previous.color() == membership.color) {
            return;
        }

        Component current = player.playerListName();
        Component original = previous != null && previous.applied().equals(current) ? previous.original() : current;
        Component styled = (original == null ? Component.text(player.getName()) : original)
                .color(TextColor.color(membership.color));
        player.playerListName(styled);
        listNames.put(id, new ListName(membership.color, original, styled));
    }

    private void updateSidebar(Player player, ScoreboardManager manager, Village membership, List<Standing> ranking,
                               Component raidStatus) {
        UUID id = player.getUniqueId();
        Scoreboard current = player.getScoreboard();
        Scoreboard main = manager.getMainScoreboard();
        Sidebar sidebar = sidebars.get(id);

        if (sidebar != null && !sidebar.board.equals(current)) {
            sidebars.remove(id);
            sidebar = null;
        }
        if (sidebar == null) {
            if (!main.equals(current) || main.getObjective(DisplaySlot.SIDEBAR) != null) {
                return;
            }
            sidebar = new Sidebar(manager);
            sidebars.put(id, sidebar);
            player.setScoreboard(sidebar.board);
        }

        int color = membership == null ? 0 : membership.color;
        if (sidebar.village != membership || sidebar.color != color) {
            Component villageName = membership == null ? Component.text("None", NamedTextColor.WHITE)
                    : Component.text(membership.name, TextColor.color(color));
            sidebar.villageLine.customName(Component.text("Village: ", NamedTextColor.GRAY).append(villageName));
            sidebar.village = membership;
            sidebar.color = color;
        }

        long balance = wallets.balance(player);
        if (sidebar.balance != balance) {
            sidebar.walletLine.customName(Component.text("Wallet: ", NamedTextColor.GRAY)
                    .append(Component.text(money(balance), NamedTextColor.GREEN)));
            sidebar.balance = balance;
        }
        if (!raidStatus.equals(sidebar.raidStatus)) {
            sidebar.raidLine.customName(raidStatus);
            sidebar.raidStatus = raidStatus;
        }
        if (!sidebar.ranking.equals(ranking)) {
            for (int i = 0; i < ranking.size(); i++) {
                Standing entry = ranking.get(i);
                Score line = sidebar.ranks[i];
                line.setScore(-i - 1);
                line.numberFormat(NumberFormat.blank());
                line.customName(Component.text((i + 1) + ". ", NamedTextColor.GRAY)
                        .append(Component.text(entry.name(), TextColor.color(entry.color())))
                        .append(Component.text(" " + entry.points() + " pts", NamedTextColor.GREEN)));
            }
            for (int i = ranking.size(); i < sidebar.ranking.size(); i++) {
                sidebar.ranks[i].resetScore();
            }
            sidebar.ranking = ranking;
        }
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    void clear() {
        plugin.getServer().getOnlinePlayers().forEach(this::clear);
    }

    private void clear(Player player) {
        updateBanner(player, null);
        updateListName(player, null);
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        ScoreboardManager manager = plugin.getServer().getScoreboardManager();
        if (sidebar != null && manager != null && sidebar.board.equals(player.getScoreboard())) {
            player.setScoreboard(manager.getMainScoreboard());
        }
    }
}
