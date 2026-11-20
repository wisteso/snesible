package snesible.core.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers what SingleStepTests doesn't: reset, hardware interrupts, and waking from WAI.
 */
class CpuInterruptTest {

    private static final int NOP = 0xEA;
    private static final int WAI = 0xCB;

    private RecordingBus bus;
    private Cpu cpu;
    private Registers r;

    @BeforeEach
    void setUp() {
        bus = new RecordingBus();
        cpu = new Cpu(bus);
        r = cpu.registers();
        pokeWord(0xFFFC, 0x8000); // RESET
        pokeWord(0xFFEA, 0x9000); // native NMI
        pokeWord(0xFFEE, 0xA000); // native IRQ
        pokeWord(0xFFFA, 0xB000); // emulation NMI
        for (int i = 0; i < 16; i++) {
            bus.poke(0x8000 + i, NOP);
        }
    }

    @Test
    void resetStartsInEmulationModeAtTheResetVector() {
        cpu.reset();

        assertTrue(r.emulation);
        assertEquals(0x8000, r.pc);
        assertEquals(0, r.pbr);
        assertTrue(r.p.irqDisable);
        assertEquals(0x01, r.sp >> 8, "stack is confined to page 1");
    }

    @Test
    void nativeModeNmiPushesBankPcAndStatusThenJumpsThroughVector() {
        enterNativeMode(0x12, 0x3456, 0x01FF);

        cpu.triggerNmi();
        cpu.step();

        assertEquals(0x9000, r.pc);
        assertEquals(0x00, r.pbr);
        assertTrue(r.p.irqDisable);
        assertEquals(0x01FB, r.sp, "four bytes pushed");
        assertEquals(0x12, bus.peek(0x01FF), "PBR");
        assertEquals(0x34, bus.peek(0x01FE), "PC high");
        assertEquals(0x56, bus.peek(0x01FD), "PC low");
        assertEquals(8, bus.cycles().size(), "2 idle + 4 pushes + 2 vector reads");
    }

    @Test
    void emulationModeNmiPushesStatusWithBreakFlagClear() {
        cpu.reset();
        r.sp = 0x01FF;

        cpu.triggerNmi();
        cpu.step();

        assertEquals(0xB000, r.pc);
        int pushedStatus = bus.peek(0x01FD);
        assertEquals(0, pushedStatus & 0x10, "B flag marks software interrupts only");
    }

    @Test
    void irqIsIgnoredWhileInterruptsAreDisabled() {
        enterNativeMode(0x00, 0x8000, 0x01FF);
        r.p.irqDisable = true;

        cpu.setIrqLine(true);
        cpu.step();

        assertEquals(0x8001, r.pc, "executed the NOP instead");
    }

    @Test
    void irqIsTakenWhenEnabled() {
        enterNativeMode(0x00, 0x8000, 0x01FF);
        r.p.irqDisable = false;

        cpu.setIrqLine(true);
        cpu.step();

        assertEquals(0xA000, r.pc);
    }

    @Test
    void waiSleepsUntilAnInterruptAndAMaskedIrqJustResumes() {
        enterNativeMode(0x00, 0x8000, 0x01FF);
        bus.poke(0x8000, WAI);
        r.p.irqDisable = true;

        cpu.step();
        assertTrue(cpu.isWaitingForInterrupt());
        cpu.step();
        assertEquals(0x8001, r.pc, "still asleep, not executing");

        cpu.setIrqLine(true);
        cpu.step();

        assertFalse(cpu.isWaitingForInterrupt());
        assertEquals(0x8002, r.pc, "woke and ran the NOP after WAI; the masked IRQ wasn't serviced");
    }

    private void enterNativeMode(int pbr, int pc, int sp) {
        cpu.reset();
        r.emulation = false;
        r.pbr = pbr;
        r.pc = pc;
        r.sp = sp;
        bus.cycles().clear();
    }

    private void pokeWord(int address, int value) {
        bus.poke(address, value & 0xFF);
        bus.poke(address + 1, value >> 8);
    }
}
