package dev.civmaker;

import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.RaidMode;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import dev.civmaker.CivStore.Window;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IncentiveRewardsTest {
    @TempDir Path directory;
    private CivStore store;
    private Village small;
    private Village large;

    @BeforeEach void setup() throws Exception {
        store = new CivStore(directory);
        UUID world = UUID.randomUUID();
        small = new Village("Small", UUID.randomUUID(), new Position(world, 0, 80, 0));
        large = new Village("Large", UUID.randomUUID(), new Position(world, 32, 80, 0));
        store.addVillage(small);
        store.addVillage(large);
        store.windows().add(new Window(100, 200));
    }

    private Settlement settlement(int x) {
        Position bell = new Position(small.beacon.world(), x, 80, 0);
        return new Settlement(null, bell, Set.of(bell.plot()));
    }

    @Test void settlementBonusRequiresPositiveCountSupportsTiesAndTransfersImmediately() {
        assertEquals(100, small.points());
        Settlement first = settlement(64);
        store.addSettlement(small, first);
        assertEquals(250, small.points());
        store.addSettlement(large, settlement(96));
        assertEquals(250, small.points());
        assertEquals(250, large.points());
        store.transferSettlement(first, small, large);
        assertEquals(100, small.points());
        assertEquals(350, large.points());
    }

    @Test void oneExceptionalPlayerOutranksALargerFactionsCombinedKillsAndTiesGiveOnlyOneBonus() {
        UUID teammate = UUID.randomUUID();
        store.addMember(large, teammate);
        for (int i = 0; i < 5; i++) store.recordRaidKill(small.owner, large.owner, 150);
        for (int i = 0; i < 3; i++) {
            store.recordRaidKill(large.owner, small.owner, 150);
            store.recordRaidKill(teammate, small.owner, 150);
        }
        assertEquals(150, small.points());
        assertEquals(100, large.points());
        for (int i = 0; i < 2; i++) store.recordRaidKill(teammate, small.owner, 150);
        assertEquals(150, large.points());
        store.removeMember(large, teammate);
        assertEquals(100, large.points());
        store.addMember(small, teammate);
        assertEquals(150, small.points());
    }

    @Test void onlyRaidKillsCountAndFriendlySelfAndShieldedKillsDoNotCount() {
        UUID teammate = UUID.randomUUID();
        store.addMember(small, teammate);
        store.recordRaidKill(small.owner, large.owner, 99);
        store.recordRaidKill(small.owner, large.owner, 200);
        store.recordRaidKill(small.owner, teammate, 150);
        store.recordRaidKill(small.owner, small.owner, 150);
        small.shieldWindow = 100L;
        store.recordRaidKill(small.owner, large.owner, 150);
        small.shieldWindow = null;
        large.shieldWindow = 100L;
        store.recordRaidKill(small.owner, large.owner, 150);
        large.shieldWindow = null;
        assertEquals(0, store.raidKills(small.owner));
        store.recordRaidKill(small.owner, large.owner, 150);
        assertEquals(1, store.raidKills(small.owner));
    }

    private void eggBeforeRaids() {
        store.observeEgg(small, true, 50);
        store.refreshEggRaids(50);
        store.windows().add(new Window(300, 400));
        store.windows().add(new Window(500, 600));
    }

    private void fullRaid(long start, long end) {
        store.refreshEggRaids(start);
        store.refreshEggRaids(end - 1);
        store.refreshEggRaids(end);
    }

    @Test void eggAdds250AndLocksOnlyAfterThreeCompletePeriodsAcrossRestart() throws Exception {
        eggBeforeRaids();
        assertEquals(350, small.points());
        fullRaid(100, 200);
        fullRaid(300, 400);
        assertEquals(2, small.eggDefendedRaids);
        assertFalse(small.eggProtected);
        store.refreshEggRaids(500);
        store.save();
        store = new CivStore(directory);
        small = store.village(small.id);
        store.observeEgg(small, true, 550);
        store.refreshEggRaids(550);
        store.refreshEggRaids(600);
        assertEquals(3, small.eggDefendedRaids);
        assertTrue(small.eggProtected);
        store.save();
        assertTrue(new CivStore(directory).village(small.id).eggProtected);
    }

    @Test void midRaidPlacementDoesNotCountAndRemovingAndReplacingResetsStreak() {
        eggBeforeRaids();
        fullRaid(100, 200);
        store.refreshEggRaids(300);
        store.observeEgg(small, false, 350);
        assertEquals(100, small.points());
        assertEquals(0, small.eggDefendedRaids);
        store.observeEgg(small, true, 351);
        store.refreshEggRaids(399);
        store.refreshEggRaids(400);
        assertEquals(0, small.eggDefendedRaids);
        fullRaid(500, 600);
        assertEquals(1, small.eggDefendedRaids);
    }

    @Test void missingAnEntireRaidDuringDowntimeBreaksTheStreak() {
        eggBeforeRaids();
        fullRaid(100, 200);
        store.refreshEggRaids(500);
        assertEquals(0, small.eggDefendedRaids);
        store.refreshEggRaids(599);
        store.refreshEggRaids(600);
        assertEquals(1, small.eggDefendedRaids);
    }

    @Test void restartingBetweenRaidsDoesNotHideAMissedPeriod() {
        eggBeforeRaids();
        fullRaid(100, 200);
        store.refreshEggRaids(450);
        assertEquals(0, small.eggDefendedRaids);
        fullRaid(500, 600);
        assertEquals(1, small.eggDefendedRaids);
    }

    @Test void shieldedPeriodsDoNotQualifyEvenIfProtectionIsEnabledMidRaid() {
        eggBeforeRaids();
        store.refreshEggRaids(100);
        small.shieldWindow = 100L;
        store.refreshEggRaids(150);
        store.refreshEggRaids(200);
        assertEquals(0, small.eggDefendedRaids);
    }

    @Test void manualRaidsCountOnceOnCloseAndSettlementOnlyRaidsDoNotQualify() {
        store.observeEgg(small, true, System.currentTimeMillis() - 1000);
        for (int i = 0; i < 2; i++) {
            store.setRaidMode(RaidMode.ON);
            store.setRaidMode(RaidMode.OFF);
        }
        assertEquals(2, small.eggDefendedRaids);
        assertFalse(small.eggProtected);
        store.setRaidMode(RaidMode.SETTLEMENTS);
        store.setRaidMode(RaidMode.OFF);
        assertEquals(0, small.eggDefendedRaids);
    }

    @Test void countersAndStandingBonusesSurviveSaveAndExistingDataDefaultsToZero() throws Exception {
        store.recordRaidKill(small.owner, large.owner, 150);
        store.observeEgg(small, true, 150);
        store.save();
        CivStore loaded = new CivStore(directory);
        assertEquals(1, loaded.raidKills(small.owner));
        assertEquals(400, loaded.village(small.id).points());
        assertEquals(100, loaded.village(large.id).points());
    }
}
