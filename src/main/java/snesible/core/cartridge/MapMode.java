package snesible.core.cartridge;

/**
 * How the cartridge board wires ROM into the CPU's 24-bit address space.
 *
 * <p>The SNES CPU sees 256 banks of 64 KiB. In most banks the lower half ($0000-$7FFF) holds
 * system RAM and I/O registers, so the board has to decide where in each bank the ROM shows up.
 * There are two common layouts:
 * <ul>
 *   <li><b>LoROM</b>: ROM appears only in the upper half ($8000-$FFFF) of each bank, so each bank
 *       holds 32 KiB of ROM. Simple wiring that ignores CPU address line A15.</li>
 *   <li><b>HiROM</b>: ROM fills whole 64 KiB banks in $40-$7D / $C0-$FF and is mirrored into the
 *       upper halves of banks $00-$3F / $80-$BF.</li>
 * </ul>
 *
 * <p>Each layout puts its internal header just below the CPU's interrupt vectors at $00:FFC0. On a
 * LoROM board, $00:FFC0 lands at file offset $7FC0; on a HiROM board it lands at $FFC0. The loader
 * finds the layout by checking which of these offsets holds a plausible header.
 */
public enum MapMode {
    LOROM("LoROM", 0x007FC0),
    HIROM("HiROM", 0x00FFC0),
    /** "Extended HiROM", used by a handful of 6 MiB games. Recognized but not emulated yet. */
    EXHIROM("ExHiROM", 0x40FFC0);

    private final String displayName;
    private final int headerFileOffset;

    MapMode(String displayName, int headerFileOffset) {
        this.displayName = displayName;
        this.headerFileOffset = headerFileOffset;
    }

    /** Where in the ROM file this layout puts the internal header (CPU address $00:FFC0). */
    public int headerFileOffset() {
        return headerFileOffset;
    }

    /**
     * Whether a header's map mode byte agrees with this layout. The byte is 001S_MMMM, where S
     * marks FastROM and MMMM is the layout ID. LoROM boards with coprocessors (S-DD1, SA-1) use
     * their own IDs but still keep the header at the LoROM position.
     */
    boolean acceptsMapModeByte(int mapModeByte) {
        if ((mapModeByte & 0xE0) != 0x20) {
            return false;
        }
        int layoutId = mapModeByte & 0x0F;
        return switch (this) {
            case LOROM -> layoutId == 0x0 || layoutId == 0x2 || layoutId == 0x3;
            case HIROM -> layoutId == 0x1 || layoutId == 0xA;
            case EXHIROM -> layoutId == 0x5;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
