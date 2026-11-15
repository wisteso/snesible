package jfc.core.system;

import jfc.core.apu.Apu;
import jfc.core.bus.SnesBus;
import jfc.core.cartridge.Cartridge;
import jfc.core.cartridge.CartridgeSlot;
import jfc.core.coprocessor.Coprocessor;
import jfc.core.coprocessor.Coprocessors;
import jfc.core.cpu.Cpu;
import jfc.core.cpu.io.ArithmeticUnit;
import jfc.core.cpu.io.InterruptControl;
import jfc.core.cpu.io.JoypadPorts;
import jfc.core.dma.DmaController;
import jfc.core.ppu.Ppu;

/**
 * The whole console: creates every component, wires them together, and runs frames.
 *
 * <pre>
 *            +--------------------------- 5A22 ----------------------------+
 *            |  65816 CPU   interrupts   math unit   joypads   DMA         |
 *            +------------------------------+------------------------------+
 *                                           | A-bus (24-bit)    B-bus (8-bit)
 *        cartridge (ROM/SRAM) -- work RAM --+------------------ PPU, APU ports, WRAM port
 * </pre>
 *
 * <p><b>How time flows:</b> the CPU drives everything. Each bus cycle it performs advances the
 * {@link MasterClock}, and the clock reports the beam position back here so the other chips
 * can react (NMI at vertical blank, timer IRQs, and later scanline rendering). There's no
 * separate scheduler. The CPU's own pace sets the pace of the whole system, which is the
 * "catch-up" design familiar from NES emulators.
 */
public final class Snes implements MasterClock.Listener {

    private final Cartridge cartridge;
    /** The chip on the cartridge board (SuperFX, ...), or null for a plain cartridge. */
    private final Coprocessor coprocessor;
    private final MasterClock clock;
    private final WorkRam wram = new WorkRam();
    private final Ppu ppu;
    private final Apu apu;
    private final ArithmeticUnit math = new ArithmeticUnit();
    private final JoypadPorts joypads = new JoypadPorts();
    private final DmaController dma;
    private final SnesBus bus;
    private final Cpu cpu;
    private final InterruptControl interrupts;

    /** Set when vertical blank begins, which is where {@link #runFrame()} stops. */
    private boolean frameComplete;

    public Snes(Cartridge cartridge) {
        this.cartridge = cartridge;
        boolean pal = cartridge.header().region().isPal();
        this.clock = new MasterClock(pal);
        this.ppu = new Ppu(clock, pal);
        this.dma = new DmaController(clock);
        this.apu = new Apu(clock, pal);
        this.interrupts = new InterruptControl(clock, ppu);
        // A coprocessor board answers for the whole cartridge space; otherwise the plain
        // cartridge does.
        this.coprocessor = Coprocessors.forCartridge(cartridge);
        CartridgeSlot slot = coprocessor != null ? coprocessor : cartridge;
        this.bus = new SnesBus(clock, wram, slot, ppu, apu, interrupts, math, joypads, dma);
        this.cpu = new Cpu(bus);
        // The interrupt logic drives the CPU's NMI and IRQ pins, but the CPU can only be built
        // once its bus exists, and the bus needs the interrupt logic. So connect it afterwards.
        interrupts.connect(cpu);
        dma.setBus(bus);
        clock.setListener(this);
        if (coprocessor != null) {
            coprocessor.connect(new Coprocessor.Host() {
                @Override
                public long masterCycles() {
                    return clock.totalCycles();
                }

                @Override
                public void setIrqLine(boolean asserted) {
                    interrupts.setCartridgeIrq(asserted);
                }
            });
        }
    }

    /**
     * Presses the reset button: the CPU restarts from the cartridge's RESET vector, and the
     * APU from its boot ROM, ready to receive the game's sound driver again.
     */
    public void reset() {
        apu.reset();
        if (coprocessor != null) {
            coprocessor.reset();
        }
        cpu.reset();
    }

    /**
     * Runs until the start of the next vertical blank, i.e. one complete picture. The APU is
     * then brought up to date, so a frame's worth of audio is ready to collect.
     */
    public void runFrame() {
        frameComplete = false;
        while (!frameComplete) {
            cpu.step();
        }
        apu.catchUp();
        if (coprocessor != null) {
            coprocessor.catchUp();
        }
    }

    /** HDMA runs at about dot 276, just after the visible part of each line ends. */
    private static final int HDMA_POSITION = 1104;
    /** Each picture line is drawn when the beam reaches its first visible dot. */
    private static final int RENDER_POSITION = Ppu.FIRST_VISIBLE_DOT * MasterClock.CYCLES_PER_DOT;

    @Override
    public void onLineStart(int line) {
        if (line == 0) {
            clock.stall(dma.initHdma());
        }
        if (coprocessor != null) {
            // Keep the coprocessor at most a line behind, so its interrupt arrives on time
            // even when the CPU is just waiting for it.
            coprocessor.catchUp();
        }
        ppu.onLineStart(line);
        interrupts.onLineStart(line);
        if (line == ppu.vblankStartLine()) {
            if (interrupts.isAutoJoypadEnabled()) {
                joypads.autoRead();
            }
            frameComplete = true;
        }
    }

    /**
     * Two things happen at fixed points on each line:
     * <ul>
     *   <li>the PPU draws the line when the beam reaches its first visible dot (picture line n
     *       appears on beam line n+1);</li>
     *   <li>HDMA for picture line n happens in the horizontal blank at the end of beam line n,
     *       so its register changes are in place when picture line n is drawn.</li>
     * </ul>
     */
    @Override
    public void onBeamMoved(int line, int fromH, int toH) {
        interrupts.onBeamMoved(line, fromH, toH);
        boolean pictureLine = line >= 1 && line <= ppu.visibleLines();
        if (pictureLine && fromH <= RENDER_POSITION && RENDER_POSITION < toH) {
            ppu.renderLine(line - 1);
        }
        boolean hdmaLine = line < ppu.vblankStartLine();
        if (hdmaLine && fromH <= HDMA_POSITION && HDMA_POSITION < toH) {
            clock.stall(dma.runHdmaLine());
        }
    }

    // ---- Component access, for the user interface, tests and debugging tools

    public Cpu cpu() {
        return cpu;
    }

    public SnesBus bus() {
        return bus;
    }

    public MasterClock clock() {
        return clock;
    }

    public Ppu ppu() {
        return ppu;
    }

    public WorkRam wram() {
        return wram;
    }

    public JoypadPorts joypads() {
        return joypads;
    }

    public Cartridge cartridge() {
        return cartridge;
    }

    /** The cartridge's coprocessor, or null when it has none. */
    public Coprocessor coprocessor() {
        return coprocessor;
    }

    public Apu apu() {
        return apu;
    }
}
