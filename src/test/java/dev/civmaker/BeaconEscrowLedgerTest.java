package dev.civmaker;

import dev.civmaker.BeaconEscrowLedger.Holding;
import dev.civmaker.BeaconEscrowLedger.Snapshot;
import dev.civmaker.BeaconEscrowLedger.Transfer;
import dev.civmaker.BeaconEscrowLedger.Offline;
import dev.civmaker.BeaconEscrowLedger.Option;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BeaconEscrowLedgerTest {
    @TempDir Path directory;
    private final CivStore.Position beacon = new CivStore.Position(UUID.randomUUID(), 2, 80, 3);
    private final CivStore.Village village = new CivStore.Village("Test", UUID.randomUUID(), beacon);

    private Transfer transfer(long after) {
        return new Transfer(UUID.randomUUID().toString(), after, new Snapshot("inventory", "cursor", "data"));
    }

    private void deposit(BeaconEscrowLedger ledger, UUID player, BigInteger amount) throws Exception {
        Transfer transfer = transfer(0);
        ledger.deposit(player, new Holding(village.id, beacon, amount), transfer);
        ledger.finish(player, transfer.id());
    }

    @Test void proportionalLossAndReturnsConserveEveryDollar() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), raider = UUID.randomUUID();
        deposit(ledger, first, BigInteger.valueOf(100));
        deposit(ledger, second, BigInteger.valueOf(300));
        var loot = transfer(100);
        ledger.loot(raider, village, 10, loot);
        assertEquals(BigInteger.valueOf(75), ledger.refundable(first));
        assertEquals(BigInteger.valueOf(225), ledger.refundable(second));
        assertEquals(BigInteger.valueOf(300), ledger.total(village));
        assertTrue(ledger.looted(beacon, 10));
        assertThrows(IllegalStateException.class, () -> ledger.loot(UUID.randomUUID(), village, 10, transfer(100)));
        ledger.finish(raider, loot.id());
        var refund = transfer(75);
        ledger.refund(first, 75, refund);
        ledger.finish(first, refund.id());
        assertEquals(BigInteger.ZERO, ledger.refundable(first));
        assertEquals(BigInteger.valueOf(225), ledger.total(village));
        assertEquals(BigInteger.valueOf(225), ledger.refundable(second));
    }

    @Test void pendingAndCompletedReceiptsSurviveRestart() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID player = UUID.randomUUID();
        var debit = transfer(0);
        ledger.deposit(player, new Holding(village.id, beacon, BigInteger.valueOf(400)), debit);
        ledger = new BeaconEscrowLedger(directory);
        assertEquals(debit, ledger.pending(player));
        assertEquals(debit, ledger.lastTransfer(player));
        assertEquals(BigInteger.valueOf(400), ledger.total(village));
        var persisted = ledger;
        assertThrows(IllegalStateException.class, () -> persisted.deposit(player,
                new Holding(village.id, beacon, BigInteger.valueOf(400)), transfer(0)));
        ledger.finish(player, debit.id());
        var refund = transfer(400);
        ledger.refund(player, 400, refund);
        ledger = new BeaconEscrowLedger(directory);
        assertEquals(BigInteger.ZERO, ledger.total(village));
        assertEquals(BigInteger.ZERO, ledger.refundable(player));
        assertEquals(refund, ledger.pending(player));
        ledger.finish(player, refund.id());
        ledger = new BeaconEscrowLedger(directory);
        assertNull(ledger.pending(player));
        assertEquals(refund, ledger.lastTransfer(player));
        assertEquals(BigInteger.ZERO, ledger.refundable(player));
    }

    @Test void raidMarkerAndLootPayoutCommitTogetherEvenAcrossRestart() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID player = UUID.randomUUID(), raider = UUID.randomUUID();
        deposit(ledger, player, BigInteger.valueOf(400));
        Transfer loot = transfer(100);
        ledger.loot(raider, village, -1, loot);
        ledger = new BeaconEscrowLedger(directory);
        assertTrue(ledger.looted(beacon, -1));
        assertEquals(BigInteger.valueOf(300), ledger.total(village));
        assertEquals(loot, ledger.pending(raider));
        assertFalse(ledger.looted(beacon, -2));
        ledger.finish(raider, loot.id());
        ledger.loot(raider, village, -2, transfer(175));
        assertEquals(BigInteger.valueOf(225), ledger.refundable(player));
    }

    @Test void lateDepositAndEarlyReturnDoNotRedistributeLosses() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID(), raider = UUID.randomUUID();
        deposit(ledger, first, BigInteger.valueOf(100));
        deposit(ledger, second, BigInteger.valueOf(300));
        var refund = transfer(100);
        ledger.refund(first, 100, refund);
        ledger.finish(first, refund.id());
        var loot = transfer(75);
        ledger.loot(raider, village, 10, loot);
        ledger.finish(raider, loot.id());
        deposit(ledger, third, BigInteger.valueOf(200));
        assertEquals(BigInteger.ZERO, ledger.refundable(first));
        assertEquals(BigInteger.valueOf(225), ledger.refundable(second));
        assertEquals(BigInteger.valueOf(200), ledger.refundable(third));
        assertThrows(IllegalStateException.class, () -> ledger.loot(raider, village, 10, transfer(1)));
    }

    @Test void roundingAlwaysConservesFundsWithHugeTotals() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        Random random = new Random(42);
        var contributors = new ArrayList<UUID>();
        BigInteger original = BigInteger.ZERO;
        for (int index = 0; index < 30; index++) {
            UUID player = UUID.randomUUID();
            contributors.add(player);
            BigInteger amount = new BigInteger(110, random).add(BigInteger.ONE);
            deposit(ledger, player, amount);
            original = original.add(amount);
        }
        UUID raider = UUID.randomUUID();
        BigInteger previous = original;
        for (int raid = 1; raid <= 10; raid++) {
            BigInteger loot = previous.divide(BigInteger.valueOf(4));
            var transfer = transfer(0);
            ledger.loot(raider, village, raid, transfer);
            ledger.finish(raider, transfer.id());
            BigInteger remaining = contributors.stream().map(ledger::refundable).reduce(BigInteger.ZERO, BigInteger::add);
            assertEquals(previous.subtract(loot), remaining);
            for (UUID player : contributors) assertTrue(ledger.refundable(player).signum() >= 0);
            previous = remaining;
        }
        assertEquals(previous, new BeaconEscrowLedger(directory).total(village));
    }

    @Test void fullWalletRefundIsReservedOutsideBeaconAndDoesNotOverflow() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID player = UUID.randomUUID();
        BigInteger huge = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        deposit(ledger, player, huge);
        var refund = transfer(Long.MAX_VALUE);
        ledger.refund(player, Long.MAX_VALUE, refund);
        ledger.finish(player, refund.id());
        ledger = new BeaconEscrowLedger(directory);
        assertNull(ledger.holding(player));
        assertEquals(BigInteger.ZERO, ledger.total(village));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), ledger.refundable(player));
    }

    @Test void disbandDoesNotEraseClaimsOrResetPhysicalBeaconRaidLimit() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID player = UUID.randomUUID(), raider = UUID.randomUUID();
        deposit(ledger, player, BigInteger.valueOf(400));
        var loot = transfer(100);
        ledger.loot(raider, village, 10, loot);
        ledger.finish(raider, loot.id());
        var newFaction = new CivStore.Village("Refounded", UUID.randomUUID(), beacon);
        assertEquals(BigInteger.ZERO, ledger.total(newFaction));
        assertEquals(BigInteger.valueOf(300), ledger.refundable(player));
        assertTrue(ledger.looted(newFaction.beacon, 10));
    }

    @Test void failedWriteLeavesBothMemoryAndDiskUnchanged() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID player = UUID.randomUUID();
        deposit(ledger, player, BigInteger.valueOf(400));
        String before = Files.readString(directory.resolve("beacon-escrow.json"));
        Files.createDirectory(directory.resolve("beacon-escrow.json.tmp"));
        assertThrows(IOException.class, () -> ledger.loot(UUID.randomUUID(), village, 10, transfer(100)));
        assertEquals(BigInteger.valueOf(400), ledger.total(village));
        assertFalse(ledger.looted(beacon, 10));
        assertEquals(before, Files.readString(directory.resolve("beacon-escrow.json")));
    }

    @Test void corruptFileIsRejectedInsteadOfResettingMoney() throws Exception {
        Files.writeString(directory.resolve("beacon-escrow.json"), "{broken");
        assertThrows(IOException.class, () -> new BeaconEscrowLedger(directory));
    }

    @Test void eachOptionHasItsOwnDurableAllowanceAcrossAttackersAndRefounding() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID victim = UUID.randomUUID();
        deposit(ledger, victim, BigInteger.valueOf(400));
        ledger.claimItems(village, 10, Option.INVENTORY);
        assertFalse(ledger.looted(beacon, 10, Option.ENDER_CHEST));
        assertFalse(ledger.looted(beacon, 10, Option.MONEY));
        ledger.claimItems(village, 10, Option.ENDER_CHEST);
        ledger.loot(UUID.randomUUID(), village, 10, transfer(100));
        var reloaded = new BeaconEscrowLedger(directory);
        var refounded = new CivStore.Village("Refounded", UUID.randomUUID(), beacon);
        for (Option option : Option.values()) {
            assertTrue(reloaded.looted(refounded.beacon, 10, option));
            assertFalse(reloaded.looted(beacon, 11, option));
        }
        assertThrows(IllegalStateException.class, () -> reloaded.claimItems(refounded, 10, Option.INVENTORY));
        reloaded.claimItems(village, 11, Option.INVENTORY);
    }

    @Test void itemRemovalAndDeliveryRecoverTogetherAndLaterStacksKeepCumulativeRemainder() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID victim = UUID.randomUUID(), raider = UUID.randomUUID();
        Transfer before = new Transfer("before", 0, new Snapshot("full", "cursor", "data", "ender"));
        Offline offline = new Offline(village.id, beacon, "Victim", before);
        ledger.departing(victim, offline);
        Transfer after = new Transfer("after", 0, new Snapshot("less", "cursor", "data", "ender"));
        Transfer delivery = transfer(0);
        ledger.takeItems(victim, offline, after, raider, delivery);
        ledger = new BeaconEscrowLedger(directory);
        assertEquals(after, ledger.pending(victim));
        assertEquals(delivery, ledger.pending(raider));
        assertEquals(after, ledger.offline(victim).contents());
        ledger.finish(raider, delivery.id());
        Transfer last = new Transfer("last", 0, new Snapshot("empty", "cursor", "data", "less ender"));
        ledger.takeItems(victim, ledger.offline(victim), last, raider, transfer(0));
        ledger = new BeaconEscrowLedger(directory);
        assertEquals(last, ledger.pending(victim));
        ledger.finish(victim, last.id());
        ledger.returning(victim);
        assertNull(new BeaconEscrowLedger(directory).offline(victim));
        assertEquals(last, new BeaconEscrowLedger(directory).lastTransfer(victim));
    }

    @Test void failedItemTransferChangesNeitherPlayerAndLegacyLedgerLoads() throws Exception {
        var ledger = new BeaconEscrowLedger(directory);
        UUID victim = UUID.randomUUID(), raider = UUID.randomUUID();
        Transfer snapshot = new Transfer("before", 0, new Snapshot("full", "cursor", "data", "ender"));
        Offline offline = new Offline(village.id, beacon, "Victim", snapshot);
        ledger.departing(victim, offline);
        Files.createDirectory(directory.resolve("beacon-escrow.json.tmp"));
        assertThrows(IOException.class, () -> ledger.takeItems(victim, offline, transfer(0), raider, transfer(0)));
        assertEquals(offline, ledger.offline(victim));
        assertNull(ledger.pending(raider));
        assertNull(ledger.pending(victim));
        Files.writeString(directory.resolve("beacon-escrow.json"), "{\"version\":1,\"holdings\":{},\"credits\":{},\"pending\":{},\"receipts\":{},\"looted\":{}}");
        assertTrue(new BeaconEscrowLedger(directory).offlinePlayers().isEmpty());
    }
}
