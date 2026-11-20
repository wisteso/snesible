package snesible.debug;

import snesible.core.apu.Apu;

/**
 * Works out, once a frame, what the DSP is reading from sound RAM for itself, and marks it in
 * ARAM as {@link Access#CHIP}.
 *
 * <p>Like the PPU, the DSP indexes sound RAM directly, without bus calls to watch. What it
 * reads follows from its registers (read with {@link Apu#peekDspRegister}, which has no side
 * effects) and from the sample directory in RAM:
 * <ul>
 *   <li><b>Samples:</b> for each voice whose envelope (ENVX) is above zero, so it's audible:
 *       its entry in the sample directory (DIR + SRCN x 4: start and loop address), and its
 *       sample from the start address to the 9-byte BRR block with the end flag.</li>
 *   <li><b>Echo buffer:</b> ESA x 256, EDL x 2 KiB long (4 bytes when EDL is 0), read and
 *       written all the time while echo writes are on (FLG bit 5 clear).</li>
 * </ul>
 *
 * <p><b>What it misses:</b> where in its sample a voice is, so the whole sample is marked
 * while it plays; and voices that started and stopped within one frame.
 */
final class SoundActivity {

    private static final int DIR = 0x5D;
    private static final int FLG = 0x6C;
    private static final int ESA = 0x6D;
    private static final int EDL = 0x7D;
    private static final int SRCN = 0x04;
    private static final int ENVX = 0x08;
    private static final int BRR_BLOCK = 9;

    private final AccessRecorder recorder;
    private final Apu apu;
    private final byte[] ram;

    SoundActivity(AccessRecorder recorder, Apu apu) {
        this.recorder = recorder;
        this.apu = apu;
        this.ram = apu.ramBytes();
    }

    /** Marks what the DSP is reading (and writing) this frame. */
    void markFrame() {
        int directory = apu.peekDspRegister(DIR) << 8;
        for (int voice = 0; voice < 8; voice++) {
            if (apu.peekDspRegister(voice << 4 | ENVX) == 0) {
                continue; // silent
            }
            int entry = (directory + apu.peekDspRegister(voice << 4 | SRCN) * 4) & 0xFFFF;
            mark(entry, 4);
            int address = (ram[entry] & 0xFF) | (ram[(entry + 1) & 0xFFFF] & 0xFF) << 8;
            // Walk the BRR blocks to the one whose header has the end flag (bit 0). Stop after
            // 64 KiB in case the data never ends (garbage, or a sample still being uploaded).
            for (int walked = 0; walked < 0x10000; walked += BRR_BLOCK) {
                mark(address, BRR_BLOCK);
                if ((ram[address] & 1) != 0) {
                    break;
                }
                address = (address + BRR_BLOCK) & 0xFFFF;
            }
        }
        if ((apu.peekDspRegister(FLG) & 0x20) == 0) {
            int start = apu.peekDspRegister(ESA) << 8;
            int delay = apu.peekDspRegister(EDL) & 0x0F;
            mark(start, delay == 0 ? 4 : delay * 2048);
        }
    }

    /** Marks {@code count} bytes from {@code address}, wrapping at 64 KiB. */
    private void mark(int address, int count) {
        int frame = recorder.frame();
        for (int i = 0; i < count; i++) {
            recorder.aram.record((address + i) & 0xFFFF, Access.CHIP, frame);
        }
    }
}
