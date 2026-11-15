package jfc.core.coprocessor.superfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jfc.core.cartridge.Cartridge;
import jfc.core.cartridge.CoprocessorType;
import jfc.core.cartridge.MapMode;
import jfc.core.cartridge.TestRoms;
import jfc.core.coprocessor.Coprocessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The SuperFX board as the console sees it: memory map, registers, bus sharing, catch-up. */
class SuperFxTest {

    private long now;
    private boolean irqLine;
    private SuperFx board;
    private byte[] rom;

    @BeforeEach
    void setUp() {
        rom = TestRoms.build(MapMode.LOROM, 256 * 1024, 0, 0x8000);
        rom[0x7FC0 + 0x16] = 0x13; // cartridge type: ROM + SuperFX
        Cartridge cartridge = Cartridge.fromFileBytes(rom);
        assertEquals(CoprocessorType.SUPER_FX, cartridge.header().coprocessorType());
        board = new SuperFx(cartridge);
        board.connect(new Coprocessor.Host() {
            @Override
            public long masterCycles() {
                return now;
            }

            @Override
            public void setIrqLine(boolean asserted) {
                irqLine = asserted;
            }
        });
        board.reset();
    }

    @Test
    void romIsVisibleInBothLayouts() {
        // LoROM $01:8000 and linear $40:8000 are both ROM offset $8000.
        assertEquals(rom[0x8000] & 0xFF, board.read(0x018000));
        assertEquals(rom[0x8000] & 0xFF, board.read(0x408000));
        assertEquals(rom[0x8000] & 0xFF, board.read(0x818000), "banks $80+ mirror");
    }

    @Test
    void ramAppearsInBank70AndAtSixThousand() {
        board.write(0x700123, 0x5A);

        assertEquals(0x5A, board.read(0x700123));
        assertEquals(0x5A, board.read(0x006123), "$6000-$7FFF shows the first 8 KiB");
    }

    @Test
    void registersAreReadableAndWritable() {
        board.write(0x003002, 0x34); // R1 low
        board.write(0x003003, 0x12); // R1 high

        assertEquals(0x1234, board.gsu().r[1]);
        assertEquals(0x34, board.read(0x003002));
    }

    @Test
    void programRunsAsTheClockAdvancesAndStopRaisesTheIrq() {
        // GSU program in ROM bank 0 at $8000: INC R1 x3, STOP, NOP.
        byte[] program = {(byte) 0xD1, (byte) 0xD1, (byte) 0xD1, 0x00, 0x01};
        Cartridge cartridge = cartridgeWithCodeAt(0x0000, program);
        board = new SuperFx(cartridge);
        connectWithClock();

        board.write(0x00303A, 0x18);          // SCMR: give the GSU the ROM and RAM buses
        board.write(0x00301E, 0x00);          // R15 = $8000 (LoROM bank 0)
        board.write(0x00301F, 0x80);          // high byte: go
        assertTrue(board.gsu().running);
        assertEquals(0x01, board.read(0x00FFEB), "while the GSU owns ROM, the NMI vector reads $0108");

        now += 10_000;                        // plenty of time
        assertEquals(0x00, board.read(0x003030) & 0x20, "G cleared by STOP");
        assertEquals(3, board.gsu().r[1]);
        assertTrue(irqLine);

        assertEquals(0x80, board.read(0x003031) & 0x80, "IRQ flag in SFR high");
        assertFalse(irqLine, "reading $3031 acknowledges the interrupt");
    }

    @Test
    void consoleCanNotSeeRamWhileTheGsuOwnsIt() {
        board.write(0x700000, 0x77);
        board.write(0x00303A, 0x08);          // RAN
        board.gsu().running = true;            // pretend it's busy

        assertEquals(Cartridge.OPEN_BUS, board.read(0x700000));
    }

    @Test
    void cacheWindowFeedsTheGsu() {
        // Write INC R1, STOP, NOPs into the cache through $3100, then start the GSU at $0000
        // (CBR = 0). ROM offset 0 holds $00 (STOP), so R1 = 1 proves the code came from the cache.
        int[] program = {0xD1, 0x00};
        for (int i = 0; i < 16; i++) {
            board.write(0x003100 + i, i < program.length ? program[i] : 0x01);
        }
        board.write(0x00301E, 0x00);
        board.write(0x00301F, 0x00);
        now += 1000;
        board.catchUp();

        assertFalse(board.gsu().running);
        assertEquals(1, board.gsu().r[1], "the code came from the cache, not from ROM");
    }

    private void connectWithClock() {
        board.connect(new Coprocessor.Host() {
            @Override
            public long masterCycles() {
                return now;
            }

            @Override
            public void setIrqLine(boolean asserted) {
                irqLine = asserted;
            }
        });
        board.reset();
    }

    /** A SuperFX test ROM with {@code program} at ROM offset {@code offset}. */
    private static Cartridge cartridgeWithCodeAt(int offset, byte[] program) {
        byte[] image = TestRoms.build(MapMode.LOROM, 256 * 1024, 0, 0x8000);
        image[0x7FC0 + 0x16] = 0x13;
        System.arraycopy(program, 0, image, offset, program.length);
        return Cartridge.fromFileBytes(image);
    }
}
