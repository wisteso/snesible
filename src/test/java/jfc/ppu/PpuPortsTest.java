package jfc.ppu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jfc.system.MasterClock;
import org.junit.jupiter.api.Test;

/** The PPU's memory access ports: VRAM, CGRAM, OAM and the Mode 7 multiplier. */
class PpuPortsTest {

    private final Ppu ppu = new Ppu(new MasterClock(false), false);

    @Test
    void vramWritesStepByTheConfiguredAmountAfterTheChosenByte() {
        ppu.write(0x15, 0x81);  // step 32 words, after the high byte
        setVramAddress(0x0400);

        ppu.write(0x18, 0x34);
        ppu.write(0x19, 0x12);
        ppu.write(0x18, 0x78);
        ppu.write(0x19, 0x56);

        assertEquals(0x1234, ppu.peekVram(0x0400));
        assertEquals(0x5678, ppu.peekVram(0x0420));
    }

    @Test
    void vramReadsComeThroughAOneWordBuffer() {
        ppu.write(0x15, 0x80);
        setVramAddress(0x0010);
        ppu.write(0x18, 0xCD);
        ppu.write(0x19, 0xAB);

        setVramAddress(0x0010); // reloads the buffer from the new address
        int low = ppu.read(0x39);
        int high = ppu.read(0x3A);

        assertEquals(0xABCD, (high << 8) | low);
    }

    @Test
    void addressRemappingRotatesTheLowBitsIntoTileOrder() {
        ppu.write(0x15, 0x84);  // remap mode 1 (8-bit rotation), step after high byte
        setVramAddress(0x0021); // row 1, column 1 in the CPU's bitmap layout

        ppu.write(0x18, 0x01);
        ppu.write(0x19, 0x00);

        // %00100001 rotated left by 3 within 8 bits -> %00001001
        assertEquals(0x0001, ppu.peekVram(0x0009));
    }

    @Test
    void cgramTakesTwoWritesPerColorAndDropsBit15() {
        ppu.write(0x21, 0x05);

        ppu.write(0x22, 0xFF);
        ppu.write(0x22, 0xFF);

        assertEquals(0x7FFF, ppu.peekCgram(0x05));
    }

    @Test
    void oamLowTableWritesLandInPairs() {
        ppu.write(0x02, 0x00);
        ppu.write(0x03, 0x00);

        ppu.write(0x04, 0x11);
        assertEquals(0x00, ppu.peekOam(0), "first byte is only latched");
        ppu.write(0x04, 0x22);

        assertEquals(0x11, ppu.peekOam(0));
        assertEquals(0x22, ppu.peekOam(1));
    }

    @Test
    void mode7MultiplierIsSigned16By8() {
        ppu.write(0x1B, 0x00);  // M7A = $FF00 (-256), written low then high
        ppu.write(0x1B, 0xFF);
        ppu.write(0x1C, 0x00);  // M7B: only its high byte (here 3) takes part
        ppu.write(0x1C, 0x03);

        int product = ppu.read(0x34) | (ppu.read(0x35) << 8) | (ppu.read(0x36) << 16);

        assertEquals(-768 & 0xFFFFFF, product);
    }

    private void setVramAddress(int wordAddress) {
        ppu.write(0x16, wordAddress & 0xFF);
        ppu.write(0x17, wordAddress >> 8);
    }
}
