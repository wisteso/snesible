package jfc.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MirroringTest {

    private static final int MIB = 1024 * 1024;

    @Test
    void offsetsInsideTheMemoryAreUnchanged() {
        assertEquals(0x1234, Mirroring.mirror(0x1234, 2 * MIB));
    }

    @Test
    void powerOfTwoSizesWrapLikeModulo() {
        assertEquals(0x10, Mirroring.mirror(512 * 1024 + 0x10, 512 * 1024));
        assertEquals(0x10, Mirroring.mirror(3 * 512 * 1024 + 0x10, 512 * 1024));
    }

    @Test
    void threeMiBRomRepeatsItsLastMegabyteRatherThanItsStart() {
        // 3 MiB = a 2 MiB chip + a 1 MiB chip. The 3-4 MiB range shows the 1 MiB chip again.
        assertEquals(2 * MIB + 0x10, Mirroring.mirror(3 * MIB + 0x10, 3 * MIB));
    }
}
