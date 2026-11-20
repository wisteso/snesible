package snesible.core.coprocessor.sa1;

import snesible.core.cartridge.Mirroring;

/**
 * The SA-1's ROM bank switcher ("Super MMC"). It lets a game with up to 8 MiB of ROM fit into
 * the 4 MiB the address map has room for, by mapping 1 MiB chunks of ROM into four windows.
 *
 * <pre>
 *   window C:  $00-$1F:8000-FFFF (LoROM view)   and  $C0-$CF:0000-FFFF (HiROM view)
 *   window D:  $20-$3F:8000-FFFF                and  $D0-$DF:0000-FFFF
 *   window E:  $80-$9F:8000-FFFF                and  $E0-$EF:0000-FFFF
 *   window F:  $A0-$BF:8000-FFFF                and  $F0-$FF:0000-FFFF
 * </pre>
 * Registers $2220-$2223 (CXB, DXB, EXB, FXB) choose the 1 MiB chunk for each window (bits 2-0).
 * The HiROM views always follow the registers. The LoROM views follow them only when bit 7 is
 * set; otherwise they show chunks 0-3 in order, so the interrupt vectors at $00:FFxx stay put
 * whatever the game maps elsewhere.
 *
 * <p>Both the console and the SA-1 CPU see the ROM through the same registers.
 */
final class SuperMmc {

    private final byte[] rom;
    /** The 1 MiB chunk shown in windows C, D, E, F. */
    private final int[] chunk = new int[4];
    /** Bit 7 of each register: the LoROM view follows the register too. */
    private final boolean[] loRomFollows = new boolean[4];

    SuperMmc(byte[] rom) {
        this.rom = rom;
        reset();
    }

    void reset() {
        for (int i = 0; i < 4; i++) {
            chunk[i] = i;
            loRomFollows[i] = false;
        }
    }

    /** $2220-$2223: {@code window} 0-3 for C-F. */
    void writeBank(int window, int value) {
        chunk[window] = value & 0x07;
        loRomFollows[window] = (value & 0x80) != 0;
    }

    /** True for addresses in either ROM view. */
    static boolean isRom(int bank, int offset) {
        boolean hiRom = (bank & 0xC0) == 0xC0;
        boolean loRom = (bank & 0x40) == 0 && offset >= 0x8000;
        return hiRom || loRom;
    }

    /** Reads the ROM byte at a CPU address in either view (see {@link #isRom}). */
    int read(int bank, int offset) {
        boolean hiRom = (bank & 0xC0) == 0xC0;
        // Turn both views into one 4 MiB "window space", where each 1 MiB is one window.
        int linear;
        if (hiRom) {
            linear = (bank & 0x3F) << 16 | offset;
        } else {
            linear = ((bank & 0x80) != 0 ? 0x200000 : 0) | (bank & 0x3F) << 15 | (offset & 0x7FFF);
        }
        int window = linear >> 20;
        int romOffset;
        if (!hiRom && !loRomFollows[window]) {
            romOffset = linear;                                   // fixed chunk = window number
        } else {
            romOffset = chunk[window] << 20 | (linear & 0xFFFFF);
        }
        return rom[Mirroring.mirror(romOffset, rom.length)] & 0xFF;
    }
}
