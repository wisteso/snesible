package jfc.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jfc.core.cartridge.Cartridge;
import jfc.core.cartridge.MapMode;
import jfc.core.cartridge.TestRoms;
import jfc.core.cpu.Registers;
import jfc.core.observe.Observable;
import jfc.core.system.Snes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the debugger's recording against tiny hand-assembled programs: accesses land in the
 * right memory at the right offset with the right kind, port writes are followed to their
 * real target, and watching changes nothing about what the console does.
 */
class MemoryObserverTest {

    /**
     * At the reset address $8000, in emulation mode (so 6502-style code): copy 32 bytes from
     * ROM ($00:8000, this code itself) to VRAM word 0 with DMA channel 0, store $42 at $0010,
     * then loop forever.
     */
    private static final int[] PROGRAM = {
            0xA9, 0x80, 0x8D, 0x15, 0x21,   // VMAIN = $80: step after the high byte
            0xA9, 0x00, 0x8D, 0x16, 0x21,   // VRAM address low
            0xA9, 0x00, 0x8D, 0x17, 0x21,   // VRAM address high: word $0000
            0xA9, 0x01, 0x8D, 0x00, 0x43,   // DMAP0: two registers, $2118 then $2119
            0xA9, 0x18, 0x8D, 0x01, 0x43,   // BBAD0: $2118
            0xA9, 0x00, 0x8D, 0x02, 0x43,   // source $00:8000
            0xA9, 0x80, 0x8D, 0x03, 0x43,
            0xA9, 0x00, 0x8D, 0x04, 0x43,
            0xA9, 0x20, 0x8D, 0x05, 0x43,   // 32 bytes
            0xA9, 0x00, 0x8D, 0x06, 0x43,
            0xA9, 0x01, 0x8D, 0x0B, 0x42,   // start channel 0
            0xA9, 0x42, 0x8D, 0x10, 0x00,   // STA $0010
            0x80, 0xFE,                     // loop: BRA loop
    };
    private static final int LOOP_OFFSET = PROGRAM.length - 2;

    @AfterEach
    void uninstall() {
        Observable.install(null);
    }

    @Test
    void cpuAccessesLandInTheRightMemoryAsTheRightKind() {
        MemoryObserver observer = watchedConsole();
        AccessRecorder recorder = observer.recorder();

        assertTrue(recorder.rom.lastFrame(LOOP_OFFSET, Access.EXECUTE) > 0, "the BRA loop is executed");
        assertTrue(recorder.rom.lastFrame(0x7FFC, Access.READ) > 0, "the reset vector is read as data");
        assertEquals(0, recorder.rom.lastFrame(0x7FFC, Access.EXECUTE));
        assertTrue(recorder.wram.lastFrame(0x0010, Access.WRITE) > 0, "STA $0010");
        assertEquals(0, recorder.wram.lastFrame(0x0011, Access.WRITE));
    }

    @Test
    void dmaThroughTheVramPortLandsAtTheVramAddress() {
        MemoryObserver observer = watchedConsole();
        AccessRecorder recorder = observer.recorder();

        for (int i = 0; i < 32; i++) {
            assertTrue(recorder.rom.lastFrame(i, Access.DMA) > 0, "ROM source byte " + i);
            assertTrue(recorder.vram.lastFrame(i, Access.DMA) > 0, "VRAM byte " + i);
        }
        assertEquals(0, recorder.vram.lastFrame(32, Access.DMA), "only 32 bytes were copied");
    }

    @Test
    void soundCpuWritesReachTheRamUnderItsRegisters() {
        MemoryObserver observer = watchedConsole();

        // The boot ROM writes $BBAA to ports $F4/$F5 to say it's ready. Writes to the APU's
        // registers also land in the RAM underneath, so they show in ARAM.
        assertTrue(observer.recorder().aram.lastFrame(0xF4, Access.WRITE) > 0);
        assertTrue(observer.recorder().aram.lastFrame(0xF5, Access.WRITE) > 0);
        // Its code is in the boot ROM at $FFC0, not RAM, so no RAM is marked executed there.
        assertEquals(0, observer.recorder().aram.lastFrame(0xFFC0, Access.EXECUTE));
    }

    @Test
    void watchingDoesNotChangeWhatTheConsoleDoes() {
        Snes plain = build();
        plain.reset();
        MemoryObserver observer = new MemoryObserver();
        Observable.install(observer);
        Snes watched = build();
        observer.attach(watched);
        watched.reset();

        for (int frame = 0; frame < 3; frame++) {
            plain.runFrame();
            watched.runFrame();
            observer.endFrame();
        }

        Registers p = plain.cpu().registers();
        Registers w = watched.cpu().registers();
        assertEquals(p.pc, w.pc);
        assertEquals(p.a, w.a);
        assertEquals(plain.clock().totalCycles(), watched.clock().totalCycles());
        assertEquals(plain.apu().cpu().pc, watched.apu().cpu().pc);
        for (int word = 0; word < 16; word++) {
            assertEquals(plain.ppu().peekVram(word), watched.ppu().peekVram(word));
        }
    }

    /** Builds the test console with an observer attached and runs two frames. */
    private static MemoryObserver watchedConsole() {
        MemoryObserver observer = new MemoryObserver();
        Observable.install(observer);
        Snes snes = build();
        observer.attach(snes);
        snes.reset();
        for (int frame = 0; frame < 2; frame++) {
            snes.runFrame();
            observer.endFrame();
        }
        return observer;
    }

    private static Snes build() {
        byte[] rom = TestRoms.build(MapMode.LOROM, 128 * 1024, 0, 0x8000);
        for (int i = 0; i < PROGRAM.length; i++) {
            rom[i] = (byte) PROGRAM[i];   // LoROM: $00:8000 is the first byte of the file
        }
        return new Snes(Cartridge.fromFileBytes(rom));
    }
}
