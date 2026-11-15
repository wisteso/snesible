package jfc.core.cartridge;

/**
 * Translates 24-bit CPU addresses into offsets within the cartridge's ROM and SRAM chips.
 *
 * <p>A CPU address is written as {@code $BB:AAAA}: an 8-bit bank and a 16-bit address within that
 * bank. Throughout this emulator the two are packed into one {@code int} as
 * {@code (bank << 16) | address}.
 *
 * <p>This plays the role of the NES mapper, but it's much simpler: the SNES CPU can already
 * address 16 MiB, so plain games need no bank-switching registers. The board only decides which
 * address ranges belong to which chip.
 */
public interface Mapper {

    /** Returned when the address doesn't select the chip in question. */
    int NOT_MAPPED = -1;

    /** The ROM offset for a CPU address, or {@link #NOT_MAPPED} if the address isn't ROM. */
    int romOffset(int address);

    /** The SRAM offset for a CPU address, or {@link #NOT_MAPPED} if the address isn't SRAM. */
    int sramOffset(int address);

    static int bankOf(int address) {
        return (address >>> 16) & 0xFF;
    }

    static int offsetInBank(int address) {
        return address & 0xFFFF;
    }
}
