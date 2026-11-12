package jfc.dma;

import jfc.system.MasterClock;

/**
 * The 5A22's eight-channel DMA controller.
 *
 * <p>The SNES has two address buses. The 24-bit <b>A-bus</b> is the CPU's own: ROM, work RAM,
 * SRAM. The 8-bit <b>B-bus</b> reaches the PPU, APU and WRAM ports ($2100-$21FF). DMA always
 * copies between the two, one byte every 8 master cycles. That's about 2.7 MB/s, several times
 * faster than a CPU copy loop. The CPU is paused while it runs.
 *
 * <p>A transfer <b>pattern</b> chooses which B-bus registers receive successive bytes, so a
 * single channel can feed a register pair such as VRAM's $2118/$2119:
 * <pre>
 *   pattern 0: p                  (1 register)          e.g. CGRAM data $2122
 *   pattern 1: p, p+1             (2 registers)         e.g. VRAM data $2118/$2119
 *   pattern 2: p, p               (1 register, twice)   e.g. write-twice scroll registers
 *   pattern 3: p, p, p+1, p+1
 *   pattern 4: p, p+1, p+2, p+3
 *   pattern 5: p, p+1, p, p+1
 *   pattern 6: same as 2,   pattern 7: same as 3
 * </pre>
 *
 * <p>Writing a bit mask to $420B (MDMAEN) runs the selected channels in order 0-7.
 *
 * <h2>HDMA</h2>
 * HDMA ("H-blank DMA") uses the same channels to write a few bytes to PPU registers during
 * the horizontal blank before each line. That's how games make gradients, wavy water, perspective
 * floors and shaped windows without spending CPU time. Each enabled channel ($420C) walks a
 * table in ROM or RAM made of entries:
 * <pre>
 *   line count byte:  bit 7 = repeat, bits 6-0 = number of lines (0 ends the table)
 *   data:             one transfer unit (1, 2 or 4 bytes, by pattern) - or, in repeat mode,
 *                     one unit for every line
 *   (indirect mode:   a 16-bit pointer to the data instead of the data itself)
 * </pre>
 * Without the repeat bit, an entry sends one unit and then holds for the remaining lines.
 * The table restarts from its beginning ($43x2-$43x3) at the top of every frame.
 */
public final class DmaController {

    /** What DMA needs from the system: decoded A-bus and B-bus access without CPU timing. */
    public interface Bus {
        int readA(int address);

        void writeA(int address, int value);

        int readB(int register);

        void writeB(int register, int value);
    }

    private static final int[][] PATTERNS = {
        {0}, {0, 1}, {0, 0}, {0, 0, 1, 1}, {0, 1, 2, 3}, {0, 1, 0, 1}, {0, 0}, {0, 0, 1, 1},
    };
    /** Master cycles per byte, and the fixed costs for starting DMA and each channel. */
    private static final int CYCLES_PER_BYTE = 8;
    private static final int CYCLES_TO_START = 8;
    private static final int CYCLES_PER_CHANNEL = 8;

    private final DmaChannel[] channels = new DmaChannel[8];
    private final MasterClock clock;
    private Bus bus;
    private int hdmaEnabled;

    public DmaController(MasterClock clock) {
        this.clock = clock;
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new DmaChannel();
        }
    }

    public void setBus(Bus bus) {
        this.bus = bus;
    }

    /** Reads $4300-$437F. Returns -1 where nothing is connected. */
    public int read(int address) {
        return channels[(address >> 4) & 7].read(address & 0xF);
    }

    /** Writes $4300-$437F. */
    public void write(int address, int value) {
        channels[(address >> 4) & 7].write(address & 0xF, value);
    }

    /** $420C HDMAEN: which channels run HDMA. Takes effect from the next frame. */
    public void writeHdmaEnable(int value) {
        hdmaEnabled = value;
    }

    public int hdmaEnabled() {
        return hdmaEnabled;
    }

    // HDMA costs: fixed overhead per line, then per channel, per byte and per table byte read.
    private static final int HDMA_LINE_OVERHEAD = 18;

    /**
     * Restarts the tables of all enabled HDMA channels. Runs at the top of every frame.
     *
     * <p>Every channel, enabled or not, gets its "finished" state cleared here. That matters for
     * channels a game enables <i>later</i> in the frame: they then run from whatever their
     * table address ($43x8-9) and line counter ($43xA) hold, without a table restart. Yoshi's
     * Island does this: it sets those registers itself and enables HDMA a few lines down (its
     * message box window comes from such a channel).
     *
     * <p>The "transfer on this line" flag is cleared too, and set again (on every channel) only
     * when at least one channel is enabled at this point, which is when the hardware runs its
     * setup step. This follows bsnes; it decides whether a channel enabled mid-frame transfers
     * on its first line or only after its counter runs out.
     *
     * @return master cycles the CPU is paused for
     */
    public int initHdma() {
        int cycles = 0;
        boolean setup = hdmaEnabled != 0;
        for (int i = 0; i < channels.length; i++) {
            DmaChannel channel = channels[i];
            channel.hdmaTerminated = false;
            channel.hdmaDoTransfer = setup;
            if ((hdmaEnabled & (1 << i)) == 0) {
                continue;
            }
            channel.hdmaTableAddress = channel.aAddress;
            channel.hdmaTerminated = false;
            cycles += CYCLES_PER_CHANNEL + loadHdmaEntry(channel);
        }
        return cycles == 0 ? 0 : HDMA_LINE_OVERHEAD + cycles;
    }

    /**
     * Performs one line's HDMA transfers. Runs in the horizontal blank of every line from 0
     * through the last visible line.
     *
     * @return master cycles the CPU is paused for
     */
    public int runHdmaLine() {
        int cycles = 0;
        for (int i = 0; i < channels.length; i++) {
            DmaChannel channel = channels[i];
            if ((hdmaEnabled & (1 << i)) == 0 || channel.hdmaTerminated) {
                continue;
            }
            cycles += CYCLES_PER_CHANNEL;
            if (channel.hdmaDoTransfer) {
                cycles += transferHdmaUnit(channel);
            }
            // Count the line off. The counter is 8 bits with the repeat flag on top, so
            // repeat mode keeps transferring until the count part reaches zero.
            channel.hdmaLineCounter = (channel.hdmaLineCounter - 1) & 0xFF;
            channel.hdmaDoTransfer = (channel.hdmaLineCounter & 0x80) != 0;
            if ((channel.hdmaLineCounter & 0x7F) == 0) {
                cycles += loadHdmaEntry(channel);
            }
        }
        return cycles == 0 ? 0 : HDMA_LINE_OVERHEAD + cycles;
    }

    /** Reads the next table entry's header (line count, and pointer in indirect mode). */
    private int loadHdmaEntry(DmaChannel channel) {
        channel.hdmaLineCounter = readTableByte(channel);
        int cycles = CYCLES_PER_BYTE;
        if (channel.isHdmaIndirect()) {
            int low = readTableByte(channel);
            int high = readTableByte(channel);
            channel.byteCount = (high << 8) | low; // $43x5-6 hold the indirect address
            cycles += 2 * CYCLES_PER_BYTE;
        }
        if (channel.hdmaLineCounter == 0) {
            channel.hdmaTerminated = true;
        }
        channel.hdmaDoTransfer = true;
        return cycles;
    }

    private int readTableByte(DmaChannel channel) {
        int address = (channel.aBank << 16) | channel.hdmaTableAddress;
        channel.hdmaTableAddress = (channel.hdmaTableAddress + 1) & 0xFFFF;
        return isReachableByDma(address) ? bus.readA(address) : 0;
    }

    /** Sends one transfer unit, taking the data from the table or the indirect address. */
    private int transferHdmaUnit(DmaChannel channel) {
        int[] pattern = PATTERNS[channel.pattern()];
        for (int offset : pattern) {
            int aAddress;
            if (channel.isHdmaIndirect()) {
                aAddress = (channel.indirectBank << 16) | channel.byteCount;
                channel.byteCount = (channel.byteCount + 1) & 0xFFFF;
            } else {
                aAddress = (channel.aBank << 16) | channel.hdmaTableAddress;
                channel.hdmaTableAddress = (channel.hdmaTableAddress + 1) & 0xFFFF;
            }
            int bRegister = (channel.bBusRegister + offset) & 0xFF;
            if (channel.isBToA()) {
                int value = bus.readB(bRegister);
                if (isReachableByDma(aAddress)) {
                    bus.writeA(aAddress, value);
                }
            } else {
                bus.writeB(bRegister, isReachableByDma(aAddress) ? bus.readA(aAddress) : 0);
            }
        }
        return pattern.length * CYCLES_PER_BYTE;
    }

    /**
     * $420B MDMAEN: run a general-purpose DMA on every channel whose bit is set. Returns only when
     * all transfers are complete; the CPU is halted meanwhile, and the time passes on the clock.
     */
    public void runGeneralDma(int channelMask) {
        if (channelMask == 0) {
            return;
        }
        // DMA starts on an 8-cycle boundary of the master clock, then pays a fixed setup cost.
        int misalignment = (int) (clock.totalCycles() % CYCLES_PER_BYTE);
        clock.advance((CYCLES_PER_BYTE - misalignment) % CYCLES_PER_BYTE + CYCLES_TO_START);
        for (int i = 0; i < channels.length; i++) {
            if ((channelMask & (1 << i)) != 0) {
                clock.advance(CYCLES_PER_CHANNEL);
                transfer(channels[i]);
            }
        }
    }

    private void transfer(DmaChannel channel) {
        int[] pattern = PATTERNS[channel.pattern()];
        int step = channel.aAddressStep();
        int index = 0;
        do {
            int bRegister = (channel.bBusRegister + pattern[index % pattern.length]) & 0xFF;
            int aAddress = (channel.aBank << 16) | channel.aAddress;
            if (channel.isBToA()) {
                int value = bus.readB(bRegister);
                if (isReachableByDma(aAddress)) {
                    bus.writeA(aAddress, value);
                }
            } else {
                int value = isReachableByDma(aAddress) ? bus.readA(aAddress) : 0;
                bus.writeB(bRegister, value);
            }
            clock.advance(CYCLES_PER_BYTE);
            // The A-bus address steps within its bank; the bank itself never changes.
            channel.aAddress = (channel.aAddress + step) & 0xFFFF;
            channel.byteCount = (channel.byteCount - 1) & 0xFFFF;
            index++;
        } while (channel.byteCount != 0);
    }

    /**
     * The A-bus side of a DMA can't reach the B-bus registers or the DMA registers themselves,
     * since the chip can't drive both buses with the same address at once.
     * <i>Simplification:</i> hardware leaves open bus there; we use 0.
     */
    private static boolean isReachableByDma(int address) {
        int bank = address >> 16;
        int offset = address & 0xFFFF;
        if ((bank & 0x40) != 0) {
            return true;
        }
        boolean bBusRegisters = offset >= 0x2100 && offset < 0x2200;
        boolean dmaRegisters = offset >= 0x4300 && offset < 0x4380;
        boolean dmaEnables = offset == 0x420B || offset == 0x420C;
        return !(bBusRegisters || dmaRegisters || dmaEnables);
    }
}
