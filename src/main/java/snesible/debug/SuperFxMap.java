package snesible.debug;

import snesible.core.cartridge.Mirroring;

/**
 * The SuperFX board's memory map, seen from the console and from the GSU. It's fixed (no bank
 * registers), so it's decoded directly, the same way {@code snesible.core.coprocessor.superfx.SuperFx}
 * does:
 * <pre>
 *                 console                     GSU
 *   $00-$3F       6000-7FFF RAM (first 8 KiB) 0000-FFFF ROM, LoROM layout
 *                 8000-FFFF ROM, LoROM
 *   $40-$5F       ROM, 64 KiB per bank        ROM, 64 KiB per bank
 *   $60-$7F       $70-$71: RAM                RAM (the bank's low bit picks the 64 KiB half)
 * </pre>
 * Banks $80-$FF mirror $00-$7F. The GSU's RAM is the cartridge RAM.
 */
final class SuperFxMap implements CartridgeMap {

    private final AccessRecorder recorder;
    private final int romSize;

    SuperFxMap(AccessRecorder recorder) {
        this.recorder = recorder;
        this.romSize = recorder.rom.size();
    }

    @Override
    public void record(int address, Access kind) {
        int bank = (address >>> 16) & 0x7F;
        int offset = address & 0xFFFF;
        if (bank < 0x40) {
            if (offset >= 0x6000 && offset < 0x8000) {
                ram(offset & 0x1FFF, kind);
            } else if (offset >= 0x8000) {
                rom(loRomOffset(bank, offset), kind);
            }
        } else if (bank < 0x60) {
            rom((bank & 0x1F) << 16 | offset, kind);
        } else if (bank == 0x70 || bank == 0x71) {
            ram((bank & 1) << 16 | offset, kind);
        }
    }

    /** Records an access by the GSU, in its own address map. */
    void recordGsu(int address, Access kind) {
        int bank = (address >>> 16) & 0x7F;
        int offset = address & 0xFFFF;
        if (bank < 0x40) {
            rom(loRomOffset(bank, offset), kind);
        } else if (bank < 0x60) {
            rom((bank & 0x1F) << 16 | offset, kind);
        } else {
            ram((bank & 1) << 16 | offset, kind);
        }
    }

    private static int loRomOffset(int bank, int offset) {
        return (bank & 0x3F) * 0x8000 + (offset & 0x7FFF);
    }

    private void rom(int offset, Access kind) {
        recorder.record(recorder.rom, Mirroring.mirror(offset, romSize), kind);
    }

    private void ram(int offset, Access kind) {
        if (recorder.cartRam != null) {
            recorder.record(recorder.cartRam, Mirroring.mirror(offset, recorder.cartRam.size()), kind);
        }
    }
}
