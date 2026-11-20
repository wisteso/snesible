package jfc.debug;

import jfc.core.ppu.Ppu;

/**
 * Works out, once a frame, what the PPU must have read to draw it, and marks it in VRAM,
 * CGRAM and OAM as {@link Access#CHIP}.
 *
 * <p><b>Why it's worked out instead of recorded:</b> the renderer reads the PPU's memories by
 * indexing arrays inside the core, with no method call the debugger could wrap. But what it
 * reads follows entirely from the registers (copied in {@link VideoRegisters}) and from the
 * tilemaps and sprite table themselves, so the same decoding the PPU does, run here once a
 * frame, gives the same regions:
 * <ul>
 *   <li><b>Backgrounds:</b> for each enabled layer, the tilemap entries on screen (from its
 *       tilemap address, size and scroll), the tiles those entries name (from its tile
 *       address and bits per pixel), and the palette entries they use.</li>
 *   <li><b>Sprites:</b> the PPU scans all of OAM every line, so all of it is marked; then the
 *       tiles and palettes of every sprite that's on screen.</li>
 *   <li><b>Mode 7:</b> its whole 32 KiB area (tilemap and pixels interleaved), and the full
 *       palette.</li>
 *   <li>The backdrop colour (CGRAM entry 0) whenever the screen is on.</li>
 * </ul>
 *
 * <p><b>What it misses:</b> how often and exactly when things were read; settings changed
 * mid-frame (HDMA scroll effects, raster splits), which are seen at their end-of-frame value;
 * offset-per-tile (modes 2, 4, 6) and windows/masks, which are ignored (the area is still
 * marked as if fully visible).
 */
final class VideoActivity {

    /** Bits per pixel of each background layer, per BG mode 0-6 (mode 7 is handled apart). */
    private static final int[][] BITS_PER_PIXEL = {
            {2, 2, 2, 2}, {4, 4, 2}, {4, 4}, {8, 4}, {8, 2}, {4, 2}, {4},
    };

    /**
     * Sprite sizes in pixels per OBSEL size setting ($2101 bits 5-7): small width, small
     * height, large width, large height. Settings 6 and 7 are the rectangular ones.
     */
    private static final int[][] SPRITE_SIZES = {
            {8, 8, 16, 16}, {8, 8, 32, 32}, {8, 8, 64, 64}, {16, 16, 32, 32},
            {16, 16, 64, 64}, {32, 32, 64, 64}, {16, 32, 32, 64}, {16, 32, 32, 32},
    };

    private final AccessRecorder recorder;
    private final VideoRegisters video;
    private final Ppu ppu;

    VideoActivity(AccessRecorder recorder, VideoRegisters video, Ppu ppu) {
        this.recorder = recorder;
        this.video = video;
        this.ppu = ppu;
    }

    /** Marks what the PPU read for the frame just drawn. */
    void markFrame() {
        if (video.forcedBlank()) {
            return; // the PPU draws (and reads) nothing
        }
        int lines = video.overscan() ? 239 : 224;
        int layers = video.enabledLayers();
        cgram(0, 1); // the backdrop
        int mode = video.bgMode();
        if (mode == 7) {
            if ((layers & 0x03) != 0) {
                vramWords(0, 0x4000);
                if (!video.directColor()) {
                    cgram(0, 256);
                }
            }
        } else {
            int[] bpp = BITS_PER_PIXEL[mode];
            boolean hiRes = mode == 5 || mode == 6;
            for (int bg = 0; bg < bpp.length; bg++) {
                if ((layers & (1 << bg)) != 0) {
                    background(bg, bpp[bg], mode, hiRes, lines);
                }
            }
        }
        // The PPU looks through the whole sprite table on every line to find what's on it.
        recorder.oam.recordRange(0, recorder.oam.size(), Access.CHIP, recorder.frame());
        if ((layers & 0x10) != 0) {
            sprites(lines);
        }
    }

    /**
     * One background layer: the visible tilemap entries, their tiles and their palettes.
     *
     * <p>A tilemap is one to four 32x32-entry "screens" of 1 K words each, laid out left to
     * right, then top to bottom. Each entry is a word: {@code vhopppcc cccccccc} (flips,
     * priority, palette, 10-bit tile number). An 8x8 tile takes 8 words per 2 bits per pixel.
     */
    private void background(int bg, int bpp, int mode, boolean hiRes, int lines) {
        boolean big = video.bigTiles(bg);
        // In hi-res modes every tile is 16 pixels wide (two 8-pixel tiles side by side).
        int tileWidth = big || hiRes ? 16 : 8;
        int tileHeight = big ? 16 : 8;
        int screenWidth = hiRes ? 512 : 256;
        boolean wide = video.tilemapWide(bg);
        boolean tall = video.tilemapTall(bg);
        int mapBase = video.tilemapBase(bg);
        int tileBase = video.tileBase(bg);
        int tileWords = 4 * bpp;
        int hScroll = video.hScroll(bg);
        int vScroll = video.vScroll(bg);
        for (int ty = vScroll / tileHeight; ty <= (vScroll + lines - 1) / tileHeight; ty++) {
            for (int tx = hScroll / tileWidth; tx <= (hScroll + screenWidth - 1) / tileWidth; tx++) {
                int mapX = tx & (wide ? 63 : 31);
                int mapY = ty & (tall ? 63 : 31);
                int screen = (mapX >> 5) + ((mapY >> 5) == 0 ? 0 : (wide ? 2 : 1));
                int entryAddress = (mapBase + screen * 0x400 + (mapY & 31) * 32 + (mapX & 31)) & 0x7FFF;
                vramWords(entryAddress, 1);
                int entry = ppu.peekVram(entryAddress);
                int tile = entry & 0x3FF;
                vramWords(tileBase + tile * tileWords, tileWords);
                if (tileWidth == 16) {
                    vramWords(tileBase + (tile + 1) * tileWords, tileWords);
                }
                if (tileHeight == 16) {
                    vramWords(tileBase + (tile + 16) * tileWords, tileWords);
                    if (tileWidth == 16) {
                        vramWords(tileBase + (tile + 17) * tileWords, tileWords);
                    }
                }
                int palette = (entry >> 10) & 7;
                switch (bpp) {
                    // Mode 0 gives each of its four 2-bpp layers its own 32 colours.
                    case 2 -> cgram((mode == 0 ? bg * 32 : 0) + palette * 4, 4);
                    case 4 -> cgram(palette * 16, 16);
                    default -> {
                        if (!video.directColor()) {
                            cgram(0, 256);
                        }
                    }
                }
            }
        }
    }

    /**
     * The tiles and palettes of every sprite on screen.
     *
     * <p>Each sprite has 4 bytes in the low table (X, Y, tile, attributes {@code vhoopppN}) and
     * 2 bits in the high table (X bit 8, large size). Sprite tiles are 4 bpp (16 words) and
     * live in two tables of 256 tiles, 16 per row; a sprite larger than 8x8 uses the tiles to
     * its right and below, wrapping within the row and column. $2101 sets where the tables
     * are and the two sprite sizes.
     */
    private void sprites(int lines) {
        int select = video.objectSelect();
        int firstTable = (select & 7) << 13;
        int secondTable = firstTable + ((((select >> 3) & 3) + 1) << 12);
        int[] sizes = SPRITE_SIZES[select >> 5];
        for (int i = 0; i < 128; i++) {
            int high = ppu.peekOam(0x200 + i / 4) >> ((i & 3) * 2);
            int x = ppu.peekOam(i * 4) | (high & 1) << 8;
            if (x >= 256) {
                x -= 512;       // 9-bit X: 256-511 is off the left edge
            }
            int y = ppu.peekOam(i * 4 + 1);
            int tile = ppu.peekOam(i * 4 + 2);
            int attributes = ppu.peekOam(i * 4 + 3);
            boolean large = (high & 2) != 0;
            int width = large ? sizes[2] : sizes[0];
            int height = large ? sizes[3] : sizes[1];
            if (x + width <= 0 || x >= 256 || !onScreenVertically(y, height, lines)) {
                continue;
            }
            int table = (attributes & 1) == 0 ? firstTable : secondTable;
            for (int row = 0; row < height / 8; row++) {
                for (int column = 0; column < width / 8; column++) {
                    int number = ((tile >> 4) + row & 0xF) << 4 | ((tile & 0xF) + column & 0xF);
                    vramWords(table + number * 16, 16);
                }
            }
            cgram(128 + ((attributes >> 1) & 7) * 16, 16);
        }
    }

    /** Sprite Y wraps at 256, so a sprite near the bottom also shows at the top. */
    private static boolean onScreenVertically(int y, int height, int lines) {
        for (int row = 0; row < height; row++) {
            if (((y + row) & 0xFF) < lines) {
                return true;
            }
        }
        return false;
    }

    /** Marks {@code count} VRAM words from {@code wordAddress} (wrapping at 32 K words). */
    private void vramWords(int wordAddress, int count) {
        int frame = recorder.frame();
        for (int i = 0; i < count; i++) {
            int word = (wordAddress + i) & 0x7FFF;
            recorder.vram.record(word * 2, Access.CHIP, frame);
            recorder.vram.record(word * 2 + 1, Access.CHIP, frame);
        }
    }

    /** Marks {@code count} palette entries (2 bytes each) from {@code index}. */
    private void cgram(int index, int count) {
        recorder.cgram.recordRange(index * 2, count * 2, Access.CHIP, recorder.frame());
    }
}
