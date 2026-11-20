package snesible.debug;

import snesible.core.cartridge.Mirroring;

/**
 * The SA-1 board's memory map, seen from both of its CPUs: the console's S-CPU and the SA-1's
 * own 65816. It decodes addresses into ROM, BW-RAM (cartridge RAM) and I-RAM offsets the same
 * way {@code snesible.core.coprocessor.sa1.Sa1} and {@code SuperMmc} do.
 *
 * <p>The SA-1 maps memory through bank registers, so this class keeps its own copy of them,
 * updated from the register writes both CPUs make (they pass through the debugger's bus
 * wrappers):
 * <ul>
 *   <li>$2220-$2223 CXB/DXB/EXB/FXB (Super MMC): which 1 MiB ROM chunk each window shows, and
 *       whether the LoROM banks follow it (bit 7);</li>
 *   <li>$2224 BMAPS: the console's 8 KiB BW-RAM block at $6000-$7FFF;</li>
 *   <li>$2225 BMAP: the SA-1's block there, or (bit 7) a block of the bitmap view;</li>
 *   <li>$223F BBF bit 7: the bitmap view packs 2-bit pixels (else 4-bit).</li>
 * </ul>
 * The initial values are the board's reset state. Not recorded: the SA-1's own DMA, which
 * copies between its memories without any bus access.
 */
final class Sa1Map implements CartridgeMap {

    private final AccessRecorder recorder;
    private final int romSize;
    private final int[] chunk = {0, 1, 2, 3};
    private final boolean[] loRomFollows = new boolean[4];
    private int consoleBwramBlock;
    private int sa1BwramBlock;
    private boolean sa1Bitmap;
    private boolean bitmap2bpp;

    Sa1Map(AccessRecorder recorder) {
        this.recorder = recorder;
        this.romSize = recorder.rom.size();
    }

    // ---- The console's side (the S-CPU and DMA) ----------------------------------------------

    @Override
    public void record(int address, Access kind) {
        int bank = (address >>> 16) & 0xFF;
        int offset = address & 0xFFFF;
        if ((bank & 0x40) == 0) {                       // $00-$3F, $80-$BF
            if (offset >= 0x3000 && offset < 0x3800) {
                iram(offset & 0x7FF, kind);
            } else if (offset >= 0x6000 && offset < 0x8000) {
                bwram(consoleBwramBlock * 0x2000 + (offset & 0x1FFF), kind);
            } else if (offset >= 0x8000) {
                rom(bank, offset, kind);
            }
        } else if ((bank & 0xC0) == 0xC0) {             // $C0-$FF
            rom(bank, offset, kind);
        } else if (bank < 0x50) {                       // $40-$4F
            bwram((bank & 0x0F) << 16 | offset, kind);
        }
    }

    @Override
    public void recordWrite(int address, int value, Access kind) {
        int offset = address & 0xFFFF;
        if (((address >>> 16) & 0x40) == 0 && offset >= 0x2200 && offset < 0x2300) {
            registerWritten(offset, value);
        } else {
            record(address, kind);
        }
    }

    // ---- The SA-1's side ----------------------------------------------------------------------

    /** Records an access by the SA-1 CPU, in its own address map. */
    void recordSa1(int address, Access kind) {
        int bank = (address >>> 16) & 0xFF;
        int offset = address & 0xFFFF;
        if ((bank & 0x40) == 0) {
            if (offset < 0x0800 || (offset >= 0x3000 && offset < 0x3800)) {
                iram(offset & 0x7FF, kind);
            } else if (offset >= 0x6000 && offset < 0x8000) {
                int windowOffset = offset & 0x1FFF;
                if (sa1Bitmap) {
                    bitmap(sa1BwramBlock * 0x2000 + windowOffset, kind);
                } else {
                    bwram((sa1BwramBlock & 0x1F) * 0x2000 + windowOffset, kind);
                }
            } else if (offset >= 0x8000) {
                rom(bank, offset, kind);
            }
        } else if ((bank & 0xC0) == 0xC0) {
            rom(bank, offset, kind);
        } else if (bank < 0x50) {
            bwram((bank & 0x0F) << 16 | offset, kind);
        } else if (bank >= 0x60 && bank < 0x70) {
            bitmap((bank & 0x0F) << 16 | offset, kind);
        }
    }

    /** Records a write by the SA-1 CPU (registers are shared with the console side). */
    void recordSa1Write(int address, int value, Access kind) {
        int offset = address & 0xFFFF;
        if (((address >>> 16) & 0x40) == 0 && offset >= 0x2200 && offset < 0x2300) {
            registerWritten(offset, value);
        } else {
            recordSa1(address, kind);
        }
    }

    /** The SA-1 CPU's side as a {@link BusMap}, for its bus wrapper. */
    BusMap sa1Side() {
        return new BusMap() {
            @Override
            public void read(int address, Access kind) {
                recordSa1(address, kind);
            }

            @Override
            public void write(int address, int value, Access kind) {
                recordSa1Write(address, value, kind);
            }
        };
    }

    // ---- Decoding -----------------------------------------------------------------------------

    /** Follows a write to one of the board's registers ($2200-$22FF), from either CPU. */
    private void registerWritten(int register, int value) {
        switch (register) {
            case 0x2220, 0x2221, 0x2222, 0x2223 -> {
                chunk[register - 0x2220] = value & 0x07;
                loRomFollows[register - 0x2220] = (value & 0x80) != 0;
            }
            case 0x2224 -> consoleBwramBlock = value & 0x1F;
            case 0x2225 -> {
                sa1Bitmap = (value & 0x80) != 0;
                sa1BwramBlock = value & 0x7F;
            }
            case 0x223F -> bitmap2bpp = (value & 0x80) != 0;
            default -> {
                // other registers don't change the memory map
            }
        }
    }

    /**
     * A ROM access through the Super MMC (as in {@code SuperMmc.read}): both the LoROM banks
     * and the HiROM banks $C0-$FF become one 4 MiB "window space", 1 MiB per window, and each
     * window shows the chunk its register selects. LoROM banks show a fixed chunk unless
     * their register's bit 7 is set.
     */
    private void rom(int bank, int offset, Access kind) {
        boolean hiRom = (bank & 0xC0) == 0xC0;
        int linear = hiRom
                ? (bank & 0x3F) << 16 | offset
                : ((bank & 0x80) != 0 ? 0x200000 : 0) | (bank & 0x3F) << 15 | (offset & 0x7FFF);
        int window = linear >> 20;
        int romOffset = !hiRom && !loRomFollows[window] ? linear : chunk[window] << 20 | (linear & 0xFFFFF);
        recorder.record(recorder.rom, Mirroring.mirror(romOffset, romSize), kind);
    }

    private void bwram(int offset, Access kind) {
        if (recorder.cartRam != null) {
            recorder.record(recorder.cartRam, Mirroring.mirror(offset, recorder.cartRam.size()), kind);
        }
    }

    /** The bitmap view: each address is one 2- or 4-bit pixel, packed into BW-RAM bytes. */
    private void bitmap(int pixel, Access kind) {
        bwram(bitmap2bpp ? pixel >> 2 : pixel >> 1, kind);
    }

    private void iram(int offset, Access kind) {
        recorder.record(recorder.iram, offset, kind);
    }
}
