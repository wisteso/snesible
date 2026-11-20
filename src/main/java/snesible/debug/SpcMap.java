package snesible.debug;

/**
 * Decodes the SPC700's 64 KiB address space into sound RAM (ARAM):
 * <ul>
 *   <li>$00F0-$00FF are the APU's registers. Reads there aren't RAM; writes are recorded,
 *       because writes always reach the RAM underneath as well (as in {@code Apu}).</li>
 *   <li>$FFC0-$FFFF read the 64-byte boot ROM while it's enabled (control register $F1
 *       bit 7, set at reset), so those reads aren't RAM either. This class follows $F1 writes
 *       to know.</li>
 * </ul>
 */
final class SpcMap implements BusMap {

    private static final int BOOT_ROM_START = 0xFFC0;

    private final AccessRecorder recorder;
    /** $F1 bit 7: the boot ROM covers $FFC0-$FFFF for reads. On at reset. */
    private boolean bootRomEnabled = true;

    SpcMap(AccessRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void read(int address, Access kind) {
        if (address >= 0xF0 && address <= 0xFF) {
            return;
        }
        if (address >= BOOT_ROM_START && bootRomEnabled) {
            return;
        }
        recorder.record(recorder.aram, address, kind);
    }

    @Override
    public void write(int address, int value, Access kind) {
        if (address == 0xF1) {
            bootRomEnabled = (value & 0x80) != 0;
        }
        recorder.record(recorder.aram, address, kind);
    }
}
