package snesible.debug;

import snesible.core.apu.Spc700Bus;
import snesible.core.coprocessor.Coprocessor;
import snesible.core.coprocessor.sa1.Sa1;
import snesible.core.coprocessor.superfx.GsuBus;
import snesible.core.coprocessor.superfx.SuperFx;
import snesible.core.cpu.CpuBus;
import snesible.core.dma.DmaController;
import snesible.core.observe.Observable;
import snesible.core.observe.Observer;
import snesible.core.system.Snes;

/**
 * The visual memory debugger's view into the console: wraps each processor's bus so every
 * memory access gets recorded in an {@link AccessRecorder}.
 *
 * <p>Use it in four steps (as {@link DebugMain} does):
 * <ol>
 *   <li>{@link Observable#install install} it, so the console's constructors hand it their
 *       buses ({@code wrapCpu}, {@code wrapDma}, ...);</li>
 *   <li>build the {@link Snes};</li>
 *   <li>call {@link #attach} with it. That gives the wrappers what didn't exist yet when they
 *       were created: the processors (for their program counters, to tell instruction
 *       fetches from data reads) and the memories to record into;</li>
 *   <li>call {@link #endFrame} after every emulated frame.</li>
 * </ol>
 */
public final class MemoryObserver implements Observer {

    // Set while the Snes constructor runs; the SA-1 and GSU ones only on those cartridges.
    private CpuBusWrapper cpuBus;
    private DmaBusWrapper dmaBus;
    private Spc700BusWrapper spc700Bus;
    private CpuBusWrapper sa1Bus;
    private GsuBusWrapper gsuBus;

    // Set by attach().
    private AccessRecorder recorder;
    private VideoActivity videoActivity;
    private SoundActivity soundActivity;

    @Override
    public CpuBus wrapCpu(CpuBus bus) {
        cpuBus = new CpuBusWrapper(bus);
        return cpuBus;
    }

    @Override
    public DmaController.Bus wrapDma(DmaController.Bus bus) {
        dmaBus = new DmaBusWrapper(bus);
        return dmaBus;
    }

    @Override
    public Spc700Bus wrapSpc700(Spc700Bus bus) {
        spc700Bus = new Spc700BusWrapper(bus);
        return spc700Bus;
    }

    @Override
    public CpuBus wrapSa1(CpuBus bus) {
        sa1Bus = new CpuBusWrapper(bus);
        return sa1Bus;
    }

    @Override
    public GsuBus wrapGsu(GsuBus bus) {
        gsuBus = new GsuBusWrapper(bus);
        return gsuBus;
    }

    /**
     * Links each bus wrapper to its processor and to the memories, right after {@code snes}
     * was built with this observer installed. Until this runs, nothing is recorded.
     */
    public void attach(Snes snes) {
        if (cpuBus == null || dmaBus == null || spc700Bus == null) {
            throw new IllegalStateException("install this observer before building the Snes");
        }
        recorder = new AccessRecorder(snes);
        VideoRegisters video = new VideoRegisters();

        Coprocessor coprocessor = snes.coprocessor();
        CartridgeMap cartridge;
        if (coprocessor instanceof Sa1 sa1) {
            Sa1Map map = new Sa1Map(recorder);
            sa1Bus.attach(sa1.cpu().registers(), map.sa1Side());
            cartridge = map;
        } else if (coprocessor instanceof SuperFx) {
            SuperFxMap map = new SuperFxMap(recorder);
            gsuBus.attach(map);
            cartridge = map;
        } else {
            cartridge = new PlainCartridgeMap(snes.cartridge(), recorder);
        }
        SystemBusMap systemBus = new SystemBusMap(recorder, cartridge, snes.ppu(), snes.wram(), video);
        cpuBus.attach(snes.cpu().registers(), systemBus);
        dmaBus.attach(systemBus);
        spc700Bus.attach(snes.apu().cpu(), new SpcMap(recorder));

        videoActivity = new VideoActivity(recorder, video, snes.ppu());
        soundActivity = new SoundActivity(recorder, snes.apu());
    }

    /**
     * Ends the frame just emulated: marks what the PPU and DSP read for it (worked out from
     * their registers), then moves the recorder on to the next frame. Call it on the
     * emulation thread, after each {@code runFrame()}.
     */
    public void endFrame() {
        videoActivity.markFrame();
        soundActivity.markFrame();
        recorder.nextFrame();
    }

    /** The recorded accesses, for the views. Null before {@link #attach}. */
    public AccessRecorder recorder() {
        return recorder;
    }
}
