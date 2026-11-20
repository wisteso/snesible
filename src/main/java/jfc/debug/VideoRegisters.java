package jfc.debug;

/**
 * The debugger's own copy of the PPU registers it needs to work out what the PPU reads
 * ({@link VideoActivity}). The PPU's registers are write-only on real hardware, and the core
 * keeps its decoded state private, so the copy is kept here instead: every write to
 * $2100-$2133 passes through the debugger's S-CPU or DMA bus wrapper, which forwards it.
 *
 * <p>Only the scroll registers need care: each is written as two bytes into a pair of latches
 * shared by all layers. That's copied from {@code Ppu.writeHorizontalScroll}/
 * {@code writeVerticalScroll} so the values match the PPU's.
 *
 * <p>The values are those at the end of the frame. A game that changes them mid-frame (with
 * HDMA or a raster interrupt) is only seen at its last setting.
 */
final class VideoRegisters {

    /** The last value written to each register $2100-$2133 (index = low byte). */
    private final int[] written = new int[0x34];
    private final int[] hScroll = new int[4];
    private final int[] vScroll = new int[4];
    private int scrollLatch1;
    private int scrollLatch2;

    VideoRegisters() {
        written[0x00] = 0x80; // the console starts in forced blank
    }

    /** Called with every B-bus write ({@code register} = the low byte of $21xx). */
    void write(int register, int value) {
        if (register >= written.length) {
            return;
        }
        written[register] = value;
        if (register >= 0x0D && register <= 0x14) {
            int layer = (register - 0x0D) / 2;
            if ((register & 1) == 1) {   // $210D, $210F, ...: horizontal
                hScroll[layer] = ((value << 8) | (scrollLatch1 & ~7) | (scrollLatch2 & 7)) & 0x3FF;
                scrollLatch1 = value;
                scrollLatch2 = value;
            } else {                     // $210E, $2110, ...: vertical
                vScroll[layer] = ((value << 8) | scrollLatch1) & 0x3FF;
                scrollLatch1 = value;
            }
        }
    }

    /** The last value written to $21xx. */
    int get(int register) {
        return written[register];
    }

    /** $2100 bit 7: forced blank, when the PPU draws (and reads) nothing. */
    boolean forcedBlank() {
        return (written[0x00] & 0x80) != 0;
    }

    /** $2105 bits 0-2. */
    int bgMode() {
        return written[0x05] & 7;
    }

    /** $2105 bits 4-7: layer {@code bg} (0-3) uses 16x16 tiles. */
    boolean bigTiles(int bg) {
        return (written[0x05] & (0x10 << bg)) != 0;
    }

    /** $2107-$210A bits 2-7: the word address of layer {@code bg}'s tilemap. */
    int tilemapBase(int bg) {
        return (written[0x07 + bg] & 0xFC) << 8;
    }

    /** $2107-$210A bit 0: the tilemap is two 32-tile screens wide. */
    boolean tilemapWide(int bg) {
        return (written[0x07 + bg] & 1) != 0;
    }

    /** $2107-$210A bit 1: the tilemap is two 32-tile screens tall. */
    boolean tilemapTall(int bg) {
        return (written[0x07 + bg] & 2) != 0;
    }

    /** $210B-$210C: the word address of layer {@code bg}'s tile data (4 KiB-word steps). */
    int tileBase(int bg) {
        int value = written[0x0B + bg / 2];
        return ((bg & 1) == 0 ? value & 0x0F : value >> 4) << 12;
    }

    int hScroll(int bg) {
        return hScroll[bg];
    }

    int vScroll(int bg) {
        return vScroll[bg];
    }

    /** $2101 OBSEL. */
    int objectSelect() {
        return written[0x01];
    }

    /** $212C | $212D: the layers drawn on the main or sub screen (bit 4 = sprites). */
    int enabledLayers() {
        return (written[0x2C] | written[0x2D]) & 0x1F;
    }

    /** $2115 bit 7: the VRAM address steps after the high byte, not the low one. */
    boolean vramIncrementOnHighByte() {
        return (written[0x15] & 0x80) != 0;
    }

    /** $2130 bit 0: 8 bpp layers use direct colour, not the palette. */
    boolean directColor() {
        return (written[0x30] & 1) != 0;
    }

    /** $2133 bit 2: 239 visible lines instead of 224. */
    boolean overscan() {
        return (written[0x33] & 0x04) != 0;
    }
}
