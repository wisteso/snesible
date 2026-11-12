package jfc.cartridge;

import java.nio.charset.StandardCharsets;

/**
 * The 64-byte block that every SNES game stores at CPU address $00:FFC0-$00:FFFF.
 *
 * <pre>
 *   $FFC0-$FFD4  Game title, 21 bytes, ASCII padded with spaces
 *   $FFD5        Map mode: 001S_MMMM (S = FastROM, MMMM = board layout)
 *   $FFD6        Cartridge type: which chips are on the board (RAM, battery, coprocessor)
 *   $FFD7        ROM size as a power of two: 1 KiB &lt;&lt; n
 *   $FFD8        SRAM size as a power of two: 1 KiB &lt;&lt; n (0 = none)
 *   $FFD9        Destination region code
 *   $FFDA        Developer ID ($33 means an extended header sits just below $FFC0)
 *   ($FFBD)      In that extended header: expansion RAM size, 1 KiB &lt;&lt; n. SuperFX games
 *                use it for the GSU's RAM.
 *   $FFDB        Version number
 *   $FFDC-$FFDD  Checksum complement (checksum XOR $FFFF)
 *   $FFDE-$FFDF  Checksum (16-bit sum of every ROM byte)
 *   $FFE0-$FFEF  Interrupt vectors for native (65816) mode
 *   $FFF0-$FFFF  Interrupt vectors for emulation (6502-compatible) mode
 * </pre>
 *
 * <p>The vectors aren't strictly part of the header, but they share the same 64 bytes, and the
 * RESET vector tells us where the game's code starts.
 *
 * @param headerFileOffset where in the ROM file the header was found
 * @param vectors          the 65816 interrupt vectors from $FFE0-$FFFF
 * @param expansionRamSizeByte $FFBD from the extended header, or 0 when there's no extended
 *                         header (developer ID other than $33)
 */
public record CartridgeHeader(
        int headerFileOffset,
        String title,
        int mapModeByte,
        int cartridgeTypeByte,
        int romSizeByte,
        int sramSizeByte,
        int regionCode,
        int developerId,
        int version,
        int checksumComplement,
        int checksum,
        Vectors vectors,
        int expansionRamSizeByte) {

    /** Size of the header block plus the interrupt vectors that follow it. */
    public static final int LENGTH = 0x40;

    /**
     * The addresses the 65816 jumps to when an interrupt or reset occurs. Native mode is the
     * 65816's own 16-bit mode; emulation mode is the 6502-compatible mode the CPU starts in.
     * Emulation mode has no separate BRK vector (BRK shares the IRQ vector, as on the 6502), and
     * native mode has no RESET vector because reset always switches to emulation mode.
     */
    public record Vectors(
            int nativeCop, int nativeBrk, int nativeAbort, int nativeNmi, int nativeIrq,
            int emulationCop, int emulationAbort, int emulationNmi, int emulationReset,
            int emulationIrqBrk) {
    }

    /** Reads the header found at {@code offset} within {@code rom}. */
    public static CartridgeHeader parse(byte[] rom, int offset) {
        String title = new String(rom, offset, 21, StandardCharsets.US_ASCII).stripTrailing();
        int developerId = readByte(rom, offset + 0x1A);
        // The extended header (16 bytes at $FFB0-$FFBF) only exists when the developer ID is $33.
        int expansionRamSizeByte = developerId == 0x33 && offset >= 3 ? readByte(rom, offset - 0x03) : 0;
        Vectors vectors = new Vectors(
                readWord(rom, offset + 0x24), // $FFE4
                readWord(rom, offset + 0x26), // $FFE6
                readWord(rom, offset + 0x28), // $FFE8
                readWord(rom, offset + 0x2A), // $FFEA
                readWord(rom, offset + 0x2E), // $FFEE
                readWord(rom, offset + 0x34), // $FFF4
                readWord(rom, offset + 0x38), // $FFF8
                readWord(rom, offset + 0x3A), // $FFFA
                readWord(rom, offset + 0x3C), // $FFFC
                readWord(rom, offset + 0x3E)); // $FFFE
        return new CartridgeHeader(
                offset,
                title,
                readByte(rom, offset + 0x15),
                readByte(rom, offset + 0x16),
                readByte(rom, offset + 0x17),
                readByte(rom, offset + 0x18),
                readByte(rom, offset + 0x19),
                developerId,
                readByte(rom, offset + 0x1B),
                readWord(rom, offset + 0x1C),
                readWord(rom, offset + 0x1E),
                vectors,
                expansionRamSizeByte);
    }

    /**
     * FastROM carts use faster ROM chips. When the game also sets the MEMSEL register ($420D),
     * the CPU accesses ROM in banks $80-$FF in 6 master cycles instead of 8.
     */
    public boolean isFastRom() {
        return (mapModeByte & 0x10) != 0;
    }

    /** ROM size claimed by the header. The real file size is what counts for emulation. */
    public int declaredRomSizeBytes() {
        return romSizeByte == 0 ? 0 : 1024 << romSizeByte;
    }

    /** Battery-backed or work SRAM on the cartridge, in bytes. */
    public int sramSizeBytes() {
        return sramSizeByte == 0 ? 0 : 1024 << sramSizeByte;
    }

    public Region region() {
        return Region.fromCode(regionCode);
    }

    /** The checksum and its complement must XOR to $FFFF in a well-formed header. */
    public boolean hasConsistentChecksumPair() {
        return (checksum ^ checksumComplement) == 0xFFFF;
    }

    /**
     * Describes the chips on the board, decoded from the cartridge type byte. The low nibble says
     * which memories are present; the high nibble names the coprocessor when there is one.
     */
    public String describeCartridgeType() {
        int memories = cartridgeTypeByte & 0x0F;
        if (memories <= 0x02) {
            return switch (memories) {
                case 0x00 -> "ROM";
                case 0x01 -> "ROM + RAM";
                default -> "ROM + RAM + battery";
            };
        }
        String coprocessor = switch (cartridgeTypeByte >> 4) {
            case 0x0 -> "DSP";
            case 0x1 -> "SuperFX";
            case 0x2 -> "OBC1";
            case 0x3 -> "SA-1";
            case 0x4 -> "S-DD1";
            case 0x5 -> "S-RTC";
            case 0xE -> "other";
            case 0xF -> "custom";
            default -> "unknown";
        };
        return switch (memories) {
            case 0x03 -> "ROM + " + coprocessor;
            case 0x04 -> "ROM + " + coprocessor + " + RAM";
            case 0x05 -> "ROM + " + coprocessor + " + RAM + battery";
            case 0x06 -> "ROM + " + coprocessor + " + battery";
            default -> "unknown ($" + Integer.toHexString(cartridgeTypeByte) + ")";
        };
    }

    /** True when the board carries a coprocessor that we would have to emulate as well. */
    public boolean hasCoprocessor() {
        return (cartridgeTypeByte & 0x0F) >= 0x03;
    }

    /** Which coprocessor the board carries, from the high nibble of the cartridge type byte. */
    public CoprocessorType coprocessorType() {
        if (!hasCoprocessor()) {
            return CoprocessorType.NONE;
        }
        return switch (cartridgeTypeByte >> 4) {
            case 0x0 -> CoprocessorType.DSP;
            case 0x1 -> CoprocessorType.SUPER_FX;
            case 0x3 -> CoprocessorType.SA1;
            default -> CoprocessorType.OTHER;
        };
    }

    /** Expansion RAM from the extended header ($FFBD), in bytes; 0 when not declared. */
    public int expansionRamSizeBytes() {
        return expansionRamSizeByte == 0 ? 0 : 1024 << expansionRamSizeByte;
    }

    /**
     * True when the cartridge has a battery keeping its SRAM alive with the power off, i.e. the
     * game can save. Cartridges without one use SRAM only as scratch memory.
     */
    public boolean hasBattery() {
        int memories = cartridgeTypeByte & 0x0F;
        return memories == 0x02 || memories == 0x05 || memories == 0x06;
    }

    static int readByte(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }

    /** The 65816 is little-endian: the low byte comes first. */
    static int readWord(byte[] data, int offset) {
        return readByte(data, offset) | (readByte(data, offset + 1) << 8);
    }
}
