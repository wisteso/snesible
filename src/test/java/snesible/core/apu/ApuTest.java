package snesible.core.apu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import snesible.core.system.MasterClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the APU from the main CPU's side of the four ports, the way games do: upload a program
 * through the boot ROM, run it, and watch what it reports back.
 */
class ApuTest {

    private MasterClock clock;
    private Apu apu;

    @BeforeEach
    void setUp() {
        clock = new MasterClock(false);
        clock.setListener(new MasterClock.Listener() {
            @Override
            public void onLineStart(int line) {
            }

            @Override
            public void onBeamMoved(int line, int fromH, int toH) {
            }
        });
        apu = new Apu(clock, false);
    }

    @Test
    void bootRomAnnouncesItIsReady() {
        waitForPort(0, 0xAA);
        assertEquals(0xBB, apu.read(1));
    }

    @Test
    void uploadedProgramRunsAndTalksBack() {
        int[] program = {
            0x8F, 0x55, 0xF4,   // MOV $F4,#$55   ; write $55 to port 0
            0x2F, 0xFE,         // BRA *
        };

        upload(0x0200, program);

        waitForPort(0, 0x55);
    }

    @Test
    void timerCountsAtItsTargetRate() {
        int[] program = {
            0x8F, 0x02, 0xFA,   // MOV $FA,#$02   ; timer 0 target: 2 ticks of 8 kHz
            0x8F, 0x01, 0xF1,   // MOV $F1,#$01   ; enable timer 0 (boot ROM off)
            0xE4, 0xFD,         // loop: MOV A,$FD ; read (and clear) timer 0's output
            0xF0, 0xFC,         //       BEQ loop
            0xC4, 0xF4,         // MOV $F4,A      ; report the count
            0x2F, 0xFE,         // BRA *
        };

        upload(0x0200, program);

        waitForPort(0, 0x01);
    }

    // ---- The main CPU's side of the upload protocol --------------------------------------------

    /** Uploads {@code program} to {@code address} and starts it, as a game's sound loader does. */
    private void upload(int address, int[] program) {
        waitForPort(0, 0xAA);
        apu.write(2, address & 0xFF);
        apu.write(3, address >> 8);
        apu.write(1, 0x01);         // non-zero: a block of data follows
        apu.write(0, 0xCC);
        waitForPort(0, 0xCC);
        for (int i = 0; i < program.length; i++) {
            apu.write(1, program[i]);
            apu.write(0, i);
            waitForPort(0, i);
        }
        apu.write(2, address & 0xFF);
        apu.write(3, address >> 8);
        apu.write(1, 0x00);         // zero: jump to the address
        int kick = program.length + 1;
        apu.write(0, kick);
        waitForPort(0, kick);
    }

    /** Lets time pass until the APU shows {@code value} on {@code port}. */
    private void waitForPort(int port, int value) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            if (apu.read(port) == value) {
                return;
            }
            clock.advance(200);
        }
        assertTrue(false, String.format("port %d never showed $%02X (shows $%02X)", port, value, apu.read(port)));
    }
}
