package snesible.core.coprocessor.sa1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import snesible.core.cartridge.Cartridge;
import snesible.core.cartridge.CoprocessorType;
import snesible.core.cartridge.MapMode;
import snesible.core.cartridge.TestRoms;
import snesible.core.coprocessor.Coprocessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SA-1 board as the console sees it: ROM banking, shared memories, the message and
 * interrupt registers, arithmetic, DMA, and small SA-1 programs run through catch-up.
 */
class Sa1Test {

    private static final int ROM_SIZE = 2 * 1024 * 1024;
    /** Where test programs for the SA-1 go: CPU $00:8100 = ROM offset $100. */
    private static final int PROGRAM = 0x8100;

    private byte[] rom;
    private long now;
    private boolean consoleIrq;
    private Sa1 board;
    private Cartridge cartridge;

    @BeforeEach
    void setUp() {
        rom = TestRoms.build(MapMode.LOROM, ROM_SIZE, 3, 0x8000); // 8 KiB BW-RAM
        rom[0x7FC0 + 0x15] = 0x23;  // map mode: SA-1
        rom[0x7FC0 + 0x16] = 0x35;  // cartridge type: SA-1 + RAM + battery
    }

    // ---- Memory map ---------------------------------------------------------------------------

    @Test
    void superMmcMapsOneMegabyteChunks() {
        boot();
        assertEquals(rom[0x000123] & 0xFF, board.read(0x008123), "LoROM $00:8000 is chunk 0 by default");
        assertEquals(rom[0x100123] & 0xFF, board.read(0xD00123), "HiROM window D shows chunk 1");

        board.write(0x002220, 0x81);  // CXB: chunk 1, and let the LoROM view follow
        assertEquals(rom[0x100123] & 0xFF, board.read(0x008123));
        assertEquals(rom[0x100123] & 0xFF, board.read(0xC00123));

        board.write(0x002220, 0x01);  // bit 7 clear: the LoROM view goes back to chunk 0
        assertEquals(rom[0x000123] & 0xFF, board.read(0x008123));
    }

    @Test
    void bwRamAppearsLinearlyAndThroughTheBlockWindow() {
        boot();
        board.write(0x402005, 0x5A);        // linear: block 1, offset 5
        board.write(0x002224, 0x01);        // BMAPS: the console's $6000 window shows block 1

        assertEquals(0x5A, board.read(0x006005));
        assertTrue(cartridge.hasUnsavedSram(), "BW-RAM is the battery-backed save memory");
    }

    // ---- The SA-1 CPU -------------------------------------------------------------------------

    @Test
    void sa1StartsAtItsResetVectorAndCanInterruptTheConsole() {
        // LDA #$85 ; STA $2209 (SCNT: IRQ to console + message 5) ; STP
        boot(0xA9, 0x85, 0x8D, 0x09, 0x22, 0xDB);
        board.write(0x002201, 0x80);        // SIE: let the SA-1 interrupt the console
        startSa1();

        assertEquals(0x85, board.read(0x002300), "SFR: IRQ flag and the message");
        assertTrue(consoleIrq);

        board.write(0x002202, 0x80);        // SIC: acknowledge
        assertFalse(consoleIrq);
        assertEquals(0x05, board.read(0x002300));
    }

    @Test
    void bothCpusShareIRam() {
        // LDA $0000 (I-RAM, as the SA-1 sees it) ; STA $3001 ; STP
        boot(0xAD, 0x00, 0x00, 0x8D, 0x01, 0x30, 0xDB);
        board.write(0x003000, 0x42);
        startSa1();

        assertEquals(0x42, board.read(0x003001));
    }

    @Test
    void bitmapViewPacksTwoPixelsPerByte() {
        // LDA #$0F ; STA $60:0001 (pixel 1 = high nibble of BW-RAM byte 0) ; STP
        boot(0xA9, 0x0F, 0x8F, 0x01, 0x00, 0x60, 0xDB);
        startSa1();

        assertEquals(0xF0, board.read(0x400000));
    }

    @Test
    void consoleVectorsCanBeReplacedBySnvAndSiv() {
        // LDA #$34 ; STA $220C ; LDA #$12 ; STA $220D ; LDA #$10 ; STA $2209 (NVSW) ; STP
        boot(0xA9, 0x34, 0x8D, 0x0C, 0x22, 0xA9, 0x12, 0x8D, 0x0D, 0x22, 0xA9, 0x10, 0x8D, 0x09, 0x22, 0xDB);
        assertEquals(rom[0x7FEA] & 0xFF, board.read(0x00FFEA), "before: the ROM's own vector");
        startSa1();

        assertEquals(0x34, board.read(0x00FFEA));
        assertEquals(0x12, board.read(0x00FFEB));
        assertEquals(rom[0x7FEE] & 0xFF, board.read(0x00FFEE), "IRQ vector not switched");
    }

    @Test
    void sa1StaysIdleWhileHeldInReset() {
        boot(0xA9, 0x85, 0x8D, 0x09, 0x22, 0xDB);
        now += 100_000;

        assertEquals(0x00, board.read(0x002300), "the SA-1 never ran");
    }

    // ---- Helpers on the board -----------------------------------------------------------------

    @Test
    void multipliesAndDividesSigned() {
        boot();
        board.write(0x002250, 0x00);        // multiply
        writeWord(0x2251, -300);
        writeWord(0x2253, 7);
        assertEquals(-2100, readResult32());

        board.write(0x002250, 0x01);        // divide: -7 / 2 = -4 remainder 1
        writeWord(0x2251, -7);
        writeWord(0x2253, 2);
        assertEquals(0xFFFC, readResult32() & 0xFFFF, "quotient -4");
        assertEquals(1, readResult32() >>> 16, "remainder stays positive");
    }

    @Test
    void cumulativeSumAddsProducts() {
        boot();
        board.write(0x002250, 0x02);
        writeWord(0x2251, 100);
        writeWord(0x2253, 3);
        writeWord(0x2251, -10);
        writeWord(0x2253, 5);

        assertEquals(250, readResult32());
    }

    @Test
    void dmaCopiesRomToIRamAndRaisesItsFlag() {
        boot();
        board.write(0x002230, 0x80);        // DCNT: enable, ROM -> I-RAM
        board.write(0x002232, 0x10);        // source $00:8010 = ROM offset $10
        board.write(0x002233, 0x80);
        board.write(0x002234, 0x00);
        board.write(0x002238, 0x04);        // 4 bytes
        board.write(0x002239, 0x00);
        board.write(0x002235, 0x00);        // destination I-RAM $100: the middle byte starts it
        board.write(0x002236, 0x01);

        for (int i = 0; i < 4; i++) {
            assertEquals(rom[0x10 + i] & 0xFF, board.read(0x003100 + i));
        }
        assertEquals(0x20, board.read(0x002301) & 0x20, "CFR: DMA finished");
    }

    @Test
    void variableLengthReaderStepsThroughBits() {
        rom[0x200] = (byte) 0xA5;
        rom[0x201] = (byte) 0x3C;
        boot();
        board.write(0x002259, 0x00);        // stream at $00:8200
        board.write(0x00225A, 0x82);
        board.write(0x00225B, 0x00);

        assertEquals(0xA5, board.read(0x00230C));
        board.write(0x002258, 0x04);        // manual mode: consume 4 bits
        assertEquals(0xCA, board.read(0x00230C), "now starting 4 bits in: $3CA5 >> 4");
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Builds the board with {@code program} at $00:8100 (the SA-1's reset vector). */
    private void boot(int... program) {
        for (int i = 0; i < program.length; i++) {
            rom[PROGRAM - 0x8000 + i] = (byte) program[i];
        }
        cartridge = Cartridge.fromFileBytes(rom);
        assertEquals(CoprocessorType.SA1, cartridge.header().coprocessorType());
        board = new Sa1(cartridge);
        board.connect(new Coprocessor.Host() {
            @Override
            public long masterCycles() {
                return now;
            }

            @Override
            public void setIrqLine(boolean asserted) {
                consoleIrq = asserted;
            }
        });
        board.reset();
        board.write(0x002203, PROGRAM & 0xFF);  // CRV
        board.write(0x002204, PROGRAM >> 8);
    }

    /** Releases the SA-1 from reset and gives it time to run its program. */
    private void startSa1() {
        board.write(0x002200, 0x00);
        now += 10_000;
        board.catchUp();
    }

    private void writeWord(int register, int value) {
        board.write(register, value & 0xFF);
        board.write(register + 1, (value >> 8) & 0xFF);
    }

    private int readResult32() {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result |= board.read(0x002306 + i) << (8 * i);
        }
        return result;
    }
}
