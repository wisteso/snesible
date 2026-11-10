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
 * <p>HDMA ("H-blank DMA") uses the same channels to change registers once per scanline. It comes
 * in Milestone 6; for now $420C is only stored.
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

    /** $420C HDMAEN: which channels run HDMA each frame (used from Milestone 6). */
    public void writeHdmaEnable(int value) {
        hdmaEnabled = value;
    }

    public int hdmaEnabled() {
        return hdmaEnabled;
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
