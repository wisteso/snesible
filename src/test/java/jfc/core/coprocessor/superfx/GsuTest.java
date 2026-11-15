package jfc.core.coprocessor.superfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs tiny hand-assembled GSU programs and checks registers, flags and RAM.
 *
 * <p>There's no public test suite for the GSU like the SingleStepTests for the 65816 and SPC700,
 * so these cover the behaviors that are easiest to get wrong: the branch delay slot, the prefix
 * instructions, the odd/even byte order of RAM words, the multiplies, and pixel plotting.
 * Each program starts at $0000 and ends with STOP followed by a NOP (STOP's delay slot).
 */
class GsuTest {

    // A few opcodes, named for readability.
    private static final int STOP = 0x00;
    private static final int NOP = 0x01;
    private static final int ALT1 = 0x3D;
    private static final int ALT2 = 0x3E;
    private static final int ALT3 = 0x3F;
    private static final int LOOP = 0x3C;
    private static final int PLOT = 0x4C;
    private static final int COLOR = 0x4E;
    private static final int MERGE = 0x70;
    private static final int FMULT = 0x9F;
    private static final int SBK = 0x90;
    private static final int GETB = 0xEF;

    private final byte[] code = new byte[0x10000];
    private final byte[] ram = new byte[0x20000];
    private int interrupts;
    private Gsu gsu;

    @BeforeEach
    void setUp() {
        GsuBus bus = new GsuBus() {
            @Override
            public int read(int address) {
                int bank = (address >>> 16) & 0x7F;
                return bank >= 0x60
                        ? ram[(bank & 1) << 16 | (address & 0xFFFF)] & 0xFF
                        : code[address & 0xFFFF] & 0xFF;
            }

            @Override
            public void write(int address, int value) {
                ram[((address >>> 16) & 1) << 16 | (address & 0xFFFF)] = (byte) value;
            }
        };
        gsu = new Gsu(bus, () -> interrupts++);
        gsu.reset();
    }

    // ---- Control flow -------------------------------------------------------------------------

    @Test
    void addsTwoRegistersIntoAThird() {
        run(iwt(1, 0x1234), iwt(2, 0x1111), op(0xB1), op(0x13), op(0x52)); // FROM R1, TO R3, ADD R2

        assertEquals(0x2345, gsu.r[3]);
        assertEquals(0x0000, gsu.r[0], "R0 is untouched once FROM/TO redirect the operands");
    }

    @Test
    void theInstructionAfterABranchRunsEvenWhenTheBranchIsTaken() {
        // $00 IBT R0,#0   $02 BRA +2   $04 INC R0 (delay slot)   $05 INC R0 (skipped)   $06 STOP
        run(op(0xA0, 0x00), op(0x05, 0x02), op(0xD0), op(0xD0));

        assertEquals(1, gsu.r[0]);
    }

    @Test
    void loopRepeatsUntilR12ReachesZero() {
        // $00 IWT R12,#5   $03 IWT R13,#$0006   $06 INC R1   $07 LOOP   $08 NOP   $09 STOP
        run(iwt(12, 5), iwt(13, 0x0006), op(0xD1), op(LOOP), op(NOP));

        assertEquals(5, gsu.r[1]);
        assertEquals(0, gsu.r[12]);
        assertTrue(gsu.zero);
    }

    @Test
    void linkThenJumpReturnsAfterTheCall() {
        // $00 LINK #4: R11 = $05   $01 IWT R15,#$0008   $04 NOP (delay)   $05 STOP ...
        // $08 INC R2   $09 JMP R11   $0A NOP (delay)   -> back at $05: STOP
        run(op(0x94), iwt(15, 0x0008), op(NOP), op(STOP), op(NOP), op(NOP), op(0xD2), op(0x9B), op(NOP));

        assertEquals(1, gsu.r[2]);
        assertEquals(0x0005, gsu.r[11]);
        assertFalse(gsu.running);
    }

    @Test
    void withFollowedByToIsAMove() {
        run(iwt(1, 0xBEEF), op(0x21), op(0x12)); // WITH R1, TO R2 = MOVE R2,R1

        assertEquals(0xBEEF, gsu.r[2]);
        assertEquals(0xBEEF, gsu.r[1]);
    }

    // ---- Arithmetic ---------------------------------------------------------------------------

    @Test
    void subtractSetsCarryWhenNoBorrowAndCompareLeavesTheRegister() {
        run(iwt(0, 5), iwt(1, 7), op(ALT3), op(0x61)); // CMP R1: 5 - 7

        assertEquals(5, gsu.r[0], "CMP only sets flags");
        assertFalse(gsu.carry, "5 - 7 borrows");
        assertTrue(gsu.sign);
    }

    @Test
    void addWithCarryUsesTheCarryFromTheFirstAdd() {
        // R0 = $FFFF + 1 = 0 with carry; then ADC #0 adds the carry: 1.
        run(iwt(0, 0xFFFF), op(ALT2), op(0x51), op(ALT3), op(0x50));

        assertEquals(1, gsu.r[0]);
    }

    @Test
    void addImmediateDetectsSignedOverflow() {
        run(iwt(0, 0x7FFF), op(ALT2), op(0x51)); // ADD #1

        assertEquals(0x8000, gsu.r[0]);
        assertTrue(gsu.overflow);
        assertTrue(gsu.sign);
    }

    @Test
    void multiplyIsSignedEightByEightAndUmultIsUnsigned() {
        run(iwt(0, 0x00FE), iwt(1, 0x0003), op(0x81)); // MULT R1: -2 * 3
        assertEquals(0xFFFA, gsu.r[0]);

        setUp();
        run(iwt(0, 0x00FE), iwt(1, 0x0003), op(ALT1), op(0x81)); // UMULT R1: 254 * 3
        assertEquals(762, gsu.r[0]);
    }

    @Test
    void fractionalMultiplyKeepsTheTopWordAndLmultTheLowWordInR4() {
        // $4000 * $4000 = $1000_0000 (0.5 * 0.5 = 0.25 in 1.15 fixed point, as $2000 after <<1)
        run(iwt(0, 0x4000), iwt(6, 0x4000), op(ALT1), op(FMULT));

        assertEquals(0x1000, gsu.r[0]);
        assertEquals(0x0000, gsu.r[4]);
    }

    @Test
    void div2RoundsMinusOneToZeroButAsrDoesNot() {
        run(iwt(0, 0xFFFF), op(0x96)); // ASR
        assertEquals(0xFFFF, gsu.r[0]);

        setUp();
        run(iwt(0, 0xFFFF), op(ALT1), op(0x96)); // DIV2
        assertEquals(0x0000, gsu.r[0]);
    }

    @Test
    void mergeCombinesTheHighBytesOfR7AndR8() {
        run(iwt(7, 0x12AB), iwt(8, 0x34CD), op(MERGE));

        assertEquals(0x1234, gsu.r[0]);
    }

    // ---- Memory -------------------------------------------------------------------------------

    @Test
    void wordStoresPutTheHighByteAtTheAddressXorOne() {
        // STW (R1) with R1 = $0101 (odd): low byte at $0101, high byte at $0100.
        run(iwt(0, 0xA1B2), iwt(1, 0x0101), op(0x31));

        assertEquals(0xB2, ram[0x0101] & 0xFF);
        assertEquals(0xA1, ram[0x0100] & 0xFF);
    }

    @Test
    void loadWordAndStoreBackUseTheSameAddress() {
        ram[0x0200] = 0x34;
        ram[0x0201] = 0x12;
        // LDW (R1); INC R0; SBK
        run(iwt(1, 0x0200), op(0x41), op(0xD0), op(SBK));

        assertEquals(0x35, ram[0x0200] & 0xFF);
        assertEquals(0x12, ram[0x0201] & 0xFF);
    }

    @Test
    void lmsAddressesWordsAtTwiceTheOperand() {
        ram[0x0010] = 0x78;
        ram[0x0011] = 0x56;
        run(op(ALT1), op(0xA3, 0x08)); // LMS R3,($08 * 2)

        assertEquals(0x5678, gsu.r[3]);
    }

    @Test
    void getbReadsTheRomByteR14PointsAt() {
        code[0x4321] = (byte) 0x9A;
        run(iwt(14, 0x4321), op(NOP), op(GETB));

        assertEquals(0x9A, gsu.r[0]);
    }

    @Test
    void codeRunsTheSameFromTheCache() {
        // CACHE, then the same addition as above. The cache now mirrors $0000-$01FF.
        run(op(0x02), iwt(1, 0x1234), iwt(2, 0x1111), op(0xB1), op(0x13), op(0x52));

        assertEquals(0x2345, gsu.r[3]);
        assertTrue(gsu.cacheLineValid[0]);
    }

    // ---- STOP and interrupts ------------------------------------------------------------------

    @Test
    void stopRaisesTheInterruptUnlessConfigMasksIt() {
        run();
        assertEquals(1, interrupts);
        assertTrue(gsu.irqPending);

        gsu.config = 0x80;
        gsu.irqPending = false;
        run();
        assertEquals(1, interrupts, "masked by CFGR bit 7");
    }

    // ---- Plotting -----------------------------------------------------------------------------

    @Test
    void plotWritesThePixelIntoTheRightBitplanes() {
        gsu.plot.colorDepthMode = 1; // 4bpp
        gsu.plot.heightMode = 0;     // 128 lines: 16 tiles per column
        // COLOR from R0 = 5; plot at (9, 3): tile column 1, row 0 -> tile 16, row 3, bit 6.
        run(iwt(0, 5), op(COLOR), iwt(1, 9), iwt(2, 3), op(PLOT));

        int rowAddress = 16 * 32 + 3 * 2;
        assertEquals(0x40, ram[rowAddress] & 0xFF, "plane 0 (bit 0 of color 5)");
        assertEquals(0x00, ram[rowAddress + 1] & 0xFF, "plane 1");
        assertEquals(0x40, ram[rowAddress + 16] & 0xFF, "plane 2 (bit 2 of color 5)");
        assertEquals(0x00, ram[rowAddress + 17] & 0xFF, "plane 3");
        assertEquals(10, gsu.r[1], "PLOT steps X");
    }

    @Test
    void readPixelReturnsWhatPlotDrew() {
        gsu.plot.colorDepthMode = 3; // 8bpp
        gsu.plot.heightMode = 2;     // 192 lines
        // Plot (100, 77) in color $C3, clear R0, then RPIX (ALT1 PLOT) at the same spot into R0.
        run(iwt(0, 0xC3), op(COLOR), iwt(1, 100), iwt(2, 77), op(PLOT),
                iwt(1, 100), iwt(0, 0), op(ALT1), op(PLOT));

        assertEquals(0xC3, gsu.r[0]);
    }

    @Test
    void colorZeroIsTransparentUnlessPorSaysOtherwise() {
        gsu.plot.colorDepthMode = 0; // 2bpp
        ram[6] = (byte) 0xFF;        // pixel row already set
        run(iwt(0, 0), op(COLOR), iwt(2, 3), op(PLOT)); // plot (0, 3) in color 0
        assertEquals(0xFF, ram[6] & 0xFF, "transparent: nothing written");

        setUp();
        gsu.plot.colorDepthMode = 0;
        ram[6] = (byte) 0xFF;
        // CMODE #1 (plot color 0), then plot
        run(iwt(0, 1), op(ALT1), op(COLOR), iwt(0, 0), op(COLOR), iwt(2, 3), op(PLOT));
        assertEquals(0x7F, ram[6] & 0xFF, "pixel 0 cleared");
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Assembles the pieces at $0000, appends STOP + NOP, and runs until the GSU stops. */
    private void run(int[]... pieces) {
        int address = 0;
        for (int[] piece : pieces) {
            for (int b : piece) {
                code[address++] = (byte) b;
            }
        }
        code[address++] = STOP;
        code[address] = NOP;
        gsu.r[15] = 0;
        gsu.running = true;
        for (int i = 0; i < 10_000 && gsu.running; i++) {
            gsu.step();
        }
        assertFalse(gsu.running, "program should reach STOP");
    }

    private static int[] op(int... bytes) {
        return bytes;
    }

    /** IWT Rn,#value: load a 16-bit immediate. */
    private static int[] iwt(int register, int value) {
        return new int[] {0xF0 | register, value & 0xFF, value >> 8};
    }
}
