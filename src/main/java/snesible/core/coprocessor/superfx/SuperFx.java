package snesible.core.coprocessor.superfx;

import static snesible.core.observe.Observable.wrapGsu;

import snesible.core.cartridge.Cartridge;
import snesible.core.cartridge.Mirroring;
import snesible.core.coprocessor.Coprocessor;

/**
 * A SuperFX cartridge board: ROM, the GSU's RAM, and the GSU chip ({@link Gsu}) between them
 * and the console.
 *
 * <h2>The console's view (CPU side)</h2>
 * <pre>
 *   Banks $00-$3F, $80-$BF:
 *     $3000-$303F  GSU registers (R0-R15 at $3000-$301F, then status and control)
 *     $3100-$32FF  the GSU's 512-byte instruction cache
 *     $6000-$7FFF  first 8 KiB of the GSU RAM
 *     $8000-$FFFF  ROM, LoROM layout
 *   Banks $40-$5F, $C0-$DF: ROM, 64 KiB per bank (the same ROM again, linearly)
 *   Banks $70-$71, $F0-$F1: GSU RAM, 64 KiB per bank (mirrored when smaller)
 * </pre>
 *
 * <h2>Sharing the memories</h2>
 * The ROM and the RAM each have one bus, and SCMR decides who gets it. A game hands the ROM
 * bus (RON) and the RAM bus (RAN) to the GSU, starts it, and then runs its own code from work
 * RAM until the GSU is done. While the GSU owns the ROM, the console reads fixed values instead
 * of ROM. Those values are chosen so the interrupt vectors at $FFE0-$FFEF point into work RAM
 * (NMI to $0108, IRQ to $010C), where the game has put small handlers. That's why some
 * SuperFX games' own headers list NMI $0108 and IRQ $010C. While the GSU owns the RAM, the
 * console reads open bus there and its writes are lost.
 *
 * <h2>Registers ($3030 onward)</h2>
 * <pre>
 *   $3030/31 SFR    rw  status: bit 5 G (running); reading $3031 clears IRQ (bit 15)
 *   $3033    BRAMR  w   backup RAM write enable (unused by the supported games)
 *   $3034    PBR    rw  program bank
 *   $3036    ROMBR  r   ROM data bank (set by the GSU's ROMB)
 *   $3037    CFGR   w   bit 7 mask the STOP IRQ, bit 5 fast multiplier
 *   $3038    SCBR   w   screen base, 1 KiB units
 *   $3039    CLSR   w   bit 0: 21.4 MHz instead of 10.7 MHz
 *   $303A    SCMR   w   screen mode: bits 1-0 color depth, bits 5 and 2 height, bit 4 RON, bit 3 RAN
 *   $303B    VCR    r   chip version
 *   $303C    RAMBR  r   RAM data bank (set by the GSU's RAMB)
 *   $303E/3F CBR    r   cache base
 * </pre>
 * Writing R15's high byte ($301F) starts the GSU at the new R15.
 *
 * <h2>Timing</h2>
 * The GSU catches up with the master clock whenever the console touches the board, and at
 * every line start (see {@link Coprocessor}). Its STOP interrupt reaches the CPU through
 * {@link Coprocessor.Host#setIrqLine}.
 */
public final class SuperFx implements Coprocessor {

    /**
     * What the console reads from ROM while the GSU owns it, by the low 4 address bits. At the
     * native vectors ($FFE4 COP, $FFE6 BRK, $FFEA NMI, $FFEE IRQ) these spell $0104, $0100,
     * $0108 and $010C: addresses in work RAM.
     */
    private static final int[] ROM_BLOCKED_VALUES = {
        0x00, 0x01, 0x00, 0x01, 0x04, 0x01, 0x00, 0x01,
        0x00, 0x01, 0x08, 0x01, 0x00, 0x01, 0x0C, 0x01,
    };

    private final Cartridge cartridge;
    private final byte[] rom;
    private final byte[] ram;
    private final Gsu gsu;
    /** VCR: 1 for the GSU-1 boards, 4 for the GSU-2 boards. */
    private final int version;

    private Host host;
    /** The master-clock time the GSU has run up to. */
    private long gsuTime;

    public SuperFx(Cartridge cartridge) {
        this.cartridge = cartridge;
        this.rom = cartridge.romBytes();
        this.ram = cartridge.ramBytes();
        this.gsu = new Gsu(wrapGsu(new GsuSide()), this::raiseInterrupt);
        // Only the GSU-2 can address more than 1 MiB of ROM, so the size tells them apart well
        // enough for the version register.
        this.version = rom.length > 0x100000 ? 4 : 1;
        gsu.reset();
    }

    @Override
    public void connect(Host host) {
        this.host = host;
    }

    @Override
    public void reset() {
        gsu.reset();
        host.setIrqLine(false);
        gsuTime = host.masterCycles();
    }

    @Override
    public void catchUp() {
        long now = host.masterCycles();
        while (gsuTime < now) {
            if (!gsu.running) {
                gsuTime = now; // a stopped GSU just lets time pass
                return;
            }
            gsuTime += gsu.step();
        }
    }

    private void raiseInterrupt() {
        host.setIrqLine(true);
    }

    // =============================================================================================
    // Console side
    // =============================================================================================

    @Override
    public int read(int address) {
        catchUp(); // whatever the console sees must reflect the GSU's progress up to now
        int bank = (address >>> 16) & 0x7F; // banks $80-$FF mirror $00-$7F
        int offset = address & 0xFFFF;
        if (bank < 0x40) {
            if (offset >= 0x3000 && offset < 0x3500) {
                return readRegister(0x3000 | (offset & 0x3FF));
            }
            if (offset >= 0x6000 && offset < 0x8000) {
                return readRamFromConsole(offset & 0x1FFF);
            }
            if (offset >= 0x8000) {
                return readRomFromConsole(address, loRomOffset(bank, offset));
            }
            return Cartridge.OPEN_BUS;
        }
        if (bank < 0x60) {
            return readRomFromConsole(address, (bank & 0x1F) << 16 | offset);
        }
        if (bank == 0x70 || bank == 0x71) {
            return readRamFromConsole((bank & 1) << 16 | offset);
        }
        return Cartridge.OPEN_BUS;
    }

    @Override
    public void write(int address, int value) {
        catchUp();
        int bank = (address >>> 16) & 0x7F;
        int offset = address & 0xFFFF;
        if (bank < 0x40 && offset >= 0x3000 && offset < 0x3500) {
            writeRegister(0x3000 | (offset & 0x3FF), value);
        } else if (bank < 0x40 && offset >= 0x6000 && offset < 0x8000) {
            writeRamFromConsole(offset & 0x1FFF, value);
        } else if (bank == 0x70 || bank == 0x71) {
            writeRamFromConsole((bank & 1) << 16 | offset, value);
        }
    }

    private int readRomFromConsole(int address, int romOffset) {
        if (gsu.running && gsu.ownsRom) {
            return ROM_BLOCKED_VALUES[address & 0x0F];
        }
        return rom[Mirroring.mirror(romOffset, rom.length)] & 0xFF;
    }

    private int readRamFromConsole(int ramOffset) {
        if (gsu.running && gsu.ownsRam) {
            return Cartridge.OPEN_BUS;
        }
        return ram[Mirroring.mirror(ramOffset, ram.length)] & 0xFF;
    }

    /**
     * Only writes from the console mark the RAM as needing a save. The GSU rewrites its frame
     * buffer constantly; counting those would rewrite the save file every second, while the
     * games save their progress with ordinary CPU writes.
     */
    private void writeRamFromConsole(int ramOffset, int value) {
        if (gsu.running && gsu.ownsRam) {
            return;
        }
        int index = Mirroring.mirror(ramOffset, ram.length);
        if (ram[index] != (byte) value) {
            ram[index] = (byte) value;
            cartridge.markRamChanged();
        }
    }

    private int readRegister(int address) {
        if (address >= 0x3100 && address < 0x3300) {
            return readCache(address - 0x3100);
        }
        if (address < 0x3020) {
            int register = gsu.r[(address >> 1) & 0x0F];
            return (address & 1) == 0 ? register & 0xFF : register >> 8;
        }
        return switch (address) {
            case 0x3030 -> gsu.statusRegister() & 0xFF;
            case 0x3031 -> {
                // Reading the high half acknowledges the STOP interrupt.
                int high = gsu.statusRegister() >> 8;
                gsu.irqPending = false;
                host.setIrqLine(false);
                yield high;
            }
            case 0x3034 -> gsu.programBank;
            case 0x3036 -> gsu.romBank;
            case 0x303B -> version;
            case 0x303C -> gsu.ramBank;
            case 0x303E -> gsu.cacheBase & 0xFF;
            case 0x303F -> gsu.cacheBase >> 8;
            default -> 0x00;
        };
    }

    private void writeRegister(int address, int value) {
        if (address >= 0x3100 && address < 0x3300) {
            writeCache(address - 0x3100, value);
            return;
        }
        if (address < 0x3020) {
            int n = (address >> 1) & 0x0F;
            int old = gsu.r[n];
            int updated = (address & 1) == 0 ? (old & 0xFF00) | value : (value << 8) | (old & 0x00FF);
            gsu.r[n] = updated;
            if (n == 14) {
                gsu.refreshRomBuffer();
            }
            if (address == 0x301F) {
                gsu.running = true; // R15's high byte: go
            }
            return;
        }
        switch (address) {
            case 0x3030 -> writeStatusLow(value);
            case 0x3031 -> {
                gsu.alt1 = (value & 0x01) != 0;
                gsu.alt2 = (value & 0x02) != 0;
                gsu.withPrefix = (value & 0x10) != 0;
                gsu.irqPending = (value & 0x80) != 0;
            }
            case 0x3033 -> {
                // BRAMR: write protection for a separate backup RAM, which these boards lack.
            }
            case 0x3034 -> {
                gsu.programBank = value & 0x7F;
                gsu.flushCache();
            }
            case 0x3037 -> gsu.config = value;
            case 0x3038 -> gsu.plot.screenBase = value;
            case 0x3039 -> gsu.fastClock = (value & 0x01) != 0;
            case 0x303A -> writeScreenMode(value);
            default -> {
                // Read-only or unused.
            }
        }
    }

    /** $3030: the flags, and G. Clearing G stops the GSU and empties its cache. */
    private void writeStatusLow(int value) {
        boolean wasRunning = gsu.running;
        gsu.zero = (value & 0x02) != 0;
        gsu.carry = (value & 0x04) != 0;
        gsu.sign = (value & 0x08) != 0;
        gsu.overflow = (value & 0x10) != 0;
        gsu.running = (value & 0x20) != 0;
        if (wasRunning && !gsu.running) {
            gsu.cacheBase = 0;
            gsu.flushCache();
        }
    }

    /** $303A SCMR: color depth, screen height, and who owns the ROM and RAM buses. */
    private void writeScreenMode(int value) {
        gsu.plot.colorDepthMode = value & 0x03;
        gsu.plot.heightMode = (value >> 2 & 1) | (value >> 4 & 2); // bits 2 and 5
        gsu.ownsRam = (value & 0x08) != 0;
        gsu.ownsRom = (value & 0x10) != 0;
    }

    /**
     * The console's window onto the instruction cache. Byte {@code n} of the window is the cache
     * byte for code address CBR + n, so a game can copy a routine into $3100 and run it from
     * there without the GSU touching ROM. Writing the last byte of a 16-byte line marks the
     * line as loaded.
     */
    private int readCache(int n) {
        return gsu.cache[(n + gsu.cacheBase) & (Gsu.CACHE_SIZE - 1)] & 0xFF;
    }

    private void writeCache(int n, int value) {
        int index = (n + gsu.cacheBase) & (Gsu.CACHE_SIZE - 1);
        gsu.cache[index] = (byte) value;
        if ((index & 0x0F) == 0x0F) {
            gsu.cacheLineValid[index >> 4] = true;
        }
    }

    // =============================================================================================
    // GSU side
    // =============================================================================================

    /**
     * The memories as the GSU sees them (see {@link GsuBus}). This is a separate object because
     * the console-side {@link #read(int)} has the same signature but a different address map.
     *
     * <p><i>Simplification:</i> the real GSU waits when it needs a bus it hasn't been given
     * (RON or RAN clear). Games don't let that happen, so we let the access through.
     */
    private final class GsuSide implements GsuBus {
        @Override
        public int read(int address) {
            int bank = (address >>> 16) & 0x7F;
            int offset = address & 0xFFFF;
            if (bank < 0x40) {
                return rom[Mirroring.mirror(loRomOffset(bank, offset), rom.length)] & 0xFF;
            }
            if (bank < 0x60) {
                return rom[Mirroring.mirror((bank & 0x1F) << 16 | offset, rom.length)] & 0xFF;
            }
            return ram[Mirroring.mirror(ramOffset(bank, offset), ram.length)] & 0xFF;
        }

        @Override
        public void write(int address, int value) {
            int bank = (address >>> 16) & 0x7F;
            if (bank >= 0x60) {
                ram[Mirroring.mirror(ramOffset(bank, address & 0xFFFF), ram.length)] = (byte) value;
            }
        }

        /** RAM banks: the bank's low bit picks the 64 KiB half (CPU banks $70/$71). */
        private static int ramOffset(int bank, int offset) {
            return (bank & 1) << 16 | offset;
        }
    }

    /** In banks $00-$3F only the low 15 address bits select a byte: both halves show the same 32 KiB. */
    private static int loRomOffset(int bank, int offset) {
        return (bank & 0x3F) * 0x8000 + (offset & 0x7FFF);
    }

    // ---- For tests and (later) debugging tools

    /** The GSU core, so tests in this package can inspect it. */
    Gsu gsu() {
        return gsu;
    }
}
