package jfc.system;

import jfc.apu.ApuHandshakeStub;
import jfc.apu.ApuPorts;
import jfc.bus.SnesBus;
import jfc.cartridge.Cartridge;
import jfc.cpu.Cpu;
import jfc.cpu.io.ArithmeticUnit;
import jfc.cpu.io.InterruptControl;
import jfc.cpu.io.JoypadPorts;
import jfc.dma.DmaController;
import jfc.ppu.Ppu;

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
    private final MasterClock clock;
    private final WorkRam wram = new WorkRam();
    private final Ppu ppu;
    private final ApuPorts apu = new ApuHandshakeStub();
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
        this.interrupts = new InterruptControl(clock, ppu);
        this.bus = new SnesBus(clock, wram, cartridge, ppu, apu, interrupts, math, joypads, dma);
        this.cpu = new Cpu(bus);
        // The interrupt logic drives the CPU's NMI and IRQ pins, but the CPU can only be built
        // once its bus exists, and the bus needs the interrupt logic. So connect it afterwards.
        interrupts.connect(cpu);
        dma.setBus(bus);
        clock.setListener(this);
    }

    /** Presses the reset button: the CPU restarts from the cartridge's RESET vector. */
    public void reset() {
        cpu.reset();
    }

    /** Runs until the start of the next vertical blank, i.e. one complete picture. */
    public void runFrame() {
        frameComplete = false;
        while (!frameComplete) {
            cpu.step();
        }
    }

    @Override
    public void onLineStart(int line) {
        ppu.onLineStart(line);
        interrupts.onLineStart(line);
        if (line == ppu.vblankStartLine()) {
            if (interrupts.isAutoJoypadEnabled()) {
                joypads.autoRead();
            }
            frameComplete = true;
        }
    }

    @Override
    public void onBeamMoved(int line, int fromH, int toH) {
        interrupts.onBeamMoved(line, fromH, toH);
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
}
