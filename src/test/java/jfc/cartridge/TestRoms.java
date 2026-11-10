package jfc.cartridge;

import java.nio.charset.StandardCharsets;

/** Builds small synthetic ROM images with valid headers, so tests don't depend on real games. */
final class TestRoms {

    private TestRoms() {
    }

    /**
     * Creates a ROM whose every byte encodes its own offset (handy for checking address mapping),
     * with a valid header at the position {@code mapMode} expects.
     */
    static byte[] build(MapMode mapMode, int romSize, int sramSizeByte, int resetVector) {
        byte[] rom = new byte[romSize];
        for (int i = 0; i < romSize; i++) {
            rom[i] = (byte) (i ^ (i >>> 8) ^ (i >>> 16));
        }
        int h = mapMode.headerFileOffset();
        byte[] title = String.format("%-21s", "JFC TEST ROM").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, h, 21);
        rom[h + 0x15] = (byte) (mapMode == MapMode.HIROM ? 0x21 : 0x20);
        rom[h + 0x16] = (byte) (sramSizeByte > 0 ? 0x02 : 0x00);
        rom[h + 0x17] = (byte) (Integer.numberOfTrailingZeros(romSize) - 10);
        rom[h + 0x18] = (byte) sramSizeByte;
        rom[h + 0x19] = 0x01;
        rom[h + 0x3C] = (byte) resetVector;
        rom[h + 0x3D] = (byte) (resetVector >> 8);
        writeChecksum(rom, h);
        return rom;
    }

    /**
     * Fills in the checksum fields. A checksum and its complement always add up to $1FE byte-wise
     * ($FF + $FF), so placing any complementary pair first gives a sum that won't change once
     * the real pair is written.
     */
    private static void writeChecksum(byte[] rom, int h) {
        setWord(rom, h + 0x1C, 0xFFFF);
        setWord(rom, h + 0x1E, 0x0000);
        int sum = 0;
        for (byte b : rom) {
            sum += b & 0xFF;
        }
        sum &= 0xFFFF;
        setWord(rom, h + 0x1C, sum ^ 0xFFFF);
        setWord(rom, h + 0x1E, sum);
    }

    private static void setWord(byte[] rom, int offset, int value) {
        rom[offset] = (byte) value;
        rom[offset + 1] = (byte) (value >> 8);
    }

    /** The value {@link #build} stored at ROM offset {@code i} (ignoring the header area). */
    static int expectedByte(int i) {
        return (i ^ (i >>> 8) ^ (i >>> 16)) & 0xFF;
    }
}
