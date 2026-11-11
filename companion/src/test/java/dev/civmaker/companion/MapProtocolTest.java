package dev.civmaker.companion;

import dev.civmaker.map.protocol.MapProtocol;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class MapProtocolTest {
    @Test void multiPacketSnapshotsPublishAtomicallyAndHandleDuplicateParts() throws Exception {
        byte[] input = new byte[100_001];
        new Random(42).nextBytes(input);
        var parts = MapProtocol.frames(input, 1);
        var assembler = new MapProtocol.Assembler();
        assertEquals(5, parts.size());
        assertNull(assembler.accept(parts.get(4), 0));
        assertNull(assembler.accept(parts.get(4), 1));
        for (int i = 0; i < 3; i++) { assertNull(assembler.accept(parts.get(i), 2)); }
        assertArrayEquals(input, assembler.accept(parts.get(3), 3));
    }

    @Test void worldSwitchAndTimedOutTransfersDoNotMixSnapshots() throws Exception {
        byte[] input = new byte[48_001];
        var oldParts = MapProtocol.frames(input, 1);
        byte[] updated = input.clone();
        Arrays.fill(updated, (byte) 7);
        var newParts = MapProtocol.frames(updated, 2);
        var assembler = new MapProtocol.Assembler();
        assertNull(assembler.accept(oldParts.get(0), 0));
        assertNull(assembler.accept(newParts.get(0), 1));
        assertNull(assembler.accept(newParts.get(1), 2));
        assertArrayEquals(updated, assembler.accept(newParts.get(2), 3));
        assertNull(assembler.accept(oldParts.get(0), 4));
        assertNull(assembler.accept(oldParts.get(1), MapProtocol.TIMEOUT_MILLIS + 5));
        assertNull(assembler.accept(oldParts.get(2), MapProtocol.TIMEOUT_MILLIS + 6));
        assembler.clear();
        assertNull(assembler.accept(oldParts.get(2), MapProtocol.TIMEOUT_MILLIS + 7));
    }

    @Test void rejectsMalformedAndOversizedFramesBeforeAllocation() throws Exception {
        var assembler = new MapProtocol.Assembler();
        assertThrows(Exception.class, () -> assembler.accept(new byte[0], 0));
        byte[] frame = MapProtocol.frames(new byte[10], 1).getFirst();
        ByteBuffer.wrap(frame).putInt(12, Integer.MAX_VALUE);
        assertThrows(Exception.class, () -> assembler.accept(frame, 0));
        byte[] truncated = Arrays.copyOf(MapProtocol.frames(new byte[10], 2).getFirst(), 25);
        assertThrows(Exception.class, () -> assembler.accept(truncated, 0));
        assertThrows(Exception.class, () -> MapProtocol.frames(new byte[MapProtocol.MAX_BYTES + 1], 1));
        assertTrue(MapProtocol.isHello(MapProtocol.hello()));
        assertFalse(MapProtocol.isHello(new byte[]{1}));
    }
}
