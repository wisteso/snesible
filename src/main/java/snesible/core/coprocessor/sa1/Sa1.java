package snesible.core.coprocessor.sa1;

import static snesible.core.observe.Observable.wrapSa1;

import java.util.Arrays;
import snesible.core.cartridge.Cartridge;
import snesible.core.cartridge.Mirroring;
import snesible.core.coprocessor.Coprocessor;
import snesible.core.cpu.Cpu;
import snesible.core.cpu.CpuBus;

/**
 * An SA-1 cartridge board.
 *
 * <p>The SA-1 is a second 65816, clocked at 10.74 MHz (three times the console CPU's usual
 * speed), plus memory and helper hardware on the cartridge. The two CPUs share the ROM, 2 KiB
 * of fast "I-RAM" and the (usually battery-backed) "BW-RAM", and talk to each other through
 * message registers and interrupts. Games typically run their heavy logic on the SA-1 and
 * leave the console CPU to feed the PPU.
 *
 * <p>The SA-1 core is this emulator's ordinary {@link Cpu}: the 65816 doesn't care whose bus it
 * is on, so it simply gets the SA-1's bus ({@link Sa1Side}).
 *
 * <h2>Address maps</h2>
 * <pre>
 *                               console CPU                 SA-1 CPU
 *   $00-$3F,$80-$BF:0000-07FF   (work RAM)                  I-RAM
 *                   2200-23FF   SA-1 registers              SA-1 registers
 *                   3000-37FF   I-RAM                       I-RAM
 *                   6000-7FFF   BW-RAM, 8 KiB block BMAPS   BW-RAM block BMAP, or bitmap view
 *                   8000-FFFF   ROM (LoROM views, Super MMC)
 *   $40-$4F                     BW-RAM, linear              BW-RAM, linear
 *   $60-$6F                     -                           BW-RAM as a bitmap (see below)
 *   $C0-$FF                     ROM (HiROM views, Super MMC)
 * </pre>
 * The ROM banking is in {@link SuperMmc}.
 *
 * <h2>Registers</h2>
 * Writes go to $2200-$225B, reads come from $2300-$230E. The ones used to coordinate the CPUs:
 * <pre>
 *   $2200 CCNT  (console) bit 7 IRQ to SA-1, bit 6 halt SA-1, bit 5 hold SA-1 in reset,
 *               bit 4 NMI to SA-1, bits 3-0 message to SA-1
 *   $2201 SIE   (console) enable IRQs from SA-1: bit 7 (SA-1 request), bit 5 (char DMA)
 *   $2202 SIC   (console) acknowledge them (same bits)
 *   $2203-8     (console) SA-1 reset, NMI and IRQ vectors
 *   $2209 SCNT  (SA-1)    bit 7 IRQ to console, bit 6/4 use SIV/SNV as the console's IRQ/NMI
 *               vectors, bits 3-0 message to console
 *   $220A CIE   (SA-1)    enable SA-1 interrupts: bit 7 from console, 6 timer, 5 DMA, 4 NMI
 *   $220B CIC   (SA-1)    acknowledge them
 *   $220C-F     (SA-1)    console NMI and IRQ vectors (SNV, SIV)
 *   $2300 SFR   (console) bit 7 IRQ from SA-1, bit 6 IVSW, bit 5 char DMA IRQ, bit 4 NVSW,
 *               bits 3-0 message from SA-1
 *   $2301 CFR   (SA-1)    bit 7 IRQ from console, 6 timer, 5 DMA, 4 NMI, bits 3-0 message
 * </pre>
 * Arithmetic ($2250-$2254, $2306-$230B) is in {@link Sa1Arithmetic}; DMA ($2230-$2239) and the
 * variable-length bit reader ($2258-$225B, $230C-$230D) are below.
 *
 * <h2>Timing</h2>
 * Like the SuperFX, the SA-1 catches up with the master clock whenever the console touches the
 * cartridge (see {@link Coprocessor}). The console fetches its own code from ROM, so in practice
 * the two CPUs take turns every instruction or so. One SA-1 cycle is 2 master cycles; BW-RAM
 * accesses take two.
 *
 * <p><i>Simplifications:</i> no bus-conflict waits when both CPUs want the ROM or BW-RAM at
 * once; write protection (SBWE, CBWE, BWPA, SIWP, CIWP) is not enforced; the SA-1 timer and
 * character-conversion DMA are not implemented yet.
 */
public final class Sa1 implements Coprocessor {

    private static final int IRAM_SIZE = 2048;
    /** Master cycles per SA-1 cycle: the SA-1 runs at half the master clock. */
    private static final int CYCLE = 2;

    private final Cartridge cartridge;
    private final byte[] bwram;
    private final byte[] iram = new byte[IRAM_SIZE];
    private final SuperMmc mmc;
    private final Sa1Arithmetic math = new Sa1Arithmetic();
    private final Sa1Side sa1Bus = new Sa1Side();
    private final Cpu cpu = new Cpu(wrapSa1(sa1Bus));

    private Host host;
    /** The master-clock time the SA-1 has run up to. */
    private long sa1Time;

    // ---- CCNT and the SA-1's interrupts (console -> SA-1)
    private boolean heldInReset = true;
    private boolean halted;
    private int messageToSa1;
    private int resetVector;
    private int nmiVector;
    private int irqVector;
    private boolean irqFromConsoleEnabled;
    private boolean timerIrqEnabled;
    private boolean dmaIrqEnabled;
    private boolean nmiFromConsoleEnabled;
    private boolean irqFromConsoleFlag;
    private boolean timerIrqFlag;
    private boolean dmaIrqFlag;
    private boolean nmiFromConsoleFlag;

    // ---- SCNT and the console's interrupts (SA-1 -> console)
    private int messageToConsole;
    private boolean useConsoleIrqVector;
    private boolean useConsoleNmiVector;
    private int consoleNmiVector;
    private int consoleIrqVector;
    private boolean irqToConsoleEnabled;
    private boolean charDmaIrqEnabled;
    private boolean irqToConsoleFlag;
    private boolean charDmaIrqFlag;

    // ---- BW-RAM mapping
    /** BMAPS: the 8 KiB BW-RAM block the console sees at $6000-$7FFF. */
    private int consoleBwramBlock;
    /** BMAP: the SA-1's block at $6000-$7FFF (bitmap blocks when bit 7 is set). */
    private int sa1BwramBlock;
    private boolean sa1BwramBitmap;
    /** BBF bit 7: the bitmap view packs 2-bit pixels (else 4-bit). */
    private boolean bitmap2bpp;

    // ---- DMA
    private boolean dmaEnabled;
    private boolean charConversion;
    private int dmaSource;        // 0 ROM, 1 BW-RAM, 2 I-RAM
    private boolean dmaToBwram;   // else to I-RAM
    private int dmaSourceAddress;
    private int dmaDestinationAddress;
    private int dmaCount;

    // ---- Variable-length bit reader
    private int bitStreamAddress;
    private int bitOffset;
    private int bitsPerRead = 16;
    private boolean autoIncrement;

    public Sa1(Cartridge cartridge) {
        this.cartridge = cartridge;
        this.bwram = cartridge.ramBytes().length > 0 ? cartridge.ramBytes() : new byte[2048];
        this.mmc = new SuperMmc(cartridge.romBytes());
    }

    @Override
    public void connect(Host host) {
        this.host = host;
    }

    @Override
    public void reset() {
        Arrays.fill(iram, (byte) 0);
        mmc.reset();
        math.reset();
        heldInReset = true;
        halted = false;
        messageToSa1 = messageToConsole = 0;
        resetVector = nmiVector = irqVector = 0;
        irqFromConsoleEnabled = timerIrqEnabled = dmaIrqEnabled = nmiFromConsoleEnabled = false;
        irqFromConsoleFlag = timerIrqFlag = dmaIrqFlag = nmiFromConsoleFlag = false;
        useConsoleIrqVector = useConsoleNmiVector = false;
        consoleNmiVector = consoleIrqVector = 0;
        irqToConsoleEnabled = charDmaIrqEnabled = irqToConsoleFlag = charDmaIrqFlag = false;
        consoleBwramBlock = sa1BwramBlock = 0;
        sa1BwramBitmap = bitmap2bpp = false;
        dmaEnabled = charConversion = dmaToBwram = false;
        dmaSource = dmaSourceAddress = dmaDestinationAddress = dmaCount = 0;
        bitStreamAddress = bitOffset = 0;
        bitsPerRead = 16;
        autoIncrement = false;
        sa1Time = host.masterCycles();
        updateConsoleIrq();
    }

    @Override
    public void catchUp() {
        long now = host.masterCycles();
        while (sa1Time < now) {
            boolean idle = heldInReset || halted || cpu.isStopped()
                    || (cpu.isWaitingForInterrupt() && !sa1IrqLine() && !nmiFromConsoleFlag);
            if (idle) {
                // Nothing can wake the SA-1 except the console, which can only act after this
                // catch-up returns. So skip straight to the present.
                sa1Time = now;
                return;
            }
            cpu.step(); // its bus cycles advance sa1Time
        }
    }

    /** The SA-1 CPU, for debugging tools. */
    public Cpu cpu() {
        return cpu;
    }

    /**
     * The SA-1's 2 KiB of on-chip I-RAM itself (not a copy), for memory viewers. Unlike
     * BW-RAM it isn't part of the cartridge's RAM ({@link Cartridge#ramBytes()}), so this is
     * the only way to see it from outside. Changing a byte here changes what both CPUs read.
     *
     * <p><b>Debug support:</b> not needed to emulate the console; it exists for the visual
     * memory debugger (viewing and editing I-RAM) and other tools.
     */
    public byte[] iramBytes() {
        return iram;
    }

    // =============================================================================================
    // Console side
    // =============================================================================================

    @Override
    public int read(int address) {
        catchUp();
        int bank = (address >>> 16) & 0xFF;
        int offset = address & 0xFFFF;
        if ((bank & 0x40) == 0) { // $00-$3F, $80-$BF
            if (offset >= 0x2200 && offset < 0x2400) {
                return readRegister(offset);
            }
            if (offset >= 0x3000 && offset < 0x3800) {
                return iram[offset & 0x7FF] & 0xFF;
            }
            if (offset >= 0x6000 && offset < 0x8000) {
                return readBwram(consoleBwramBlock * 0x2000 + (offset & 0x1FFF));
            }
            if (offset >= 0x8000) {
                return readRomFromConsole(bank, offset);
            }
            return Cartridge.OPEN_BUS;
        }
        if ((bank & 0xC0) == 0xC0) {
            return mmc.read(bank, offset);
        }
        if (bank >= 0x40 && bank < 0x50) {
            return readBwram((bank & 0x0F) << 16 | offset);
        }
        return Cartridge.OPEN_BUS;
    }

    @Override
    public void write(int address, int value) {
        catchUp();
        int bank = (address >>> 16) & 0xFF;
        int offset = address & 0xFFFF;
        if ((bank & 0x40) == 0) {
            if (offset >= 0x2200 && offset < 0x2300) {
                writeRegister(offset, value);
            } else if (offset >= 0x3000 && offset < 0x3800) {
                iram[offset & 0x7FF] = (byte) value;
            } else if (offset >= 0x6000 && offset < 0x8000) {
                writeBwram(consoleBwramBlock * 0x2000 + (offset & 0x1FFF), value);
            }
        } else if (bank >= 0x40 && bank < 0x50) {
            writeBwram((bank & 0x0F) << 16 | offset, value);
        }
    }

    /**
     * The console's interrupt vectors can be replaced by SNV/SIV, so the SA-1 decides where the
     * console's NMI and IRQ handlers are.
     */
    private int readRomFromConsole(int bank, int offset) {
        if (bank == 0x00 || bank == 0x80) {
            switch (offset) {
                case 0xFFEA, 0xFFEB -> {
                    if (useConsoleNmiVector) {
                        return offset == 0xFFEA ? consoleNmiVector & 0xFF : consoleNmiVector >> 8;
                    }
                }
                case 0xFFEE, 0xFFEF -> {
                    if (useConsoleIrqVector) {
                        return offset == 0xFFEE ? consoleIrqVector & 0xFF : consoleIrqVector >> 8;
                    }
                }
                default -> {
                    // an ordinary ROM byte
                }
            }
        }
        return mmc.read(bank, offset);
    }

    // =============================================================================================
    // SA-1 side
    // =============================================================================================

    /**
     * The SA-1 CPU's bus. Each access charges its time to {@link #sa1Time}. A separate class
     * because {@link CpuBus#read} and the console-side {@link Sa1#read} share a signature.
     */
    private final class Sa1Side implements CpuBus {

        /** The SA-1's own open-bus value. */
        private int dataBus;

        @Override
        public int read(int address) {
            int bank = (address >>> 16) & 0xFF;
            int offset = address & 0xFFFF;
            sa1Time += isBwram(bank, offset) ? 2 * CYCLE : CYCLE;
            int value = readSa1(bank, offset);
            if (value != Cartridge.OPEN_BUS) {
                dataBus = value;
            }
            return dataBus;
        }

        @Override
        public void write(int address, int value) {
            int bank = (address >>> 16) & 0xFF;
            int offset = address & 0xFFFF;
            sa1Time += isBwram(bank, offset) ? 2 * CYCLE : CYCLE;
            dataBus = value;
            writeSa1(bank, offset, value);
        }

        @Override
        public void idle() {
            sa1Time += CYCLE;
        }

        private boolean isBwram(int bank, int offset) {
            boolean window = (bank & 0x40) == 0 && offset >= 0x6000 && offset < 0x8000;
            return window || (bank >= 0x40 && bank < 0x70);
        }
    }

    private int readSa1(int bank, int offset) {
        if ((bank & 0x40) == 0) {
            if (offset < 0x0800 || (offset >= 0x3000 && offset < 0x3800)) {
                return iram[offset & 0x7FF] & 0xFF;
            }
            if (offset >= 0x2200 && offset < 0x2400) {
                return readRegister(offset);
            }
            if (offset >= 0x6000 && offset < 0x8000) {
                int windowOffset = offset & 0x1FFF;
                return sa1BwramBitmap
                        ? readBitmap(sa1BwramBlock * 0x2000 + windowOffset)
                        : readBwram((sa1BwramBlock & 0x1F) * 0x2000 + windowOffset);
            }
            if (offset >= 0x8000) {
                return readRomFromSa1(bank, offset);
            }
            return Cartridge.OPEN_BUS;
        }
        if ((bank & 0xC0) == 0xC0) {
            return mmc.read(bank, offset);
        }
        if (bank >= 0x40 && bank < 0x50) {
            return readBwram((bank & 0x0F) << 16 | offset);
        }
        if (bank >= 0x60 && bank < 0x70) {
            return readBitmap((bank & 0x0F) << 16 | offset);
        }
        return Cartridge.OPEN_BUS;
    }

    private void writeSa1(int bank, int offset, int value) {
        if ((bank & 0x40) == 0) {
            if (offset < 0x0800 || (offset >= 0x3000 && offset < 0x3800)) {
                iram[offset & 0x7FF] = (byte) value;
            } else if (offset >= 0x2200 && offset < 0x2300) {
                writeRegister(offset, value);
            } else if (offset >= 0x6000 && offset < 0x8000) {
                int windowOffset = offset & 0x1FFF;
                if (sa1BwramBitmap) {
                    writeBitmap(sa1BwramBlock * 0x2000 + windowOffset, value);
                } else {
                    writeBwram((sa1BwramBlock & 0x1F) * 0x2000 + windowOffset, value);
                }
            }
        } else if (bank >= 0x40 && bank < 0x50) {
            writeBwram((bank & 0x0F) << 16 | offset, value);
        } else if (bank >= 0x60 && bank < 0x70) {
            writeBitmap((bank & 0x0F) << 16 | offset, value);
        }
    }

    /** The SA-1's own vectors come from CRV, CNV and CIV, not from ROM. */
    private int readRomFromSa1(int bank, int offset) {
        if (bank == 0x00 || bank == 0x80) {
            int vector = switch (offset & 0xFFFE) {
                case 0xFFFC -> resetVector;
                case 0xFFEA, 0xFFFA -> nmiVector;  // native and emulation NMI
                case 0xFFEE, 0xFFFE -> irqVector;  // native and emulation IRQ
                default -> -1;
            };
            if (vector >= 0) {
                return (offset & 1) == 0 ? vector & 0xFF : vector >> 8;
            }
        }
        return mmc.read(bank, offset);
    }

    // =============================================================================================
    // BW-RAM, including the bitmap view
    // =============================================================================================

    private int readBwram(int offset) {
        return bwram[Mirroring.mirror(offset, bwram.length)] & 0xFF;
    }

    private void writeBwram(int offset, int value) {
        int index = Mirroring.mirror(offset, bwram.length);
        if (bwram[index] != (byte) value) {
            bwram[index] = (byte) value;
            cartridge.markRamChanged();
        }
    }

    /**
     * The bitmap view ($60-$6F, or the SA-1's $6000-$7FFF with BMAP bit 7) shows BW-RAM as one
     * pixel per address: each byte holds two 4-bit pixels (low nibble first) or four 2-bit ones.
     * Programs can then draw with ordinary byte stores, while the data stays packed.
     */
    private int readBitmap(int pixel) {
        if (bitmap2bpp) {
            int shift = (pixel & 3) * 2;
            return readBwram(pixel >> 2) >> shift & 0x03;
        }
        int shift = (pixel & 1) * 4;
        return readBwram(pixel >> 1) >> shift & 0x0F;
    }

    private void writeBitmap(int pixel, int value) {
        if (bitmap2bpp) {
            int shift = (pixel & 3) * 2;
            int old = readBwram(pixel >> 2);
            writeBwram(pixel >> 2, (old & ~(0x03 << shift)) | (value & 0x03) << shift);
        } else {
            int shift = (pixel & 1) * 4;
            int old = readBwram(pixel >> 1);
            writeBwram(pixel >> 1, (old & ~(0x0F << shift)) | (value & 0x0F) << shift);
        }
    }

    // =============================================================================================
    // Registers
    // =============================================================================================

    private int readRegister(int address) {
        return switch (address) {
            case 0x2300 -> (irqToConsoleFlag ? 0x80 : 0)          // SFR
                    | (useConsoleIrqVector ? 0x40 : 0)
                    | (charDmaIrqFlag ? 0x20 : 0)
                    | (useConsoleNmiVector ? 0x10 : 0)
                    | messageToConsole;
            case 0x2301 -> (irqFromConsoleFlag ? 0x80 : 0)        // CFR
                    | (timerIrqFlag ? 0x40 : 0)
                    | (dmaIrqFlag ? 0x20 : 0)
                    | (nmiFromConsoleFlag ? 0x10 : 0)
                    | messageToSa1;
            case 0x2306, 0x2307, 0x2308, 0x2309, 0x230A -> math.readResult(address - 0x2306);
            case 0x230B -> math.readOverflow();
            case 0x230C -> readBitStream() & 0xFF;
            case 0x230D -> {
                int high = readBitStream() >> 8 & 0xFF;
                if (autoIncrement) {
                    advanceBitStream();
                }
                yield high;
            }
            case 0x230E -> 0x23; // VC: chip version
            default -> Cartridge.OPEN_BUS; // includes the unimplemented timer counters
        };
    }

    private void writeRegister(int address, int value) {
        switch (address) {
            case 0x2200 -> writeSa1Control(value);
            case 0x2201 -> {                                       // SIE
                irqToConsoleEnabled = (value & 0x80) != 0;
                charDmaIrqEnabled = (value & 0x20) != 0;
                updateConsoleIrq();
            }
            case 0x2202 -> {                                       // SIC
                if ((value & 0x80) != 0) {
                    irqToConsoleFlag = false;
                }
                if ((value & 0x20) != 0) {
                    charDmaIrqFlag = false;
                }
                updateConsoleIrq();
            }
            case 0x2203 -> resetVector = (resetVector & 0xFF00) | value;
            case 0x2204 -> resetVector = (value << 8) | (resetVector & 0xFF);
            case 0x2205 -> nmiVector = (nmiVector & 0xFF00) | value;
            case 0x2206 -> nmiVector = (value << 8) | (nmiVector & 0xFF);
            case 0x2207 -> irqVector = (irqVector & 0xFF00) | value;
            case 0x2208 -> irqVector = (value << 8) | (irqVector & 0xFF);
            case 0x2209 -> writeConsoleControl(value);
            case 0x220A -> {                                       // CIE
                irqFromConsoleEnabled = (value & 0x80) != 0;
                timerIrqEnabled = (value & 0x40) != 0;
                dmaIrqEnabled = (value & 0x20) != 0;
                nmiFromConsoleEnabled = (value & 0x10) != 0;
                updateSa1Irq();
            }
            case 0x220B -> {                                       // CIC
                if ((value & 0x80) != 0) {
                    irqFromConsoleFlag = false;
                }
                if ((value & 0x40) != 0) {
                    timerIrqFlag = false;
                }
                if ((value & 0x20) != 0) {
                    dmaIrqFlag = false;
                }
                if ((value & 0x10) != 0) {
                    nmiFromConsoleFlag = false;
                }
                updateSa1Irq();
            }
            case 0x220C -> consoleNmiVector = (consoleNmiVector & 0xFF00) | value;
            case 0x220D -> consoleNmiVector = (value << 8) | (consoleNmiVector & 0xFF);
            case 0x220E -> consoleIrqVector = (consoleIrqVector & 0xFF00) | value;
            case 0x220F -> consoleIrqVector = (value << 8) | (consoleIrqVector & 0xFF);
            case 0x2220, 0x2221, 0x2222, 0x2223 -> mmc.writeBank(address - 0x2220, value);
            case 0x2224 -> consoleBwramBlock = value & 0x1F;       // BMAPS
            case 0x2225 -> {                                       // BMAP
                sa1BwramBitmap = (value & 0x80) != 0;
                sa1BwramBlock = value & 0x7F;
            }
            case 0x2230 -> {                                       // DCNT
                dmaEnabled = (value & 0x80) != 0;
                charConversion = (value & 0x20) != 0;
                dmaToBwram = (value & 0x04) != 0;
                dmaSource = value & 0x03;
            }
            case 0x2232 -> dmaSourceAddress = (dmaSourceAddress & 0xFFFF00) | value;
            case 0x2233 -> dmaSourceAddress = (dmaSourceAddress & 0xFF00FF) | value << 8;
            case 0x2234 -> dmaSourceAddress = (dmaSourceAddress & 0x00FFFF) | value << 16;
            case 0x2235 -> dmaDestinationAddress = (dmaDestinationAddress & 0xFFFF00) | value;
            case 0x2236 -> {
                dmaDestinationAddress = (dmaDestinationAddress & 0xFF00FF) | value << 8;
                // For an I-RAM destination, writing the middle byte starts the transfer.
                if (dmaEnabled && !charConversion && !dmaToBwram) {
                    runDma();
                }
            }
            case 0x2237 -> {
                dmaDestinationAddress = (dmaDestinationAddress & 0x00FFFF) | value << 16;
                // For BW-RAM, the top byte is needed too, so writing it starts the transfer.
                if (dmaEnabled && !charConversion && dmaToBwram) {
                    runDma();
                }
            }
            case 0x2238 -> dmaCount = (dmaCount & 0xFF00) | value;
            case 0x2239 -> dmaCount = (value << 8) | (dmaCount & 0xFF);
            case 0x223F -> bitmap2bpp = (value & 0x80) != 0;      // BBF
            case 0x2250 -> math.writeControl(value);
            case 0x2251 -> math.writeMaLow(value);
            case 0x2252 -> math.writeMaHigh(value);
            case 0x2253 -> math.writeMbLow(value);
            case 0x2254 -> math.writeMbHigh(value);
            case 0x2258 -> writeBitStreamControl(value);
            case 0x2259 -> bitStreamAddress = (bitStreamAddress & 0xFFFF00) | value;
            case 0x225A -> bitStreamAddress = (bitStreamAddress & 0xFF00FF) | value << 8;
            case 0x225B -> {
                bitStreamAddress = (bitStreamAddress & 0x00FFFF) | value << 16;
                bitOffset = 0; // setting the address restarts the stream at a byte boundary
            }
            default -> {
                // Write protection ($2226-$222A), the timer ($2210-$2215) and character
                // conversion ($2231, $2240-$224F) aren't emulated.
            }
        }
    }

    /** $2200 CCNT, written by the console. */
    private void writeSa1Control(int value) {
        boolean wasHeldInReset = heldInReset;
        heldInReset = (value & 0x20) != 0;
        halted = (value & 0x40) != 0;
        messageToSa1 = value & 0x0F;
        if (wasHeldInReset && !heldInReset) {
            // Releasing reset starts the SA-1 from CRV (via the vector override above).
            cpu.reset();
        }
        if ((value & 0x80) != 0) {
            irqFromConsoleFlag = true;
        }
        if ((value & 0x10) != 0) {
            nmiFromConsoleFlag = true;
            if (nmiFromConsoleEnabled) {
                cpu.triggerNmi();
            }
        }
        updateSa1Irq();
    }

    /** $2209 SCNT, written by the SA-1. */
    private void writeConsoleControl(int value) {
        useConsoleIrqVector = (value & 0x40) != 0;
        useConsoleNmiVector = (value & 0x10) != 0;
        messageToConsole = value & 0x0F;
        if ((value & 0x80) != 0) {
            irqToConsoleFlag = true;
        }
        updateConsoleIrq();
    }

    private boolean sa1IrqLine() {
        return (irqFromConsoleFlag && irqFromConsoleEnabled)
                || (timerIrqFlag && timerIrqEnabled)
                || (dmaIrqFlag && dmaIrqEnabled);
    }

    private void updateSa1Irq() {
        cpu.setIrqLine(sa1IrqLine());
    }

    private void updateConsoleIrq() {
        boolean asserted = (irqToConsoleFlag && irqToConsoleEnabled)
                || (charDmaIrqFlag && charDmaIrqEnabled);
        host.setIrqLine(asserted);
    }

    // =============================================================================================
    // DMA
    // =============================================================================================

    /**
     * A normal SA-1 DMA: copies DTC bytes from ROM, BW-RAM or I-RAM to I-RAM or BW-RAM, then
     * raises the DMA interrupt flag. It happens instantly here.
     */
    private void runDma() {
        int count = dmaCount == 0 ? 0x10000 : dmaCount;
        for (int i = 0; i < count; i++) {
            int source = (dmaSourceAddress + i) & 0xFFFFFF;
            int value = switch (dmaSource) {
                case 0 -> mmc.read(source >>> 16, source & 0xFFFF);
                case 1 -> readBwram(source & 0xFFFFF);
                default -> iram[source & 0x7FF] & 0xFF;
            };
            int destination = dmaDestinationAddress + i;
            if (dmaToBwram) {
                writeBwram(destination & 0xFFFFF, value);
            } else {
                iram[destination & 0x7FF] = (byte) value;
            }
        }
        dmaSourceAddress = (dmaSourceAddress + count) & 0xFFFFFF;
        dmaDestinationAddress = (dmaDestinationAddress + count) & 0xFFFFFF;
        dmaCount = 0;
        dmaIrqFlag = true;
        updateSa1Irq();
    }

    // =============================================================================================
    // Variable-length bit reader
    // =============================================================================================

    /**
     * $2258 VBD: bit 7 auto-increment, bits 3-0 how many bits each read consumes (0 means 16).
     * In manual mode, writing this register itself advances the stream.
     */
    private void writeBitStreamControl(int value) {
        autoIncrement = (value & 0x80) != 0;
        bitsPerRead = value & 0x0F;
        if (bitsPerRead == 0) {
            bitsPerRead = 16;
        }
        if (!autoIncrement) {
            advanceBitStream();
        }
    }

    /**
     * $230C-$230D: the next 16 bits of the stream, starting {@link #bitOffset} bits into the
     * byte at {@link #bitStreamAddress}. Games use this to decode compressed data whose fields
     * aren't byte-aligned.
     */
    private int readBitStream() {
        int data = peekStream(bitStreamAddress)
                | peekStream(bitStreamAddress + 1) << 8
                | peekStream(bitStreamAddress + 2) << 16;
        return data >> bitOffset & 0xFFFF;
    }

    private void advanceBitStream() {
        bitOffset += bitsPerRead;
        bitStreamAddress = (bitStreamAddress + (bitOffset >> 3)) & 0xFFFFFF;
        bitOffset &= 7;
    }

    /** Reads stream data (ROM or BW-RAM) without side effects or time. */
    private int peekStream(int address) {
        int bank = (address >>> 16) & 0xFF;
        int offset = address & 0xFFFF;
        if (SuperMmc.isRom(bank, offset)) {
            return mmc.read(bank, offset);
        }
        if (bank >= 0x40 && bank < 0x50) {
            return readBwram((bank & 0x0F) << 16 | offset);
        }
        return 0;
    }
}
