package jfc.core.ppu;

import jfc.core.system.MasterClock;

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
 * <p><b>Not emulated yet:</b> hi-res modes 5/6 and pseudo-hi-res, interlace, offset-per-tile
 * (modes 2, 4, 6) and direct color.
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
    /**
     * Latches for the write-twice scroll registers ($210D-$2114). The scroll registers are
     * split across both PPU chips, and each chip keeps its own copy of the previous byte
     * written: PPU1's is updated by every scroll write, PPU2's only by horizontal ones.
     */
    private int scrollLatchPpu1;
    private int scrollLatchPpu2;

    // ---- $212C TM / $212D TS: which layers are drawn on the main and sub screens
    /** Bits 0-3 = BG1-BG4, bit 4 = sprites. */
    int mainScreenLayers;
    int subScreenLayers;

    // ---- $2106 MOSAIC: blocky pixelation, used for transitions
    /** Size of a mosaic block, 1 (off) to 16 pixels square. */
    int mosaicSize = 1;
    /** Bits 0-3: which of BG1-BG4 are pixelated. */
    int mosaicLayers;

    // ---- $211A-$2120 and $210D-$210E: Mode 7 (see Mode7Layer)
    int mode7Settings;
    int mode7A;
    int mode7B;
    int mode7C;
    int mode7D;
    int mode7CenterX;
    int mode7CenterY;
    int mode7HScroll;
    int mode7VScroll;

    // ---- $2123-$212B, $212E-$212F: windows (see WindowMasks)
    /** Window settings for BG1-BG4, sprites and the color window: 4 bits each. */
    final int[] windowSettings = new int[6];
    /** How each layer combines its two windows: 0 OR, 1 AND, 2 XOR, 3 XNOR. */
    final int[] windowLogic = new int[6];
    int window1Left;
    int window1Right;
    int window2Left;
    int window2Right;
    /** Which layers the windows hide on the main screen (TMW) and sub screen (TSW). */
    int mainScreenWindowedLayers;
    int subScreenWindowedLayers;

    // ---- $2130-$2132: color math (see Renderer)
    /** CGWSEL: when to black out the main screen and when to allow math; sub screen vs fixed. */
    int colorMathControl;
    /** CGADSUB: subtract/half flags and which layers take part. */
    int colorMathLayers;
    /** COLDATA: the fixed color, 15-bit BGR. It's also the sub screen's backdrop. */
    int fixedColor;

    // ---- $2133 SETINI
    /** Bit 6 EXTBG (Mode 7's second layer), bit 2 overscan; the rest isn't emulated yet. */
    int screenSettings;

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

    /** The latch shared by all write-twice Mode 7 registers (also used by the multiplier). */
    private int mode7Latch;

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
        return (screenSettings & 0x04) != 0;
    }

    /** EXTBG ($2133 bit 6): Mode 7 also shows a second layer with per-pixel priority. */
    boolean isMode7ExtBg() {
        return (screenSettings & 0x40) != 0;
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

    /**
     * The finished picture, as the PPU outputs it: not RGB, but each pixel's 15-bit color with
     * the master brightness that applies to it.
     *
     * <p><b>Pixels.</b> Each {@code int} is {@code brightness << 15 | bgr15}:
     * <pre>
     *   bits 18-15  master brightness, 0 (black) to 15 (full)
     *   bits 14-0   color, 0bbbbbgggggrrrrr (5 bits per channel, as in CGRAM)
     * </pre>
     * The higher bits are always 0. A forced-blank line is all 0 (black). Turning pixels into
     * RGB is the display's job: {@code jfc.ui.video.SnesColor} does the plain conversion, and
     * the NTSC filter and the OpenGL shaders read the raw values. ({@code Renderer.withBrightness}
     * explains why the core stops here.)
     *
     * <p><b>Layout.</b> Row after row, 256 pixels each, room for {@link Renderer#MAX_LINES}
     * rows; only the first {@link #visibleLines()} rows (224, or 239 with overscan) belong to the
     * current picture.
     *
     * <p><b>Lifetime.</b> This is the PPU's own array, not a copy: the renderer draws into it
     * line by line while a frame runs, so read it between frames (after
     * {@code Snes.runFrame()} returns) and copy what you need to keep.
     */
    public int[] frameBuffer() {
        return renderer.frameBuffer();
    }

    /** Called by the system at the start of every scanline. */
    public void onLineStart(int line) {
        if (line == 0) {
            rangeOver = false;
            timeOver = false;
        }
        if (line == vblankStartLine() && !forcedBlank) {
            oamAddress = oamReloadAddress;
        }
    }

    /**
     * Draws picture line {@code pictureLine} (0 = top) into the frame buffer.
     *
     * <p>Beam line 0 is never shown, so picture line {@code n} appears while the beam is on line
     * {@code n + 1}. The system calls this when the beam reaches that line's first visible dot
     * ({@link #FIRST_VISIBLE_DOT}). We draw the whole line at once, using the registers as they
     * are at that moment. Writes made earlier on the line, during horizontal blank, apply to it,
     * as on hardware. A write during the visible part only shows from the next line, which is
     * the cost of a scanline renderer.
     */
    public void renderLine(int pictureLine) {
        renderer.renderLine(pictureLine);
    }

    /** Pixels start appearing around dot 22; before that the beam is still in horizontal blank. */
    public static final int FIRST_VISIBLE_DOT = 22;

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
            // OAMADDL/H set a 9-bit *word* address: $2102 its low 8 bits, $2103 bit 0 its top bit.
            // We keep a byte address, so the word address is shifted left one place. Bit 9 of
            // the byte address is the top bit; bits 1-8 come from $2102; bit 0 starts at zero.
            case 0x02 -> setOamAddress((oamReloadAddress & 0x200) | (value << 1));
            case 0x03 -> {
                setOamAddress(((value & 1) << 9) | (oamReloadAddress & 0x1FE));
                objectPriorityRotation = (value & 0x80) != 0;
            }
            case 0x04 -> writeOam(value);
            case 0x05 -> writeBgMode(value);
            case 0x06 -> {
                mosaicSize = (value >> 4) + 1;
                mosaicLayers = value & 0x0F;
            }
            case 0x07, 0x08, 0x09, 0x0A -> backgrounds[register - 0x07].writeTilemapSettings(value);
            case 0x0B -> {
                backgrounds[0].tileBase = (value & 0x0F) << 12;
                backgrounds[1].tileBase = (value >> 4) << 12;
            }
            case 0x0C -> {
                backgrounds[2].tileBase = (value & 0x0F) << 12;
                backgrounds[3].tileBase = (value >> 4) << 12;
            }
            // Scroll registers come in pairs, H then V, for BG1 through BG4. BG1's pair also
            // sets the Mode 7 scroll, which is 13 bits and uses the Mode 7 latch instead.
            case 0x0D -> {
                writeHorizontalScroll(backgrounds[0], value);
                mode7HScroll = writeMode7Register(value) & 0x1FFF;
            }
            case 0x0E -> {
                writeVerticalScroll(backgrounds[0], value);
                mode7VScroll = writeMode7Register(value) & 0x1FFF;
            }
            case 0x0F, 0x11, 0x13 -> writeHorizontalScroll(backgrounds[(register - 0x0D) / 2], value);
            case 0x10, 0x12, 0x14 -> writeVerticalScroll(backgrounds[(register - 0x0E) / 2], value);
            case 0x15 -> writeVramControl(value);
            case 0x16 -> setVramAddress((vramAddress & 0xFF00) | value);
            case 0x17 -> setVramAddress((vramAddress & 0x00FF) | (value << 8));
            case 0x18 -> writeVram(value, false);
            case 0x19 -> writeVram(value, true);
            case 0x1A -> mode7Settings = value;
            case 0x1B -> mode7A = writeMode7Register(value);
            case 0x1C -> mode7B = writeMode7Register(value);
            case 0x1D -> mode7C = writeMode7Register(value);
            case 0x1E -> mode7D = writeMode7Register(value);
            case 0x1F -> mode7CenterX = writeMode7Register(value) & 0x1FFF;
            case 0x20 -> mode7CenterY = writeMode7Register(value) & 0x1FFF;
            case 0x21 -> {
                cgramAddress = value;
                cgramSecondByte = false;
            }
            case 0x22 -> writeCgram(value);
            // Window settings pack two layers per register, four bits each.
            case 0x23, 0x24, 0x25 -> {
                int firstLayer = (register - 0x23) * 2;
                windowSettings[firstLayer] = value & 0x0F;
                windowSettings[firstLayer + 1] = value >> 4;
            }
            case 0x26 -> window1Left = value;
            case 0x27 -> window1Right = value;
            case 0x28 -> window2Left = value;
            case 0x29 -> window2Right = value;
            case 0x2A -> {
                for (int bg = 0; bg < 4; bg++) {
                    windowLogic[bg] = (value >> (bg * 2)) & 3;
                }
            }
            case 0x2B -> {
                windowLogic[4] = value & 3;
                windowLogic[5] = (value >> 2) & 3;
            }
            case 0x2C -> mainScreenLayers = value & 0x1F;
            case 0x2D -> subScreenLayers = value & 0x1F;
            case 0x2E -> mainScreenWindowedLayers = value & 0x1F;
            case 0x2F -> subScreenWindowedLayers = value & 0x1F;
            case 0x30 -> colorMathControl = value;
            case 0x31 -> colorMathLayers = value;
            case 0x32 -> writeFixedColor(value);
            case 0x33 -> screenSettings = value;
            default -> {
                // $2134-$213F are read-only; writes to them do nothing.
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
     * Scroll registers are written twice, low byte first: each write combines the new byte
     * (as the high byte) with the previously written byte (as the low byte). For horizontal
     * scroll, the low 3 bits come from PPU2's latch and the rest from PPU1's. In the normal
     * low-then-high sequence both latches hold the same byte, so the result is simply
     * {@code high << 8 | low}. The split only shows when a game interleaves writes to
     * different scroll registers.
     *
     * <p>Only the low 10 bits are used, but the latches must keep all 8 bits of each byte:
     * masking too early drops bits of the low byte before the second write arrives.
     */
    private void writeHorizontalScroll(BackgroundLayer bg, int value) {
        bg.hScroll = ((value << 8) | (scrollLatchPpu1 & ~7) | (scrollLatchPpu2 & 7)) & 0x3FF;
        scrollLatchPpu1 = value;
        scrollLatchPpu2 = value;
    }

    private void writeVerticalScroll(BackgroundLayer bg, int value) {
        bg.vScroll = ((value << 8) | scrollLatchPpu1) & 0x3FF;
        scrollLatchPpu1 = value;
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

    /**
     * $2132 COLDATA: bits 7-5 choose which channels (blue, green, red) to set, and bits 4-0 give
     * the intensity. Games often write it three times, once per channel, or once to set all
     * three to the same gray.
     */
    private void writeFixedColor(int value) {
        int intensity = value & 0x1F;
        if ((value & 0x20) != 0) {
            fixedColor = (fixedColor & ~0x001F) | intensity;
        }
        if ((value & 0x40) != 0) {
            fixedColor = (fixedColor & ~0x03E0) | (intensity << 5);
        }
        if ((value & 0x80) != 0) {
            fixedColor = (fixedColor & ~0x7C00) | (intensity << 10);
        }
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

    // ---- Port addresses ------------------------------------------------------------------------
    // VRAM, CGRAM and OAM sit behind data ports: the CPU (or DMA) writes $2118, $2122 or $2104,
    // and the PPU decides which word or byte that lands in from an address it keeps internally
    // and steps forward by itself. A bus-level observer only sees the port register, so these
    // read-only getters tell it where the next port access will go. None has side effects.
    // Debug support: none of them is needed to emulate the console; they exist for the visual
    // memory debugger and other tools.

    /**
     * The VRAM word ($0000-$7FFF) that the next $2118/$2119 write goes to, after address
     * remapping ($2115 bits 2-3). $2118 writes its low byte, $2119 its high byte. A
     * $2139/$213A read that steps the address refills the read buffer from this word too.
     *
     * <p><b>Debug support</b> (see the section comment above).
     */
    public int vramPortWordAddress() {
        return remappedVramAddress();
    }

    /**
     * The CGRAM byte (0-$1FF) the next $2122 write or $213B read goes to: the colour index
     * times two, plus one when the port is waiting for a colour's second (high) byte. (A first
     * byte is held in a latch until the second arrives, but both belong to this colour.)
     *
     * <p><b>Debug support</b> (see the section comment above).
     */
    public int cgramPortByteAddress() {
        return cgramAddress * 2 + (cgramSecondByte ? 1 : 0);
    }

    /**
     * The OAM byte (0-$21F) the next $2104 write or $2138 read goes to. Above $1FF the
     * 32-byte high table repeats, so the address is folded into it the way the PPU does.
     * (Low-table writes are held in pairs: an even byte is stored when the odd one arrives.)
     *
     * <p><b>Debug support</b> (see the section comment above).
     */
    public int oamPortByteAddress() {
        return oamAddress < 0x200 ? oamAddress : 0x200 | (oamAddress & 0x1F);
    }
}
