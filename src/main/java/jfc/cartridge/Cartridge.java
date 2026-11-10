package jfc.cartridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A game cartridge: the ROM image, its optional SRAM, and the board wiring that maps them into
 * the CPU's address space.
 *
 * <p>The system bus asks the cartridge to handle any address that isn't work RAM or a console
 * register. {@link #read} returns {@link #OPEN_BUS} when the cartridge doesn't drive the data
 * bus at that address, so the bus can supply its open-bus value instead.
 */
public final class Cartridge {

    /** Returned by {@link #read} when no cartridge chip answers at the address. */
    public static final int OPEN_BUS = -1;

    /**
     * Size of the header that 1990s backup ("copier") devices put in front of the ROM data. Real
     * ROM sizes are multiples of 1 KiB, so a file that is 512 bytes over has one.
     */
    static final int COPIER_HEADER_SIZE = 512;

    private final byte[] rom;
    private final byte[] sram;
    private final CartridgeHeader header;
    private final MapMode mapMode;
    private final Mapper mapper;
    private final boolean hadCopierHeader;

    private Cartridge(byte[] rom, MapMode mapMode, boolean hadCopierHeader) {
        this.rom = rom;
        this.mapMode = mapMode;
        this.hadCopierHeader = hadCopierHeader;
        this.header = CartridgeHeader.parse(rom, mapMode.headerFileOffset());
        this.sram = new byte[header.sramSizeBytes()];
        this.mapper = switch (mapMode) {
            case LOROM -> new LoRomMapper(rom.length, sram.length);
            case HIROM -> new HiRomMapper(rom.length, sram.length);
            case EXHIROM -> throw new UnsupportedOperationException(
                    "ExHiROM cartridges aren't supported yet: " + header.title());
        };
    }

    /** Loads a .sfc or .smc file. */
    public static Cartridge load(Path path) throws IOException {
        return fromFileBytes(Files.readAllBytes(path));
    }

    /** Builds a cartridge from the raw contents of a ROM file. */
    public static Cartridge fromFileBytes(byte[] file) {
        boolean hasCopierHeader = file.length % 1024 == COPIER_HEADER_SIZE;
        byte[] rom = hasCopierHeader
                ? Arrays.copyOfRange(file, COPIER_HEADER_SIZE, file.length)
                : file.clone();
        return new Cartridge(rom, HeaderLocator.detectMapMode(rom), hasCopierHeader);
    }

    /**
     * Reads one byte at a 24-bit CPU address.
     *
     * @return the byte as 0-255, or {@link #OPEN_BUS} if the cartridge doesn't respond there
     */
    public int read(int address) {
        int romOffset = mapper.romOffset(address);
        if (romOffset != Mapper.NOT_MAPPED) {
            return rom[romOffset] & 0xFF;
        }
        int sramOffset = mapper.sramOffset(address);
        if (sramOffset != Mapper.NOT_MAPPED) {
            return sram[sramOffset] & 0xFF;
        }
        return OPEN_BUS;
    }

    /** Writes one byte. Only SRAM is writable, and writes elsewhere are ignored, as on hardware. */
    public void write(int address, int value) {
        int sramOffset = mapper.sramOffset(address);
        if (sramOffset != Mapper.NOT_MAPPED) {
            sram[sramOffset] = (byte) value;
        }
    }

    /**
     * Computes the checksum the way the header defines it: the 16-bit sum of every ROM byte. If
     * the ROM isn't a power-of-two size, it's first padded up to one with its mirrored contents,
     * which is what the CPU would see reading the whole decoded address range.
     */
    public int computeChecksum() {
        int paddedSize = Integer.highestOneBit(rom.length);
        if (paddedSize < rom.length) {
            paddedSize <<= 1;
        }
        int sum = 0;
        for (int i = 0; i < paddedSize; i++) {
            sum += rom[Mirroring.mirror(i, rom.length)] & 0xFF;
        }
        return sum & 0xFFFF;
    }

    public CartridgeHeader header() {
        return header;
    }

    public MapMode mapMode() {
        return mapMode;
    }

    /** ROM size in bytes, after any copier header has been removed. */
    public int romSize() {
        return rom.length;
    }

    public int sramSize() {
        return sram.length;
    }

    public boolean hadCopierHeader() {
        return hadCopierHeader;
    }
}
