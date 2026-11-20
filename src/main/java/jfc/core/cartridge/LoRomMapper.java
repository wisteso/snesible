package jfc.core.cartridge;

import static jfc.core.cartridge.Mapper.bankOf;
import static jfc.core.cartridge.Mapper.offsetInBank;

/**
 * The LoROM board layout.
 *
 * <pre>
 *   Banks $00-$7D, $80-$FF  at $8000-$FFFF  ->  ROM, 32 KiB per bank
 *   Banks $70-$7D, $F0-$FF  at $0000-$7FFF  ->  SRAM (when the cart has any)
 * </pre>
 *
 * <p>The board doesn't connect CPU address line A15, which is set in exactly the upper half of
 * every bank. Each bank therefore contributes 32 KiB, and the ROM offset is
 * {@code bank * $8000 + (address - $8000)}. Bank bit 7 isn't connected either, so banks
 * $80-$FF show the same ROM as $00-$7F. Games run from the upper copy when they want FastROM
 * timing.
 *
 * <p>Banks $7E-$7F always hold the console's 128 KiB work RAM, so they're never cartridge space.
 *
 * <p><b>Debug support:</b> the class and its constructor are public only so the visual memory
 * debugger ({@code jfc.debug}) can build its own mapper to turn bus addresses into ROM and
 * SRAM offsets. Emulation itself only uses it through {@link Cartridge}.
 */
public final class LoRomMapper implements Mapper {

    private final int romSize;
    private final int sramSize;

    public LoRomMapper(int romSize, int sramSize) {
        this.romSize = romSize;
        this.sramSize = sramSize;
    }

    @Override
    public int romOffset(int address) {
        int bank = bankOf(address);
        int offset = offsetInBank(address);
        if (isWorkRamBank(bank) || offset < 0x8000) {
            return NOT_MAPPED;
        }
        int linear = (bank & 0x7F) * 0x8000 + (offset - 0x8000);
        return Mirroring.mirror(linear, romSize);
    }

    @Override
    public int sramOffset(int address) {
        int bank = bankOf(address);
        int offset = offsetInBank(address);
        boolean sramBank = (bank & 0x7F) >= 0x70 && !isWorkRamBank(bank);
        if (sramSize == 0 || !sramBank || offset >= 0x8000) {
            return NOT_MAPPED;
        }
        int linear = (bank & 0x0F) * 0x8000 + offset;
        return Mirroring.mirror(linear, sramSize);
    }

    private static boolean isWorkRamBank(int bank) {
        return bank == 0x7E || bank == 0x7F;
    }
}
