package snesible.core.cartridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * A game cartridge: the ROM image, its optional SRAM, and the board wiring that maps them into
 * the CPU's address space.
 *
 * <p>The system bus asks the cartridge to handle any address that isn't work RAM or a console
 * register. {@link #read} returns {@link #OPEN_BUS} when the cartridge doesn't drive the data
 * bus at that address, so the bus can supply its open-bus value instead.
 *
 * <p>Boards with a coprocessor (see {@link CoprocessorType}) have their own wiring. For those,
 * the coprocessor implements {@link CartridgeSlot} and decodes addresses itself, using this
 * class only as the holder of the ROM and RAM bytes ({@link #romBytes()}, {@link #ramBytes()})
 * and of the save-file logic.
 */
public final class Cartridge implements CartridgeSlot {

    /**
     * GSU RAM size for SuperFX games whose header predates the extended header, so it doesn't
     * say how much RAM the board has. The early GSU-1 boards have 32 KiB.
     */
    private static final int DEFAULT_SUPER_FX_RAM = 32 * 1024;

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
    /** Set when the game writes SRAM, so the emulator knows the save file is out of date. */
    private boolean sramChanged;

    private Cartridge(byte[] rom, MapMode mapMode, boolean hadCopierHeader) {
        this.rom = rom;
        this.mapMode = mapMode;
        this.hadCopierHeader = hadCopierHeader;
        this.header = CartridgeHeader.parse(rom, mapMode.headerFileOffset());
        this.sram = new byte[ramSize(header)];
        this.mapper = switch (mapMode) {
            case LOROM -> new LoRomMapper(rom.length, sram.length);
            case HIROM -> new HiRomMapper(rom.length, sram.length);
            case EXHIROM -> throw new UnsupportedOperationException(
                    "ExHiROM cartridges aren't supported yet: " + header.title());
        };
    }

    /**
     * The size of the cartridge RAM. Usually that's the SRAM the header declares, but a SuperFX
     * board's RAM (the GSU's work and frame-buffer memory, battery-backed on some games) is
     * declared as "expansion RAM" in the extended header instead.
     */
    private static int ramSize(CartridgeHeader header) {
        if (header.coprocessorType() == CoprocessorType.SUPER_FX) {
            int declared = header.expansionRamSizeBytes();
            return declared > 0 ? declared : DEFAULT_SUPER_FX_RAM;
        }
        return header.sramSizeBytes();
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
    @Override
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
    @Override
    public void write(int address, int value) {
        int sramOffset = mapper.sramOffset(address);
        if (sramOffset != Mapper.NOT_MAPPED && sram[sramOffset] != (byte) value) {
            sram[sramOffset] = (byte) value;
            sramChanged = true;
        }
    }

    // =============================================================================================
    // Raw memory, for coprocessor boards that decode addresses themselves
    // =============================================================================================

    /**
     * The ROM image itself (not a copy). Coprocessor boards read it directly; nothing may write
     * to it.
     */
    public byte[] romBytes() {
        return rom;
    }

    /**
     * The cartridge RAM itself (not a copy): SRAM on plain boards, the coprocessor's RAM on
     * others. Boards that write it on the CPU's behalf call {@link #markRamChanged()} so the
     * save file gets updated.
     */
    public byte[] ramBytes() {
        return sram;
    }

    /** Records that RAM holds data the save file doesn't have yet. */
    public void markRamChanged() {
        sramChanged = true;
    }

    // =============================================================================================
    // Battery-backed save RAM
    // =============================================================================================

    /** True if SRAM has changed since the last {@link #saveSramTo} or {@link #loadSramFrom}. */
    public boolean hasUnsavedSram() {
        return sramChanged && header.hasBattery();
    }

    /**
     * Writes SRAM to a file (the usual ".srm" format: the raw bytes, nothing else). The data is
     * written to a temporary file that then replaces the real one, so a crash mid-write can't
     * leave a corrupt save.
     */
    public void saveSramTo(Path file) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, sram);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        sramChanged = false;
    }

    /**
     * Restores SRAM from a save file, if it exists. A file of the wrong size is used as far as
     * it goes (other emulators sometimes pad or trim saves).
     */
    public void loadSramFrom(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        byte[] saved = Files.readAllBytes(file);
        System.arraycopy(saved, 0, sram, 0, Math.min(saved.length, sram.length));
        sramChanged = false;
    }

    /** The save file that belongs to a ROM: same name, ".srm" extension. */
    public static Path saveFileFor(Path romFile) {
        String name = romFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return romFile.resolveSibling(base + ".srm");
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
