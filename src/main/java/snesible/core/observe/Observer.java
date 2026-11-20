package snesible.core.observe;

import snesible.core.apu.Spc700Bus;
import snesible.core.coprocessor.superfx.GsuBus;
import snesible.core.cpu.CpuBus;
import snesible.core.dma.DmaController;

/**
 * Something that wants to see every memory access the console's processors make, such as the
 * visual memory debugger in {@code snesible.debug}.
 *
 * <p><b>Debug support:</b> not needed to emulate the console (see {@link Observable}).
 *
 * <p>Each processor reaches memory through one bus object, and each method here is offered
 * that bus once, while the console is being built. It returns the object the processor should
 * use instead: normally a wrapper that records each access and passes it on unchanged.
 *
 * <p>The core never implements this interface and never depends on an implementation. An
 * observer is installed from outside with {@link Observable#install} before the
 * {@link snesible.core.system.Snes} is constructed. See {@link Observable} for the call sites.
 */
public interface Observer {

    /** The S-CPU (the console's 65816) and its bus: work RAM, cartridge, I/O registers. */
    CpuBus wrapCpu(CpuBus bus);

    /**
     * The DMA controller's bus. DMA and HDMA move data between the A-bus (work RAM,
     * cartridge) and the B-bus (PPU, APU ports, the work-RAM port) without the CPU.
     */
    DmaController.Bus wrapDma(DmaController.Bus bus);

    /** The SPC700 (the sound CPU) and its bus: the 64 KiB of sound RAM and its I/O registers. */
    Spc700Bus wrapSpc700(Spc700Bus bus);

    /**
     * The SA-1's 65816 and its bus (cartridge ROM, BW-RAM, I-RAM). It's a {@link CpuBus} like
     * the S-CPU's, but with a different memory map, so it gets its own method.
     */
    CpuBus wrapSa1(CpuBus bus);

    /** The SuperFX's GSU and its bus: cartridge ROM and the GSU's RAM. */
    GsuBus wrapGsu(GsuBus bus);
}
