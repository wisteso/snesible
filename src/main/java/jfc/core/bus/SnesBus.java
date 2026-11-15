package jfc.core.bus;

import jfc.core.apu.ApuPorts;
import jfc.core.cartridge.CartridgeSlot;
import jfc.core.cpu.CpuBus;
import jfc.core.cpu.io.ArithmeticUnit;
import jfc.core.cpu.io.InterruptControl;
import jfc.core.cpu.io.JoypadPorts;
import jfc.core.dma.DmaController;
import jfc.core.ppu.Ppu;
import jfc.core.system.MasterClock;
import jfc.core.system.WorkRam;

/**
 * The SNES memory map, as the CPU and DMA see it, plus the cost of every access.
 *
 * <h2>Memory map</h2>
 * <pre>
 *   Banks $00-$3F and $80-$BF ("system banks"):
 *     $0000-$1FFF  work RAM (first 8 KiB, mirrored)                  8 cycles
 *     $2100-$21FF  B-bus: PPU $2100-$213F, APU $2140-$217F,          6 cycles
 *                  WRAM port $2180-$2183
 *     $4016-$4017  serial joypad ports                              12 cycles
 *     $4200-$421F  CPU I/O: interrupts, math, joypad results         6 cycles
 *     $4300-$437F  DMA channel registers                             6 cycles
 *     $6000-$7FFF  cartridge (e.g. HiROM SRAM)                       8 cycles
 *     $8000-$FFFF  cartridge ROM                                     8, or 6 with FastROM
 *     anything else in $2000-$5FFF goes to the cartridge (usually open bus; coprocessors
 *     put their registers here, e.g. the SuperFX at $3000-$32FF)
 *   Banks $40-$7D and $C0-$FF: cartridge                             8, or 6 with FastROM
 *   Banks $7E-$7F: all 128 KiB of work RAM                           8 cycles
 * </pre>
 * The odd-looking speeds are real: the CPU slows its own clock to suit whatever chip is being
 * accessed. The old joypad ports are slowest (12) for compatibility with NES-era parts. FastROM
 * (6 cycles) applies only to banks $80-$FF, and only once a game sets MEMSEL ($420D) bit 0,
 * which is why FastROM games run from the upper banks.
 *
 * <h2>Open bus</h2>
 * Reading an address that no chip answers returns whatever byte was last on the data bus,
 * usually the last byte the CPU itself fetched. Some games accidentally depend on this.
 */
public final class SnesBus implements CpuBus, DmaController.Bus {

    private static final int FAST_CYCLES = 6;
    private static final int SLOW_CYCLES = 8;
    private static final int EXTRA_SLOW_CYCLES = 12;
    /** Internal-operation cycles (no bus access) always take 6 master cycles. */
    private static final int IDLE_CYCLES = 6;
    private static final int NOT_DRIVEN = -1;

    private final MasterClock clock;
    private final WorkRam wram;
    /** The cartridge, or the coprocessor board standing in front of it. */
    private final CartridgeSlot cartridge;
    private final Ppu ppu;
    private final ApuPorts apu;
    private final InterruptControl interrupts;
    private final ArithmeticUnit math;
    private final JoypadPorts joypads;
    private final DmaController dma;

    private int openBus;

    /** Notified of every write to a hardware register, from any source (CPU, DMA or HDMA). */
    public interface RegisterWriteListener {
        /** @param address $2100-$21FF or $4000-$43FF, in bank $00 terms */
        void onRegisterWrite(int address, int value);
    }

    /** For debugging tools: watches register writes. Null when nobody is watching. */
    private RegisterWriteListener registerWriteListener;
    /** MEMSEL ($420D) bit 0: use 6-cycle access for ROM in banks $80-$FF. */
    private boolean fastRomEnabled;
    /** WRIO ($4201): an output port on the controller connectors, also wired to the H/V latch. */
    private int wrio = 0xFF;

    public SnesBus(MasterClock clock, WorkRam wram, CartridgeSlot cartridge, Ppu ppu, ApuPorts apu,
                   InterruptControl interrupts, ArithmeticUnit math, JoypadPorts joypads,
                   DmaController dma) {
        this.clock = clock;
        this.wram = wram;
        this.cartridge = cartridge;
        this.ppu = ppu;
        this.apu = apu;
        this.interrupts = interrupts;
        this.math = math;
        this.joypads = joypads;
        this.dma = dma;
    }

    // =============================================================================================
    // CPU-facing bus cycles: each one spends time first, then performs the access
    // =============================================================================================

    @Override
    public int read(int address) {
        clock.advance(accessCycles(address));
        openBus = readA(address);
        return openBus;
    }

    @Override
    public void write(int address, int value) {
        clock.advance(accessCycles(address));
        openBus = value;
        writeA(address, value);
    }

    @Override
    public void idle() {
        clock.advance(IDLE_CYCLES);
    }

    /** Master cycles the CPU spends on an access to {@code address}. */
    public int accessCycles(int address) {
        int bank = address >>> 16;
        int offset = address & 0xFFFF;
        boolean fastRomBank = (bank & 0x80) != 0 && fastRomEnabled;
        if ((bank & 0x40) != 0) {
            // Banks $40-$7F and $C0-$FF: all cartridge, except work RAM at $7E-$7F.
            return fastRomBank ? FAST_CYCLES : SLOW_CYCLES;
        }
        if (offset < 0x2000) {
            return SLOW_CYCLES;
        }
        if (offset < 0x4000) {
            return FAST_CYCLES;
        }
        if (offset < 0x4200) {
            return EXTRA_SLOW_CYCLES;
        }
        if (offset < 0x6000) {
            return FAST_CYCLES;
        }
        if (offset < 0x8000) {
            return SLOW_CYCLES;
        }
        return fastRomBank ? FAST_CYCLES : SLOW_CYCLES;
    }

    /** Installs (or, with null, removes) a listener for hardware register writes. */
    public void setRegisterWriteListener(RegisterWriteListener listener) {
        this.registerWriteListener = listener;
    }

    /** The last value on the data bus, for open-bus reads. */
    public int openBus() {
        return openBus;
    }

    // =============================================================================================
    // A-bus decoding (shared with DMA, which brings its own timing)
    // =============================================================================================

    @Override
    public int readA(int address) {
        int bank = address >>> 16;
        int offset = address & 0xFFFF;
        int value = NOT_DRIVEN;
        if (bank == 0x7E || bank == 0x7F) {
            value = wram.read(address & 0x1FFFF);
        } else if ((bank & 0x40) == 0 && offset < 0x2000) {
            value = wram.read(offset);
        } else if ((bank & 0x40) == 0 && offset >= 0x2100 && offset < 0x2200) {
            value = readB(offset & 0xFF);
        } else if ((bank & 0x40) == 0 && offset >= 0x4000 && offset < 0x4400) {
            value = readCpuIo(offset);
        } else {
            value = cartridge.read(address);
        }
        return value == NOT_DRIVEN ? openBus : value;
    }

    @Override
    public void writeA(int address, int value) {
        int bank = address >>> 16;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            wram.write(address & 0x1FFFF, value);
        } else if ((bank & 0x40) == 0 && offset < 0x2000) {
            wram.write(offset, value);
        } else if ((bank & 0x40) == 0 && offset >= 0x2100 && offset < 0x2200) {
            writeB(offset & 0xFF, value);
        } else if ((bank & 0x40) == 0 && offset >= 0x4000 && offset < 0x4400) {
            writeCpuIo(offset, value);
        } else {
            cartridge.write(address, value);
        }
    }

    // =============================================================================================
    // B-bus: the 256 registers at $2100-$21FF
    // =============================================================================================

    @Override
    public int readB(int register) {
        int value;
        if (register < 0x40) {
            value = ppu.read(register);
        } else if (register < 0x80) {
            value = apu.read(register & 3); // four ports, mirrored through $217F
        } else if (register == 0x80) {
            value = wram.readPort();
        } else {
            value = NOT_DRIVEN;
        }
        return value == NOT_DRIVEN ? openBus : value;
    }

    @Override
    public void writeB(int register, int value) {
        if (registerWriteListener != null) {
            registerWriteListener.onRegisterWrite(0x2100 | register, value);
        }
        if (register < 0x40) {
            ppu.write(register, value);
        } else if (register < 0x80) {
            apu.write(register & 3, value);
        } else if (register == 0x80) {
            wram.writePort(value);
        } else if (register <= 0x83) {
            wram.writePortAddress(register - 0x81, value);
        }
    }

    // =============================================================================================
    // CPU I/O registers ($4000-$43FF)
    // =============================================================================================

    private int readCpuIo(int offset) {
        if (offset >= 0x4300 && offset < 0x4380) {
            return dma.read(offset);
        }
        return switch (offset) {
            case 0x4016 -> (openBus & 0xFC) | joypads.readSerial(0);
            // $4017 bits 4-2 are wired high on the board.
            case 0x4017 -> (openBus & 0xE0) | 0x1C | joypads.readSerial(1);
            case 0x4210 -> interrupts.readRdnmi(openBus);
            case 0x4211 -> interrupts.readTimeup(openBus);
            case 0x4212 -> interrupts.readHvbjoy(openBus);
            case 0x4213 -> wrio; // RDIO: reads back the I/O port pins (nothing else drives them)
            case 0x4214, 0x4215, 0x4216, 0x4217 -> math.read(offset);
            case 0x4218, 0x4219, 0x421A, 0x421B, 0x421C, 0x421D, 0x421E, 0x421F ->
                    joypads.readAutoResult(offset - 0x4218);
            default -> NOT_DRIVEN; // $4200-$420F are write-only
        };
    }

    private void writeCpuIo(int offset, int value) {
        if (registerWriteListener != null) {
            registerWriteListener.onRegisterWrite(offset, value);
        }
        if (offset >= 0x4300 && offset < 0x4380) {
            dma.write(offset, value);
            return;
        }
        switch (offset) {
            case 0x4016 -> joypads.writeLatch(value);
            case 0x4200 -> interrupts.writeNmitimen(value);
            case 0x4201 -> writeWrio(value);
            case 0x4202, 0x4203, 0x4204, 0x4205, 0x4206 -> math.write(offset, value);
            case 0x4207, 0x4208, 0x4209, 0x420A -> interrupts.writeTimer(offset - 0x4207, value);
            case 0x420B -> dma.runGeneralDma(value);
            case 0x420C -> dma.writeHdmaEnable(value);
            case 0x420D -> fastRomEnabled = (value & 1) != 0;
            default -> {
                // Unused I/O addresses ignore writes.
            }
        }
    }

    /**
     * $4201 WRIO. Bit 7 is also connected to the PPU's counter latch (used by light guns), so
     * pulling it from 1 to 0 latches the H/V counters.
     */
    private void writeWrio(int value) {
        boolean wasHigh = (wrio & 0x80) != 0;
        wrio = value;
        if (wasHigh && (value & 0x80) == 0) {
            ppu.latchCounters();
        }
    }
}
