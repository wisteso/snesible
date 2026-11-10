package jfc.ppu;

import jfc.system.MasterClock;

/**
 * The Picture Processing Unit: really two chips (PPU1 and PPU2) working as one.
 *
 * <p>The PPU owns three memories that the CPU can't address directly:
 * <ul>
 *   <li><b>VRAM</b>, 64 KiB organized as 32K 16-bit words: tile graphics and tilemaps.</li>
 *   <li><b>CGRAM</b>, 256 colors of 15 bits: the palette.</li>
 *   <li><b>OAM</b>, 544 bytes: attributes for 128 sprites ("objects").</li>
 * </ul>
 * The CPU (or DMA) reaches them only through registers $2100-$213F on the B-bus: set an address
 * register, then stream bytes through a data port. That's why VRAM updates go through DMA.
 *
 * <p><b>Milestone 3 status:</b> the memory ports, counter latches and Mode 7 multiplier work. The
 * picture-related registers ($2101 and $2105-$2133) are stored raw, and rendering arrives in
 * Milestone 4.
 */
public final class Ppu {

    public static final int VRAM_WORDS = 0x8000;
    public static final int CGRAM_COLORS = 256;
    public static final int OAM_BYTES = 544;

    private final MasterClock clock;
    private final boolean pal;

    final int[] vram = new int[VRAM_WORDS];
    final int[] cgram = new int[CGRAM_COLORS];
    final int[] oam = new int[OAM_BYTES];

    /** Raw values of write-only registers $2100-$213F not yet decoded (until Milestone 4). */
    private final int[] rawRegisters = new int[0x40];

    // ---- $2100 INIDISP
    private boolean forcedBlank = true;

    // ---- VRAM port ($2115-$2119, $2139-$213A)
    private int vramAddress;
    private int vramIncrement = 1;
    private boolean incrementOnHighByte;
    private int vramAddressRemap;
    /** VRAM reads go through a one-word prefetch buffer, so reads lag one word behind. */
    private int vramReadBuffer;

    // ---- CGRAM port ($2121-$2122, $213B)
    private int cgramAddress;
    /** CGRAM entries are 15 bits, written as two bytes; the first is held until the second. */
    private int cgramWriteLatch;
    private boolean cgramSecondByte;

    // ---- OAM port ($2102-$2104, $2138)
    private int oamAddress;
    /** The address games set; the PPU returns to it at the start of every vertical blank. */
    private int oamReloadAddress;
    private int oamWriteLatch;

    // ---- Mode 7 multiplier ($211B-$211C, $2134-$2136)
    private int mode7Latch;
    private int mode7A;
    private int mode7B;

    // ---- H/V counter latch ($2137, $213C-$213D, $213F)
    private int latchedH;
    private int latchedV;
    private boolean countersLatched;
    private boolean readHighH;
    private boolean readHighV;

    public Ppu(MasterClock clock, boolean pal) {
        this.clock = clock;
        this.pal = pal;
    }

    /**
     * The first line of vertical blank. Overscan mode ($2133 bit 2) shows 239 lines instead of
     * 224, which pushes vertical blank and NMI later.
     */
    public int vblankStartLine() {
        boolean overscan = (rawRegisters[0x33] & 0x04) != 0;
        return overscan ? 240 : 225;
    }

    public boolean isForcedBlank() {
        return forcedBlank;
    }

    /** Called by the system at the start of every scanline. */
    public void onLineStart(int line) {
        if (line == vblankStartLine() && !forcedBlank) {
            oamAddress = oamReloadAddress;
        }
    }

    // =============================================================================================
    // CPU-facing registers
    // =============================================================================================

    /**
     * Writes a PPU register.
     *
     * @param register the low byte of the address ($00 for $2100 ... $3F for $213F)
     */
    public void write(int register, int value) {
        switch (register) {
            case 0x00 -> {
                forcedBlank = (value & 0x80) != 0;
                rawRegisters[register] = value;
            }
            case 0x02 -> setOamAddress((oamReloadAddress & 0x200) | (value << 1));
            case 0x03 -> setOamAddress(((value & 1) << 9) | (oamReloadAddress & 0x1FE));
            case 0x04 -> writeOam(value);
            case 0x15 -> writeVramControl(value);
            case 0x16 -> setVramAddress((vramAddress & 0xFF00) | value);
            case 0x17 -> setVramAddress((vramAddress & 0x00FF) | (value << 8));
            case 0x18 -> writeVram(value, false);
            case 0x19 -> writeVram(value, true);
            case 0x1B -> mode7A = writeMode7Register(value);
            case 0x1C -> mode7B = writeMode7Register(value);
            case 0x21 -> {
                cgramAddress = value;
                cgramSecondByte = false;
            }
            case 0x22 -> writeCgram(value);
            default -> {
                if (register < rawRegisters.length) {
                    rawRegisters[register] = value;
                }
            }
        }
    }

    /**
     * Reads a PPU register.
     *
     * @return the value, or -1 for write-only registers (the bus then supplies open bus)
     */
    public int read(int register) {
        return switch (register) {
            case 0x34 -> mode7Product() & 0xFF;
            case 0x35 -> (mode7Product() >> 8) & 0xFF;
            case 0x36 -> (mode7Product() >> 16) & 0xFF;
            case 0x37 -> {
                latchCounters();
                yield -1; // reading SLHV latches the counters; the value itself is open bus
            }
            case 0x38 -> readOam();
            case 0x39 -> readVram(false);
            case 0x3A -> readVram(true);
            case 0x3B -> readCgram();
            case 0x3C -> readCounter(latchedH, true);
            case 0x3D -> readCounter(latchedV, false);
            case 0x3E -> 0x01; // STAT77: PPU1 version 1, no sprite overflow flags yet
            case 0x3F -> readStatus78();
            default -> -1;
        };
    }

    // =============================================================================================
    // VRAM
    // =============================================================================================

    /**
     * $2115 VMAIN: how the VRAM address advances after each access.
     * <pre>
     *   bit 7     1 = increment after writing the high byte ($2119), 0 = after the low ($2118)
     *   bits 3-2  address remapping (for converting bitmap layouts into tiles, see below)
     *   bits 1-0  step: 1, 32, 128 or 128 words (32 walks down a tilemap column)
     * </pre>
     */
    private void writeVramControl(int value) {
        incrementOnHighByte = (value & 0x80) != 0;
        vramAddressRemap = (value >> 2) & 3;
        vramIncrement = switch (value & 3) {
            case 0 -> 1;
            case 1 -> 32;
            default -> 128;
        };
    }

    private void setVramAddress(int address) {
        vramAddress = address & 0x7FFF;
        // Setting the address also fills the read buffer, so the first read after it returns
        // the word at the new address.
        vramReadBuffer = vram[remappedVramAddress()];
    }

    /**
     * Address remapping rotates the low bits of the address so a CPU writing pixel rows in order
     * lands them in the PPU's tile layout. For n = 8, 9 or 10 bits it turns
     * {@code ...rrrccccc} (3-bit row, then column bits) into {@code ...cccccrrr}.
     */
    private int remappedVramAddress() {
        if (vramAddressRemap == 0) {
            return vramAddress;
        }
        int bits = 7 + vramAddressRemap; // 8, 9 or 10 bits take part
        int mask = (1 << bits) - 1;
        int low = vramAddress & mask;
        int rotated = ((low << 3) & mask) | (low >> (bits - 3));
        return (vramAddress & ~mask) | rotated;
    }

    private void writeVram(int value, boolean highByte) {
        int address = remappedVramAddress();
        if (highByte) {
            vram[address] = (vram[address] & 0x00FF) | (value << 8);
        } else {
            vram[address] = (vram[address] & 0xFF00) | value;
        }
        if (highByte == incrementOnHighByte) {
            vramAddress = (vramAddress + vramIncrement) & 0x7FFF;
        }
    }

    /**
     * $2139/$213A: the PPU returns the buffered word, then refills the buffer from the
     * current address before stepping the address forward.
     */
    private int readVram(boolean highByte) {
        int value = highByte ? vramReadBuffer >> 8 : vramReadBuffer & 0xFF;
        if (highByte == incrementOnHighByte) {
            vramReadBuffer = vram[remappedVramAddress()];
            vramAddress = (vramAddress + vramIncrement) & 0x7FFF;
        }
        return value;
    }

    // =============================================================================================
    // CGRAM (palette)
    // =============================================================================================

    /** Colors are 15-bit BGR: {@code 0bbbbbgg gggrrrrr}. The low byte comes first. */
    private void writeCgram(int value) {
        if (!cgramSecondByte) {
            cgramWriteLatch = value;
        } else {
            cgram[cgramAddress] = ((value & 0x7F) << 8) | cgramWriteLatch;
            cgramAddress = (cgramAddress + 1) & 0xFF;
        }
        cgramSecondByte = !cgramSecondByte;
    }

    private int readCgram() {
        int color = cgram[cgramAddress];
        int value;
        if (!cgramSecondByte) {
            value = color & 0xFF;
        } else {
            value = color >> 8;
            cgramAddress = (cgramAddress + 1) & 0xFF;
        }
        cgramSecondByte = !cgramSecondByte;
        return value;
    }

    // =============================================================================================
    // OAM (sprite attributes)
    // =============================================================================================

    /**
     * OAM is a 512-byte low table (4 bytes per sprite: X, Y, tile, attributes) plus a 32-byte
     * high table (2 bits per sprite: X bit 8 and size). The address register counts bytes; games
     * set it as a word address, which is why $2102 is shifted left by one.
     */
    private void setOamAddress(int byteAddress) {
        oamReloadAddress = byteAddress & 0x3FF;
        oamAddress = oamReloadAddress;
    }

    /**
     * Writes to the low table are buffered in pairs so a sprite's 16-bit halves change together.
     * Writes to the high table go straight in.
     */
    private void writeOam(int value) {
        if (oamAddress < 0x200) {
            if ((oamAddress & 1) == 0) {
                oamWriteLatch = value;
            } else {
                oam[oamAddress - 1] = oamWriteLatch;
                oam[oamAddress] = value;
            }
        } else {
            oam[0x200 | (oamAddress & 0x1F)] = value;
        }
        oamAddress = (oamAddress + 1) & 0x3FF;
    }

    private int readOam() {
        int address = oamAddress < 0x200 ? oamAddress : 0x200 | (oamAddress & 0x1F);
        oamAddress = (oamAddress + 1) & 0x3FF;
        return oam[address];
    }

    // =============================================================================================
    // Mode 7 multiplier
    // =============================================================================================

    /**
     * Mode 7 matrix registers take two writes (low byte, then high), sharing one latch. They
     * double as a fast signed multiplier: games use $2134-$2136 for math even outside Mode 7.
     */
    private int writeMode7Register(int value) {
        int combined = (value << 8) | mode7Latch;
        mode7Latch = value;
        return combined;
    }

    /** $2134-$2136 MPY: signed 16-bit M7A times signed 8-bit high byte of M7B, 24-bit result. */
    private int mode7Product() {
        return (short) mode7A * (byte) (mode7B >> 8);
    }

    // =============================================================================================
    // H/V counters
    // =============================================================================================

    /** Snapshots the beam position, so software can read where the beam was at this moment. */
    public void latchCounters() {
        latchedH = clock.dot();
        latchedV = clock.v();
        countersLatched = true;
    }

    /** The counters are 9-bit, read as two bytes through one register: low first, then bit 8. */
    private int readCounter(int counter, boolean horizontal) {
        boolean high = horizontal ? readHighH : readHighV;
        if (horizontal) {
            readHighH = !readHighH;
        } else {
            readHighV = !readHighV;
        }
        return high ? (counter >> 8) & 1 : counter & 0xFF;
    }

    /** $213F STAT78: PPU2 version, PAL flag, counter-latched flag. Reading resets the latches. */
    private int readStatus78() {
        int value = 0x03 | (pal ? 0x10 : 0) | (countersLatched ? 0x40 : 0);
        countersLatched = false;
        readHighH = false;
        readHighV = false;
        return value;
    }

    // =============================================================================================
    // Debug access
    // =============================================================================================

    /** A VRAM word, for tests and (later) the debug viewers. */
    public int peekVram(int wordAddress) {
        return vram[wordAddress & 0x7FFF];
    }

    public int peekCgram(int index) {
        return cgram[index & 0xFF];
    }

    public int peekOam(int byteAddress) {
        return oam[byteAddress];
    }
}
