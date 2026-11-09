package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.RaidMode;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class CivCommands implements TabExecutor {
    private static final List<String> PLAYER_COMMANDS = List.of("help", "balance", "withdraw", "deposit",
            "create", "claim", "settlement", "points", "borders", "invite", "join", "leave", "disband", "info", "villages", "raids", "shop", "banner", "raidprotection");
    private static final long INVITE_DURATION = 10 * 60 * 1000;
    private static final long DISBAND_DURATION = 60_000;

    private record Invite(Village village, long expires) {}
    private record DisbandConfirmation(Village village, long expires) {}

    private final CivMaker plugin;
    private final CivStore store;
    private final Notes notes;
    private final Wallets wallets;
    private final ClaimBuying claimBuying;
    private final SettlementBuying settlementBuying;
    private final MeadowSearch meadowSearch;
    private final SettlementVaults settlementVaults;
    private final VillageBorders villageBorders;
    private final RaidShop raidShop;
    private final RaidShields shields;
    private final FoundingBeacons foundingBeacons;
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, DisbandConfirmation> disbandConfirmations = new HashMap<>();
    private final Map<CommandSender, DisbandConfirmation> adminDisbandConfirmations = new HashMap<>();

    CivCommands(CivMaker plugin, CivStore store, Notes notes, Wallets wallets, ClaimBuying claimBuying,
                SettlementBuying settlementBuying, NaturalVillages natural, VillageBorders villageBorders, RaidShop raidShop,
                SettlementVaults settlementVaults, RaidShields shields, FoundingBeacons foundingBeacons) {
        this.plugin = plugin;
        this.store = store;
        this.notes = notes;
        this.wallets = wallets;
        this.claimBuying = claimBuying;
        this.settlementBuying = settlementBuying;
        meadowSearch = new MeadowSearch(plugin, natural);
        this.settlementVaults = settlementVaults;
        this.villageBorders = villageBorders;
        this.raidShop = raidShop;
        this.shields = shields;
        this.foundingBeacons = foundingBeacons;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> help(sender);
                case "villages" -> villages(sender);
                case "points" -> points(sender);
                case "raids" -> raids(sender, args);
                case "raidprotection" -> shields.configure(sender, args, isAdmin(sender));
                case "raidmode" -> raidMode(sender, args);
                case "testdeposit" -> testDeposit(sender, args);
                case "locatemeadow" -> {
                    require(isAdmin(sender), "Only a CivMaker admin can locate Meadow villages.");
                    require(sender instanceof Player && args.length == 1, "In game, use /civ locatemeadow.");
                    meadowSearch.find((Player) sender);
                }
                case "allegiance" -> allegiance(sender, args);
                case "schedule" -> schedule(sender, args);
                case "cancelraid" -> cancelRaid(sender, args);
                case "admin" -> changeAdmin(sender, args);
                case "disbandvillage" -> adminDisband(sender, args);
                case "setbalance" -> setBalance(sender, args);
                case "treasury" -> sender.sendMessage("Banknotes in Secure Vaults now count toward village wealth.");
                case "raid" -> sender.sendMessage("Use lockpicks to raid vaults or hold an enemy settlement bell to capture it during a raid.");
                default -> {
                    require(sender instanceof Player, "Only a player can use this command.");
                    Player player = (Player) sender;
                    UUID id = player.getUniqueId();
                    Village village = store.memberOf(id);
                    switch (action) {
                        case "balance" -> player.sendMessage("Wallet: $" + wallets.balance(player));
                        case "borders" -> {
                            require(args.length == 1, "Use /civ borders to toggle the village border display.");
                            villageBorders.toggle(player);
                        }
                        case "info" -> info(player, village);
                        case "leave" -> leave(player, args);
                        case "disband" -> disband(player, args);
                        case "banner" -> setBanner(player, args);
                        case "withdraw", "deposit", "create", "claim", "settlement", "invite", "join", "shop" -> {
                            require(player.getGameMode() == GameMode.SURVIVAL, "Switch to Survival to use this command.");
                            switch (action) {
                                case "withdraw" -> withdraw(player, amount(args, true));
                                case "deposit" -> deposit(player, amount(args, false));
                                case "create" -> {
                                    require(args.length == 1, "Use /civ create to buy a Founding Beacon. Name your civilization when you place it.");
                                    foundingBeacons.buy(player);
                                }
                                case "claim" -> claimBuying.command(player, owned(player, village), args);
                                case "settlement" -> settlementBuying.command(player, owned(player, village), args);
                                case "invite" -> invite(player, owned(player, village), args);
                                case "join" -> join(player, village);
                                case "shop" -> raidShop.open(player);
                            }
                        }
                        default -> help(sender);
                    }
                }
            }
        } catch (IllegalArgumentException error) {
            sender.sendMessage(error.getMessage());
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("/civ balance | withdraw <notes> | deposit [notes]");
        sender.sendMessage("/civ create buys a Founding Beacon for " + FoundingBeacons.PRICE_LABEL + ". Place it to start a civilization.");
        sender.sendMessage("/civ claim | info | villages");
        sender.sendMessage("/civ claim mode | claim undo | borders");
        sender.sendMessage("/civ settlement [buy|vault] | points");
        sender.sendMessage("/civ invite <player> | join | leave");
        sender.sendMessage("/civ disband");
        sender.sendMessage("/civ raids | shop");
        sender.sendMessage("/civ banner set (owner, hold a banner in your main hand)");
        if (isAdmin(sender)) {
            sender.sendMessage("/civ schedule <start ISO-8601> <end ISO-8601> | cancelraid <start ISO-8601>");
            sender.sendMessage("/civ setbalance <online player> <amount>");
            sender.sendMessage("/civ raids <on|off|auto|toggle> | allegiance [village name|--outsider]");
            sender.sendMessage("/civ raidmode <settlements|all|none|toggle>");
            sender.sendMessage("/civ schedule nightly <HH:mm> <HH:mm> [time zone] | nightly off");
            sender.sendMessage("/civ raidprotection <on|off|price amount|cooldown hours>");
            sender.sendMessage("/civ testdeposit (look at a Settlement Vault)");
            sender.sendMessage("/civ locatemeadow");
            sender.sendMessage("/civ disbandvillage <village name>");
        }
        if (sender.hasPermission("civmaker.admin.manage")) {
            sender.sendMessage("/civ admin <add|remove> <player name or UUID>");
        }
    }

    private void setBanner(Player player, String[] args) {
        require(args.length == 2 && args[1].equalsIgnoreCase("set"),
                "Hold a banner in your main hand and use /civ banner set.");
        var village = store.registeredVillage(player.getUniqueId());
        require(village != null && village.owner.equals(player.getUniqueId()),
                "Only a faction owner can set their faction banner.");
        village.banner = FactionBanner.copyOf(player.getInventory().getItemInMainHand());
        store.dirty = true;
        player.sendMessage("Set the faction banner for " + village.name + ".");
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("civmaker.admin")
                || (sender instanceof Player player && store.isAdmin(player.getUniqueId()));
    }

    private void testDeposit(CommandSender sender, String[] args) {
        require(isAdmin(sender), "Only a CivMaker admin can schedule a test deposit.");
        require(sender instanceof Player && args.length == 1, "In game, look at a Settlement Vault and use /civ testdeposit.");
        settlementVaults.testDeposit((Player) sender);
    }

    private void allegiance(CommandSender sender, String[] args) {
        require(isAdmin(sender), "Only a CivMaker admin can change testing allegiance.");
        require(sender instanceof Player, "Only a player can change their allegiance.");
        Player player = (Player) sender;
        UUID id = player.getUniqueId();
        if (args.length == 1) {
            store.clearAllegiance(id);
            sender.sendMessage("Testing allegiance cleared.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("--outsider")) {
            store.setAllegiance(id, null);
            sender.sendMessage("Testing allegiance set to outsider.");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        for (Village village : store.villages()) {
            if (village.name.equalsIgnoreCase(name)) {
                store.setAllegiance(id, village);
                sender.sendMessage("Testing allegiance set to " + village.name + ".");
                return;
            }
        }
        sender.sendMessage("No village named " + name + ".");
    }

    private void changeAdmin(CommandSender sender, String[] args) {
        require(sender.hasPermission("civmaker.admin.manage"), "Only a server operator can manage CivMaker admins.");
        require(args.length == 3 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove")),
                "Use /civ admin <add|remove> <player name or UUID>.");
        OfflinePlayer target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            target = plugin.getServer().getOfflinePlayerIfCached(args[2]);
        }
        UUID id;
        try {
            id = target == null ? UUID.fromString(args[2]) : target.getUniqueId();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown player. Use an online player's name or their UUID.");
        }
        String name = target == null || target.getName() == null ? id.toString() : target.getName();
        boolean grant = args[1].equalsIgnoreCase("add");
        require(store.setAdmin(id, grant), name + (grant ? " already has" : " does not have") + " the CivMaker admin role.");
        sender.sendMessage("CivMaker admin role " + (grant ? "granted to " : "removed from ") + name + ".");
    }

    private void setBalance(CommandSender sender, String[] args) {
        require(isAdmin(sender), "Only a CivMaker admin can adjust wallets.");
        require(args.length == 3, "Use /civ setbalance <online player> <amount>.");
        Player target = plugin.getServer().getPlayerExact(args[1]);
        require(target != null, "That player is not online.");
        long balance;
        try {
            balance = Long.parseLong(args[2]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter a whole number from 0 to " + Long.MAX_VALUE + ".");
        }
        require(balance >= 0, "Wallet balances must be zero or greater.");
        long previous = wallets.balance(target);
        wallets.setBalance(target, balance);
        sender.sendMessage(target.getName() + "'s wallet changed from $" + previous + " to $" + balance + ".");
        if (!target.equals(sender)) {
            target.sendMessage("Your wallet balance was set to $" + balance + " by " + sender.getName() + ".");
        }
    }

    private void withdraw(Player player, int amount) {
        long cost = Notes.VALUE * amount;
        long balance = wallets.balance(player);
        require(balance >= cost, "Your wallet needs $" + cost + ".");
        require(notes.capacity(player.getInventory()) >= amount, "Free up enough inventory space first.");
        notes.give(player.getInventory(), amount);
        wallets.setBalance(player, balance - cost);
        player.sendMessage("Withdrew " + amount + " banknote(s) worth $" + cost + ".");
    }

    private void deposit(Player player, int amount) {
        int available = notes.count(player.getInventory());
        if (amount == 0) {
            amount = available;
        }
        require(amount > 0 && amount <= available, "You don't have enough banknotes in your inventory.");
        long value = notes.value(player.getInventory(), amount);
        long balance = wallets.balance(player);
        require(value < Long.MAX_VALUE && balance <= Long.MAX_VALUE - value, "Your wallet cannot hold this deposit.");
        notes.remove(player.getInventory(), amount);
        wallets.setBalance(player, balance + value);
        player.sendMessage("Deposited $" + value + " into your wallet.");
    }

    private void invite(Player player, Village village, String[] args) {
        require(args.length == 2, "Use /civ invite <online player>.");
        Player invited = plugin.getServer().getPlayerExact(args[1]);
        require(invited != null, "That player is not online.");
        UUID id = invited.getUniqueId();
        require(store.memberOf(id) == null && store.registeredVillage(id) == null,
                "That player already belongs to a village.");
        long now = System.currentTimeMillis();
        invites.values().removeIf(invite -> invite.expires() <= now);
        invites.put(id, new Invite(village, now + INVITE_DURATION));
        invited.sendMessage(player.getName() + " invited you to " + village.name + ". Use /civ join within 10 minutes.");
        player.sendMessage("Invitation sent to " + invited.getName() + ".");
    }

    private void join(Player player, Village current) {
        UUID id = player.getUniqueId();
        require(current == null && store.registeredVillage(id) == null, "You already belong to a village.");
        Invite invite = invites.remove(id);
        require(invite != null && invite.expires() > System.currentTimeMillis(), "You don't have an active village invite.");
        store.addMember(invite.village(), id);
        player.sendMessage("You joined " + invite.village().name + ".");
    }

    private void leave(Player player, String[] args) {
        require(args.length == 1, "Use /civ leave to leave your civilization.");
        UUID id = player.getUniqueId();
        Village village = store.registeredVillage(id);
        require(village != null, "You don't belong to a civilization.");
        require(!village.owner.equals(id), "Civilization owners can use /civ disband to release their civilization.");
        store.removeMember(village, id);
        invites.remove(id);
        player.sendMessage("You left " + village.name + ". You can buy a Founding Beacon in /civ shop or with /civ create.");
    }

    private void disband(Player player, String[] args) {
        UUID id = player.getUniqueId();
        Village village = owned(player, store.registeredVillage(id));
        long now = System.currentTimeMillis();
        if (args.length == 1) {
            disbandConfirmations.values().removeIf(confirmation -> confirmation.expires() <= now);
            disbandConfirmations.put(id, new DisbandConfirmation(village, now + DISBAND_DURATION));
            showDisbandPreview(player, village, "/civ disband confirm");
            return;
        }
        require(args.length == 2 && args[1].equalsIgnoreCase("confirm"), "Use /civ disband.");
        DisbandConfirmation confirmation = disbandConfirmations.remove(id);
        require(confirmation != null && confirmation.expires() > now && confirmation.village() == village,
                "Run /civ disband first to review and confirm.");

        disbandVillage(player, village);
    }

    private void adminDisband(CommandSender sender, String[] args) {
        require(isAdmin(sender), "Only a CivMaker admin can disband another village.");
        require(args.length >= 2, "Use /civ disbandvillage <village name>.");
        long now = System.currentTimeMillis();
        if (args.length == 2 && args[1].equalsIgnoreCase("--confirm")) {
            DisbandConfirmation confirmation = adminDisbandConfirmations.remove(sender);
            require(confirmation != null && confirmation.expires() > now && store.hasVillage(confirmation.village().id),
                    "Run /civ disbandvillage <village name> first to review and confirm.");
            disbandVillage(sender, confirmation.village());
            return;
        }

        adminDisbandConfirmations.remove(sender);
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        for (Village village : store.villages()) {
            if (village.name.equalsIgnoreCase(name)) {
                adminDisbandConfirmations.values().removeIf(confirmation -> confirmation.expires() <= now);
                adminDisbandConfirmations.put(sender, new DisbandConfirmation(village, now + DISBAND_DURATION));
                showDisbandPreview(sender, village, "/civ disbandvillage --confirm");
                return;
            }
        }
        sender.sendMessage("No village named " + name + ".");
    }

    private static void showDisbandPreview(CommandSender sender, Village village, String confirmCommand) {
        sender.sendMessage("Disbanding " + village.name + " releases all " + village.plots.size()
                + " claimed chunks and removes all memberships. Land purchases are final.");
        sender.sendMessage("Run " + confirmCommand + " within 60 seconds to disband.");
    }

    private void disbandVillage(CommandSender sender, Village village) {
        store.disband(village);
        disbandConfirmations.remove(village.owner);
        adminDisbandConfirmations.values().removeIf(confirmation -> confirmation.village() == village);
        invites.values().removeIf(invite -> invite.village() == village);
        claimBuying.stop(village.owner);
        sender.sendMessage("Disbanded " + village.name + ". Its land is now unclaimed.");
        for (UUID member : village.members) {
            Player online = plugin.getServer().getPlayer(member);
            if (online != null && !online.equals(sender)) {
                online.sendMessage(sender.getName() + " disbanded " + village.name + ". Its land is now unclaimed.");
            }
        }
    }

    private void info(Player player, Village memberOf) {
        Village village = store.claimAt(player.getLocation().getBlock());
        if (village == null) {
            village = memberOf;
        }
        require(village != null, "This chunk is unclaimed and you don't belong to a village.");
        player.sendMessage(summary(village));
        player.sendMessage("Capital expansion: " + village.capitalExpansionCount() + "/"
                + CivStore.CAPITAL_EXPANSION_LIMIT + " chunks.");
    }

    private void villages(CommandSender sender) {
        boolean found = false;
        for (Village village : store.villages()) {
            sender.sendMessage(summary(village));
            found = true;
        }
        if (!found) {
            sender.sendMessage("No villages have been founded yet.");
        }
    }

    private void points(CommandSender sender) {
        var leaders = StreamSupport.stream(store.villages().spliterator(), false)
                .sorted(Comparator.comparingLong(Village::points).reversed().thenComparing(village -> village.name))
                .limit(10).toList();
        if (leaders.isEmpty()) {
            sender.sendMessage("No villages have been founded yet.");
            return;
        }
        sender.sendMessage("Village Points");
        for (int i = 0; i < leaders.size(); i++) {
            Village village = leaders.get(i);
            sender.sendMessage((i + 1) + ". " + village.name + " — " + village.points() + " points");
        }
    }

    private String summary(Village village) {
        Position beacon = village.beacon;
        World world = plugin.getServer().getWorld(beacon.world());
        String worldName = world == null ? beacon.world().toString() : world.getName();
        return village.name + " | " + village.points() + " pts | " + village.settlements.size()
                + " settlements | " + String.format(Locale.US, "$%,d", store.villageBalance(village))
                + " | " + village.plots.size() + " chunks | " + village.members.size()
                + " members | beacon " + worldName + " " + beacon.x() + " " + beacon.y() + " " + beacon.z();
    }

    private void raids(CommandSender sender, String[] args) {
        long now = System.currentTimeMillis();
        if (args.length > 1) {
            require(isAdmin(sender), "Only a CivMaker admin can change raid mode.");
            require(args.length == 2, "Use /civ raids <on|off|auto|toggle>.");
            RaidMode mode = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "on" -> RaidMode.ON;
                case "off" -> RaidMode.OFF;
                case "auto" -> RaidMode.AUTO;
                case "toggle" -> store.activeWindow(now) == null ? RaidMode.ON : RaidMode.OFF;
                default -> throw new IllegalArgumentException("Use /civ raids <on|off|auto|toggle>.");
            };
            store.setRaidMode(mode);
            sender.sendMessage("Raid mode set to " + mode.name().toLowerCase(Locale.ROOT) + ".");
            return;
        }
        sender.sendMessage(switch (store.raidMode()) {
            case ON -> "Raids are enabled manually.";
            case OFF -> "Raids are disabled manually.";
            case AUTO -> "Raids follow the schedule.";
            case SETTLEMENTS -> "Raids are enabled in settlements only.";
        });
        NightlyRaids nightly = store.nightly();
        if (nightly != null) {
            sender.sendMessage("Nightly: " + nightly.start() + " to " + nightly.end() + " (" + nightly.zone() + ")");
        }
        var windows = store.upcomingWindows(now, 3);
        if (windows.isEmpty()) {
            sender.sendMessage("No raid windows are scheduled.");
        }
        for (Window window : windows) {
            sender.sendMessage((window.contains(now) && store.raidMode() == RaidMode.AUTO ? "Active: " : "Scheduled: ")
                    + Instant.ofEpochMilli(window.start()) + " to " + Instant.ofEpochMilli(window.end()));
        }
    }

    private void raidMode(CommandSender sender, String[] args) {
        require(isAdmin(sender), "Only a CivMaker admin can change raid mode.");
        String usage = "Use /civ raidmode <settlements|all|none|toggle>.";
        require(args.length == 2, usage);
        RaidMode mode = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "settlements" -> RaidMode.SETTLEMENTS;
            case "all" -> RaidMode.ON;
            case "none" -> RaidMode.OFF;
            case "toggle" -> switch (store.raidMode()) {
                case OFF, AUTO -> RaidMode.SETTLEMENTS;
                case SETTLEMENTS -> RaidMode.ON;
                case ON -> RaidMode.OFF;
            };
            default -> throw new IllegalArgumentException(usage);
        };
        store.setRaidMode(mode);
        sender.sendMessage(switch (mode) {
            case SETTLEMENTS -> "Raids are enabled in settlements only.";
            case ON -> "Raids are enabled in all claims.";
            case OFF -> "Raids are disabled.";
            case AUTO -> "Raids follow the schedule.";
        });
    }

    private void schedule(CommandSender sender, String[] args) {
        require(isAdmin(sender), "Only an admin can schedule raids.");
        if (args.length >= 2 && args[1].equalsIgnoreCase("nightly")) {
            scheduleNightly(sender, args);
            return;
        }
        require(args.length == 3, "Use /civ schedule <start ISO-8601> <end ISO-8601> or /civ schedule nightly.");
        long start = instant(args[1]);
        long end = instant(args[2]);
        long now = System.currentTimeMillis();
        require(start >= now && start < end, "The raid must start in the future and end after it starts.");
        var windows = store.windows();
        require(windows.stream().noneMatch(window -> start < window.end() && end > window.start()),
                "Raid windows cannot overlap.");
        windows.add(new Window(start, end));
        windows.sort(Comparator.comparingLong(Window::start));
        store.dirty = true;
        sender.sendMessage("Raid scheduled from " + Instant.ofEpochMilli(start) + " to " + Instant.ofEpochMilli(end) + ".");
    }

    private void scheduleNightly(CommandSender sender, String[] args) {
        if (args.length == 3 && args[2].equalsIgnoreCase("off")) {
            store.setNightly(null);
            sender.sendMessage("Nightly raids removed from the schedule.");
            return;
        }
        require(args.length == 4 || args.length == 5,
                "Use /civ schedule nightly <HH:mm> <HH:mm> [time zone] or /civ schedule nightly off.");
        LocalTime start;
        LocalTime end;
        ZoneId zone;
        try {
            start = LocalTime.parse(args[2]);
            end = LocalTime.parse(args[3]);
            zone = args.length == 5 ? ZoneId.of(args[4]) : ZoneId.systemDefault();
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("Use 24-hour times and a time zone such as America/New_York.");
        }
        require(!start.equals(end), "Nightly start and end must be different.");
        store.setNightly(new NightlyRaids(start.toString(), end.toString(), zone.getId()));
        sender.sendMessage("Nightly raids scheduled from " + start + " to " + end + " (" + zone + "). Use /civ raids auto to follow the schedule.");
    }

    private void cancelRaid(CommandSender sender, String[] args) {
        require(isAdmin(sender), "Only an admin can cancel raids.");
        require(args.length == 2, "Use /civ cancelraid <start ISO-8601>.");
        long start = instant(args[1]);
        require(store.windows().removeIf(window -> window.start() == start && window.end() > System.currentTimeMillis()),
                "No current or upcoming raid window matches that start time.");
        store.dirty = true;
        sender.sendMessage("Raid window canceled.");
    }

    private static Village owned(Player player, Village village) {
        require(village != null && village.owner.equals(player.getUniqueId()), "Only a village owner can use this command.");
        return village;
    }

    private static int amount(String[] args, boolean required) {
        if (!required && args.length == 1) {
            return 0;
        }
        require(args.length == 2, "Specify a number of banknotes.");
        try {
            int amount = Integer.parseInt(args[1]);
            require(amount > 0, "The number of banknotes must be positive.");
            return amount;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter a positive whole number of banknotes.");
        }
    }

    private static long instant(String text) {
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeException | ArithmeticException error) {
            throw new IllegalArgumentException("Use an ISO-8601 time such as 2026-11-07T18:00:00Z.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        Stream<String> options = Stream.empty();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean manageAdmins = sender.hasPermission("civmaker.admin.manage");
        if (args.length == 1) {
            options = PLAYER_COMMANDS.stream();
            if (isAdmin(sender)) {
                options = Stream.concat(options, Stream.of("schedule", "cancelraid", "setbalance", "allegiance",
                        "disbandvillage", "raidmode", "testdeposit", "locatemeadow"));
            }
            if (manageAdmins) {
                options = Stream.concat(options, Stream.of("admin"));
            }
        } else if (args.length == 2 && action.equals("raids") && isAdmin(sender)) {
            options = Stream.of("on", "off", "auto", "toggle");
        } else if (args.length == 2 && action.equals("schedule") && isAdmin(sender)) {
            options = Stream.of("nightly");
        } else if (args.length == 3 && action.equals("schedule") && args[1].equalsIgnoreCase("nightly") && isAdmin(sender)) {
            options = Stream.of("off");
        } else if (args.length == 2 && action.equals("raidprotection") && isAdmin(sender)) {
            options = Stream.of("on", "off", "price", "cooldown");
        } else if (args.length == 2 && action.equals("raidmode") && isAdmin(sender)) {
            options = Stream.of("settlements", "all", "none", "toggle");
        } else if (args.length >= 2 && (action.equals("allegiance") || action.equals("disbandvillage")) && isAdmin(sender)) {
            String entered = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
            if (!entered.isEmpty()) {
                entered += " ";
            }
            String start = entered;
            options = StreamSupport.stream(store.villages().spliterator(), false)
                    .map(village -> village.name)
                    .filter(name -> name.regionMatches(true, 0, start, 0, start.length()))
                    .map(name -> name.substring(start.length()));
            if (args.length == 2 && action.equals("allegiance")) {
                options = Stream.concat(options, Stream.of("--outsider"));
            } else if (args.length == 2 && adminDisbandConfirmations.containsKey(sender)) {
                options = Stream.concat(options, Stream.of("--confirm"));
            }
        } else if (args.length == 2 && action.equals("claim")) {
            options = Stream.of("mode", "undo");
        } else if (args.length == 2 && action.equals("banner")) {
            options = Stream.of("set");
        } else if (args.length == 2 && action.equals("disband") && sender instanceof Player player
                && disbandConfirmations.containsKey(player.getUniqueId())) {
            options = Stream.of("confirm");
        } else if (args.length == 2 && action.equals("settlement")) {
            options = Stream.of("buy", "vault");
        } else if (args.length == 2 && action.equals("admin") && manageAdmins) {
            options = Stream.of("add", "remove");
        } else if ((args.length == 2 && (action.equals("invite") || (action.equals("setbalance") && isAdmin(sender))))
                || (args.length == 3 && action.equals("admin") && manageAdmins)) {
            options = plugin.getServer().getOnlinePlayers().stream().map(Player::getName);
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
