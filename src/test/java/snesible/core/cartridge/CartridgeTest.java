package snesible.core.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CartridgeTest {

    private static final int KIB = 1024;

    @Test
    void detectsLoRomAndParsesHeader() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.LOROM, 256 * KIB, 1, 0x8123));

        assertEquals(MapMode.LOROM, cart.mapMode());
        assertEquals("SNESIBLE TEST ROM", cart.header().title());
        assertEquals(0x8123, cart.header().vectors().emulationReset());
        assertEquals(Region.USA, cart.header().region());
        assertEquals(2 * KIB, cart.sramSize());
        assertEquals(cart.header().checksum(), cart.computeChecksum());
        assertFalse(cart.hadCopierHeader());
    }

    @Test
    void detectsHiRom() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.HIROM, 256 * KIB, 0, 0x8000));

        assertEquals(MapMode.HIROM, cart.mapMode());
        assertEquals(cart.header().checksum(), cart.computeChecksum());
    }

    @Test
    void stripsCopierHeader() {
        byte[] rom = TestRoms.build(MapMode.LOROM, 128 * KIB, 0, 0x8000);
        byte[] withCopierHeader = new byte[rom.length + 512];
        System.arraycopy(rom, 0, withCopierHeader, 512, rom.length);

        Cartridge cart = Cartridge.fromFileBytes(withCopierHeader);

        assertTrue(cart.hadCopierHeader());
        assertEquals(128 * KIB, cart.romSize());
        assertEquals("SNESIBLE TEST ROM", cart.header().title());
    }

    @Test
    void loRomMapsUpperHalfOfEachBankToConsecutive32KiBChunks() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.LOROM, 256 * KIB, 0, 0x8000));

        assertEquals(TestRoms.expectedByte(0x00000), cart.read(0x008000));
        assertEquals(TestRoms.expectedByte(0x08001), cart.read(0x018001));
        assertEquals(TestRoms.expectedByte(0x17ABC), cart.read(0x02FABC));
        // Banks $80-$FF mirror $00-$7F (this is where FastROM code runs).
        assertEquals(cart.read(0x02FABC), cart.read(0x82FABC));
        // The lower half of bank $00 belongs to RAM and I/O, not the cartridge.
        assertEquals(Cartridge.OPEN_BUS, cart.read(0x001234));
        // Work RAM banks are never cartridge space.
        assertEquals(Cartridge.OPEN_BUS, cart.read(0x7E8000));
    }

    @Test
    void loRomResetVectorIsReadableThroughTheMemoryMap() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.LOROM, 128 * KIB, 0, 0x8123));

        int reset = cart.read(0x00FFFC) | (cart.read(0x00FFFD) << 8);

        assertEquals(0x8123, reset);
    }

    @Test
    void loRomMirrorsSmallRomAcrossLaterBanks() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.LOROM, 128 * KIB, 0, 0x8000));

        // 128 KiB is four LoROM banks, so bank $04 shows bank $00 again.
        assertEquals(cart.read(0x008010), cart.read(0x048010));
    }

    @Test
    void loRomSramIsReadableAndWritableAndMirrored() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.LOROM, 128 * KIB, 1, 0x8000));

        cart.write(0x700010, 0x5A);

        assertEquals(0x5A, cart.read(0x700010));
        assertEquals(0x5A, cart.read(0x700810), "2 KiB SRAM repeats every $800 bytes");
        assertEquals(0x5A, cart.read(0xF00010), "banks $F0-$FF mirror $70-$7F");
    }

    @Test
    void writesToRomAreIgnored() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.LOROM, 128 * KIB, 0, 0x8000));
        int before = cart.read(0x008000);

        cart.write(0x008000, before ^ 0xFF);

        assertEquals(before, cart.read(0x008000));
    }

    @Test
    void hiRomMapsWholeBanksAndUpperHalfMirrors() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.HIROM, 256 * KIB, 0, 0x8000));

        assertEquals(TestRoms.expectedByte(0x00000), cart.read(0xC00000));
        assertEquals(TestRoms.expectedByte(0x1ABCD), cart.read(0xC1ABCD));
        assertEquals(cart.read(0xC1ABCD), cart.read(0x01ABCD), "upper half visible in bank $01");
        assertEquals(cart.read(0xC1ABCD), cart.read(0x41ABCD), "banks $40-$7D mirror $C0-$FF");
        assertEquals(Cartridge.OPEN_BUS, cart.read(0x011234));
    }

    @Test
    void sramSurvivesASaveAndReload(@TempDir Path dir) throws IOException {
        byte[] rom = TestRoms.build(MapMode.LOROM, 128 * KIB, 1, 0x8000);
        Cartridge cart = Cartridge.fromFileBytes(rom);
        Path saveFile = dir.resolve("game.srm");
        cart.write(0x700000, 0x12);
        cart.write(0x7007FF, 0x34);
        assertTrue(cart.hasUnsavedSram());

        cart.saveSramTo(saveFile);
        Cartridge reloaded = Cartridge.fromFileBytes(rom);
        reloaded.loadSramFrom(saveFile);

        assertFalse(cart.hasUnsavedSram());
        assertEquals(2 * KIB, Files.size(saveFile));
        assertEquals(0x12, reloaded.read(0x700000));
        assertEquals(0x34, reloaded.read(0x7007FF));
    }

    @Test
    void writingTheSameValueDoesNotMarkSramChanged() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.LOROM, 128 * KIB, 1, 0x8000));

        cart.write(0x700000, 0x00);

        assertFalse(cart.hasUnsavedSram());
    }

    @Test
    void saveFileSitsNextToTheRom() {
        assertEquals(Path.of("roms", "Super Mario World.srm"),
                Cartridge.saveFileFor(Path.of("roms", "Super Mario World.sfc")));
    }

    @Test
    void hiRomSramSitsAt6000InBanks20To3F() {
        Cartridge cart = Cartridge.fromFileBytes(TestRoms.build(MapMode.HIROM, 256 * KIB, 3, 0x8000));

        cart.write(0x206000, 0x42);

        assertEquals(0x42, cart.read(0x206000));
        assertEquals(0x42, cart.read(0xA06000));
        assertEquals(Cartridge.OPEN_BUS, cart.read(0x205FFF));
    }
}
