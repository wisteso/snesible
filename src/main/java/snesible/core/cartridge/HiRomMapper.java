package snesible.core.cartridge;

import static snesible.core.cartridge.Mapper.bankOf;
import static snesible.core.cartridge.Mapper.offsetInBank;

/**
 * The HiROM board layout.
 *
 * <pre>
 *   Banks $40-$7D, $C0-$FF  at $0000-$FFFF  ->  ROM, 64 KiB per bank
 *   Banks $00-$3F, $80-$BF  at $8000-$FFFF  ->  ROM (the upper half of the same 64 KiB banks)
 *   Banks $20-$3F, $A0-$BF  at $6000-$7FFF  ->  SRAM, 8 KiB window per bank
 * </pre>
 *
 * <p>The low six bits of the bank pick a 64 KiB slice of ROM. In banks $00-$3F the lower half of
 * each bank is taken by RAM and I/O, so only the upper 32 KiB of each slice shows there.
 *
 * <p><b>Debug support:</b> the class and its constructor are public only so the visual memory
 * debugger ({@code snesible.debug}) can build its own mapper to turn bus addresses into ROM and
 * SRAM offsets. Emulation itself only uses it through {@link Cartridge}.
 */
public final class HiRomMapper implements Mapper {

    private final int romSize;
    private final int sramSize;

    public HiRomMapper(int romSize, int sramSize) {
        this.romSize = romSize;
        this.sramSize = sramSize;
    }

    @Override
    public int romOffset(int address) {
        int bank = bankOf(address);
        int offset = offsetInBank(address);
        if (bank == 0x7E || bank == 0x7F) {
            return NOT_MAPPED;
        }
        boolean fullBank = (bank & 0x40) != 0;
        if (!fullBank && offset < 0x8000) {
            return NOT_MAPPED;
        }
        int linear = ((bank & 0x3F) << 16) | offset;
        return Mirroring.mirror(linear, romSize);
    }

    @Override
    public int sramOffset(int address) {
        int bank = bankOf(address);
        int offset = offsetInBank(address);
        boolean sramBank = (bank & 0x7F) >= 0x20 && (bank & 0x7F) <= 0x3F;
        if (sramSize == 0 || !sramBank || offset < 0x6000 || offset >= 0x8000) {
            return NOT_MAPPED;
        }
        int linear = (bank & 0x1F) * 0x2000 + (offset - 0x6000);
        return Mirroring.mirror(linear, sramSize);
    }
}
