package jfc.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jfc.bus.SnesBus;
import jfc.cartridge.Cartridge;
import jfc.cartridge.MapMode;
import jfc.cartridge.TestRoms;
import org.junit.jupiter.api.Test;

/**
 * Whole-system tests: memory map, timing, interrupts and DMA, driven by tiny hand-assembled
 * programs. After reset the CPU is in emulation mode, so the programs are plain 6502-style code.
 */
class SnesTest {

    private static final int FRAME_CYCLES = MasterClock.CYCLES_PER_LINE * MasterClock.LINES_PER_FRAME_NTSC;

    /** {@code loop: BRA loop} */
    private static final int[] IDLE_LOOP = {0x80, 0xFE};

    // ---- Memory map ---------------------------------------------------------------------------

    @Test
    void lowRamIsMirroredInEverySystemBank() {
        SnesBus bus = boot(IDLE_LOOP).bus();

        bus.writeA(0x7E0010, 0x42);

        assertEquals(0x42, bus.readA(0x000010));
        assertEquals(0x42, bus.readA(0x3F0010));
        assertEquals(0x42, bus.readA(0x800010));
    }

    @Test
    void onlyTheFirst8KiBOfWorkRamIsMirrored() {
        SnesBus bus = boot(IDLE_LOOP).bus();

        bus.writeA(0x7E2000, 0x42);

        assertEquals(0x42, bus.readA(0x7E2000));
        // $00:2000 isn't work RAM; nothing answers there, so it reads as open bus instead.
        assertEquals(bus.openBus(), bus.readA(0x002000));
        assertNotEquals(0x42, bus.openBus());
    }

    @Test
    void unmappedAddressesReturnTheLastValueOnTheBus() {
        SnesBus bus = boot(IDLE_LOOP).bus();

        int fetched = bus.read(0x008000);

        assertEquals(fetched, bus.read(0x002000), "nothing answers at $2000, so the bus keeps its value");
    }

    @Test
    void accessSpeedDependsOnWhereTheAccessGoes() {
        SnesBus bus = boot(IDLE_LOOP).bus();

        assertEquals(8, bus.accessCycles(0x000000), "work RAM");
        assertEquals(6, bus.accessCycles(0x002118), "PPU register");
        assertEquals(12, bus.accessCycles(0x004016), "old-style joypad port");
        assertEquals(6, bus.accessCycles(0x004210), "CPU I/O");
        assertEquals(8, bus.accessCycles(0x7E0000), "work RAM, full bank");
        assertEquals(8, bus.accessCycles(0x808000), "ROM is slow until FastROM is enabled");

        bus.writeA(0x00420D, 0x01);

        assertEquals(6, bus.accessCycles(0x808000), "FastROM in the upper banks");
        assertEquals(6, bus.accessCycles(0xC00000));
        assertEquals(8, bus.accessCycles(0x008000), "lower banks stay slow");
    }

    @Test
    void workRamPortWrapsAround128KiB() {
        Snes snes = boot(IDLE_LOOP);
        SnesBus bus = snes.bus();
        bus.writeA(0x002181, 0xFF);
        bus.writeA(0x002182, 0xFF);
        bus.writeA(0x002183, 0x01);

        bus.writeA(0x002180, 0xAB);
        bus.writeA(0x002180, 0xCD);

        assertEquals(0xAB, snes.wram().read(0x1FFFF));
        assertEquals(0xCD, snes.wram().read(0x00000));
    }

    // ---- Timing and interrupts ----------------------------------------------------------------

    @Test
    void aFrameLasts262LinesOf1364MasterCycles() {
        Snes snes = boot(IDLE_LOOP);
        snes.runFrame();
        long firstVblank = snes.clock().totalCycles();

        snes.runFrame();

        long frameLength = snes.clock().totalCycles() - firstVblank;
        // runFrame stops at the first CPU cycle after vblank starts, so allow one access of slack.
        assertTrue(Math.abs(frameLength - FRAME_CYCLES) <= 64, "frame was " + frameLength);
    }

    @Test
    void nmiFiresOncePerFrameWhenEnabled() {
        int[] program = {
            0xA9, 0x80,       // LDA #$80
            0x8D, 0x00, 0x42, // STA $4200   enable NMI
            0x80, 0xFE,       // BRA *       wait forever
        };
        int[] nmiHandler = {
            0xEE, 0x10, 0x00, // INC $0010   count NMIs in work RAM
            0x40,             // RTI
        };
        Snes snes = boot(program, nmiHandler, null);

        for (int i = 0; i < 4; i++) {
            snes.runFrame();
        }

        // runFrame stops as vblank (and the NMI) begins, so the 4th NMI's handler hasn't run yet.
        assertEquals(3, snes.wram().read(0x10));
    }

    @Test
    void vTimerIrqFiresOnTheProgrammedLine() {
        int[] program = {
            0xA9, 100,        // LDA #100
            0x8D, 0x09, 0x42, // STA $4209   VTIME low
            0x9C, 0x0A, 0x42, // STZ $420A   VTIME high
            0xA9, 0x20,       // LDA #$20
            0x8D, 0x00, 0x42, // STA $4200   IRQ mode 2: at the start of line VTIME
            0x58,             // CLI
            0x80, 0xFE,       // BRA *
        };
        int[] irqHandler = {
            0xAD, 0x37, 0x21, // LDA $2137   latch the beam position
            0xAD, 0x11, 0x42, // LDA $4211   acknowledge the IRQ
            0x40,             // RTI
        };
        Snes snes = boot(program, null, irqHandler);
        snes.runFrame();
        snes.runFrame();

        SnesBus bus = snes.bus();
        bus.readA(0x00213F); // reset the counter read flip-flops
        int latchedLine = bus.readA(0x00213D) | ((bus.readA(0x00213D) & 1) << 8);

        assertEquals(100, latchedLine);
    }

    // ---- DMA ----------------------------------------------------------------------------------

    @Test
    void dmaCopiesWorkRamIntoVramThroughTheRegisterPair() {
        Snes snes = boot(IDLE_LOOP);
        SnesBus bus = snes.bus();
        int[] data = {0x11, 0x22, 0x33, 0x44};
        for (int i = 0; i < data.length; i++) {
            snes.wram().write(i, data[i]);
        }
        bus.writeA(0x002115, 0x80); // VMAIN: step after the high byte
        bus.writeA(0x002116, 0x00); // VRAM word address $1000
        bus.writeA(0x002117, 0x10);
        bus.writeA(0x004300, 0x01); // A-bus to B-bus, A address increments, pattern 1 (p, p+1)
        bus.writeA(0x004301, 0x18); // B-bus register $2118
        bus.writeA(0x004302, 0x00); // source $7E:0000
        bus.writeA(0x004303, 0x00);
        bus.writeA(0x004304, 0x7E);
        bus.writeA(0x004305, data.length);
        bus.writeA(0x004306, 0x00);
        long before = snes.clock().totalCycles();

        bus.writeA(0x00420B, 0x01);

        assertEquals(0x2211, snes.ppu().peekVram(0x1000));
        assertEquals(0x4433, snes.ppu().peekVram(0x1001));
        assertEquals(0, bus.readA(0x004305), "the byte counter runs down to zero");
        assertEquals(data.length, bus.readA(0x004302), "the source address has advanced");
        long elapsed = snes.clock().totalCycles() - before;
        assertTrue(elapsed >= 8L * data.length + 16, "DMA takes 8 master cycles per byte plus setup");
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static Snes boot(int[] program) {
        return boot(program, null, null);
    }

    /**
     * Builds a LoROM with {@code program} at the reset address $8000, optional NMI/IRQ handlers at
     * $8100/$8200 (wired to the emulation-mode vectors), resets the console and returns it.
     */
    private static Snes boot(int[] program, int[] nmiHandler, int[] irqHandler) {
        byte[] rom = TestRoms.build(MapMode.LOROM, 128 * 1024, 0, 0x8000);
        place(rom, 0x8000, program);
        if (nmiHandler != null) {
            place(rom, 0x8100, nmiHandler);
            place(rom, 0xFFFA, new int[] {0x00, 0x81});
        }
        if (irqHandler != null) {
            place(rom, 0x8200, irqHandler);
            place(rom, 0xFFFE, new int[] {0x00, 0x82});
        }
        Snes snes = new Snes(Cartridge.fromFileBytes(rom));
        snes.reset();
        return snes;
    }

    /** Copies bytes to a bank $00 CPU address. In LoROM, $00:8000 is file offset 0. */
    private static void place(byte[] rom, int cpuAddress, int[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            rom[cpuAddress - 0x8000 + i] = (byte) bytes[i];
        }
    }
}
