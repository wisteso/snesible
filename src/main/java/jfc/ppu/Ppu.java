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
 * register, then stream bytes through a data port.
 *
 * <p>This class holds that state and the register interface. Drawing is done by the
 * {@link Renderer}, which reads the state here once per scanline.
 *
 * <p><b>Not yet decoded</b> (stored raw until Milestone 6): mosaic ($2106), Mode 7 matrix and
 * center ($211A, $211D-$2120), windows ($2123-$212B, $212E-$212F) and color math
 * ($2130-$2132).
 */
public final class Ppu {

    public static final int VRAM_WORDS = 0x8000;
    public static final int CGRAM_COLORS = 256;
    public static final int OAM_BYTES = 544;

    private final MasterClock clock;
    private final boolean pal;
    private final Renderer renderer;

    final int[] vram = new int[VRAM_WORDS];
    final int[] cgram = new int[CGRAM_COLORS];
    final int[] oam = new int[OAM_BYTES];

    /** Raw values of registers that aren't decoded yet (see class comment). */
    final int[] rawRegisters = new int[0x40];

    // ---- $2100 INIDISP: display control
    /** Forced blank turns the picture off so VRAM can be accessed safely at any time. */
    boolean forcedBlank = true;
    /** Master brightness, 0 (black) to 15 (full). */
    int brightness;

    // ---- $2101 OBSEL: sprite graphics location and sizes
    /** VRAM word address of the first sprite tile table. */
    int objectTileBase;
    /** Word distance from the first sprite tile table to the second, minus $1000. */
    int objectTileGap;
    /** Which pair of sprite sizes (small/large) is in use, 0-7. */
    int objectSizeSelect;

    // ---- $2105 BGMODE
    int bgMode;
    /** In mode 1, lifts BG3's high-priority tiles in front of everything (used for status bars). */
    boolean bg3HighPriority;

    final BackgroundLayer[] backgrounds = {
        new BackgroundLayer(0), new BackgroundLayer(1), new BackgroundLayer(2), new BackgroundLayer(3),
    };
    /** The shared latch for the write-twice scroll registers ($210D-$2114). */
    private int scrollLatch;

    // ---- $212C TM / $212D TS: which layers are drawn on the main and sub screens
    /** Bits 0-3 = BG1-BG4, bit 4 = sprites. */
    int mainScreenLayers;
    int subScreenLayers;

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
    int oamReloadAddress;
    /** With priority rotation, the sprite at the OAM address is drawn in front (instead of #0). */
    boolean objectPriorityRotation;
    private int oamWriteLatch;

    // ---- Sprite overflow flags (read in $213E)
    /** More than 32 sprites were found on one line. */
    boolean rangeOver;
    /** More than 34 sprite tiles (8 pixels wide each) were needed on one line. */
    boolean timeOver;

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
        this.renderer = new Renderer(this);
    }

    /** True in overscan mode ($2133 bit 2): 239 visible lines instead of 224. */
    public boolean isOverscan() {
        return (rawRegisters[0x33] & 0x04) != 0;
    }

    /** How many picture lines a frame has: 224, or 239 with overscan. */
    public int visibleLines() {
        return isOverscan() ? 239 : 224;
    }

    /** The first line of vertical blank (when NMI fires): just after the last picture line. */
    public int vblankStartLine() {
        return visibleLines() + 1;
    }

    public boolean isForcedBlank() {
        return forcedBlank;
    }

    /** The current background mode, 0-7. */
    public int bgMode() {
        return bgMode;
    }

    /** The finished picture: 256 x {@link Renderer#MAX_LINES} pixels as 0xRRGGBB. */
    public int[] frameBuffer() {
        return renderer.frameBuffer();
    }

    /**
     * Called by the system at the start of every scanline.
     *
     * <p>Line 0 is never shown, so picture line {@code n} is drawn while the beam is on line
     * {@code n + 1}. We draw a whole line at once, at its start, using the registers as they are
     * at that moment. A game changing registers in the middle of a line would see the change
     * only on the next line. That's the cost of a scanline renderer, and few games care.
     */
    public void onLineStart(int line) {
        if (line == 0) {
            rangeOver = false;
            timeOver = false;
        }
        if (line >= 1 && line <= visibleLines()) {
            renderer.renderLine(line - 1);
        }
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
                brightness = value & 0x0F;
            }
            case 0x01 -> writeObjectSelect(value);
            case 0x02 -> setOamAddress((oamReloadAddress & 0x200) | (value << 1));
            case 0x03 -> {
                setOamAddress(((value & 1) << 9) | (oamReloadAddress & 0x1FE));
                objectPriorityRotation = (value & 0x80) != 0;
            }
            case 0x04 -> writeOam(value);
            case 0x05 -> writeBgMode(value);
            case 0x07, 0x08, 0x09, 0x0A -> backgrounds[register - 0x07].writeTilemapSettings(value);
            case 0x0B -> {
                backgrounds[0].tileBase = (value & 0x0F) << 12;
                backgrounds[1].tileBase = (value >> 4) << 12;
            }
            case 0x0C -> {
                backgrounds[2].tileBase = (value & 0x0F) << 12;
                backgrounds[3].tileBase = (value >> 4) << 12;
            }
            // Scroll registers come in pairs, H then V, for BG1 through BG4.
            case 0x0D, 0x0F, 0x11, 0x13 -> writeHorizontalScroll(backgrounds[(register - 0x0D) / 2], value);
            case 0x0E, 0x10, 0x12, 0x14 -> writeVerticalScroll(backgrounds[(register - 0x0E) / 2], value);
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
            case 0x2C -> mainScreenLayers = value & 0x1F;
            case 0x2D -> subScreenLayers = value & 0x1F;
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
            // STAT77: sprite overflow flags, PPU1 version 1
            case 0x3E -> (timeOver ? 0x80 : 0) | (rangeOver ? 0x40 : 0) | 0x01;
            case 0x3F -> readStatus78();
            default -> -1;
        };
    }

    // =============================================================================================
    // Background and sprite settings
    // =============================================================================================

    /**
     * $2105 BGMODE.
     * <pre>
     *   bits 7-4  tile size for BG4..BG1: 0 = 8x8, 1 = 16x16
     *   bit 3     BG3 high priority (mode 1 only)
     *   bits 2-0  background mode 0-7
     * </pre>
     */
    private void writeBgMode(int value) {
        bgMode = value & 0x07;
        bg3HighPriority = (value & 0x08) != 0;
        for (int i = 0; i < 4; i++) {
            backgrounds[i].bigTiles = (value & (0x10 << i)) != 0;
        }
    }

    /**
     * $2101 OBSEL.
     * <pre>
     *   bits 7-5  size pair (see SpriteLayer)
     *   bits 4-3  gap between the two sprite tile tables, in $1000-word steps (plus one)
     *   bits 2-0  base of the first table, in $2000-word (16 KiB) steps
     * </pre>
     */
    private void writeObjectSelect(int value) {
        objectTileBase = (value & 0x07) << 13;
        objectTileGap = ((value >> 3) & 0x03) << 12;
        objectSizeSelect = (value >> 5) & 0x07;
    }

    /**
     * Horizontal scroll is 10 bits, written as two bytes to one address. The registers for all
     * four BGs share one latch, and the low 3 bits are mixed oddly: they come from the previous
     * write, but only bits above them are taken from it. The formula is from hardware tests.
     */
    private void writeHorizontalScroll(BackgroundLayer bg, int value) {
        bg.hScroll = ((value << 8) | (scrollLatch & ~7) | ((bg.hScroll >> 8) & 7)) & 0x3FF;
        scrollLatch = value;
    }

    private void writeVerticalScroll(BackgroundLayer bg, int value) {
        bg.vScroll = ((value << 8) | scrollLatch) & 0x3FF;
        scrollLatch = value;
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
