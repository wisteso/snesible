package jfc.core.observe;

import jfc.core.apu.Spc700Bus;
import jfc.core.coprocessor.superfx.GsuBus;
import jfc.core.cpu.CpuBus;
import jfc.core.dma.DmaController;

/**
 * The one place where the core lets an outside {@link Observer} watch memory accesses.
 *
 * <p><b>Debug support:</b> this package isn't needed to emulate the console. It exists so the
 * visual memory debugger ({@code jfc.debug}) and other tools can watch it.
 *
 * <p>Every processor in the console is given its bus through one of these methods, at the
 * point where the bus is connected:
 * <pre>
 *   Snes      new Cpu(wrapCpu(bus))                   the S-CPU
 *   Snes      dma.setBus(wrapDma(bus))                DMA and HDMA
 *   Apu       new Spc700(wrapSpc700(new SpcSide()))   the sound CPU
 *   Sa1       new Cpu(wrapSa1(sa1Bus))                the SA-1 (on SA-1 cartridges)
 *   SuperFx   new Gsu(wrapGsu(new GsuSide()), ...)    the GSU (on SuperFX cartridges)
 * </pre>
 * The call sites import these methods statically, so they read as above.
 *
 * <p><b>When no observer is installed</b> (a normal run), each method returns the bus it was
 * given. The processor then talks to its real bus directly, exactly as if this class didn't
 * exist: the only cost is one null check per processor, once, while the console is built.
 *
 * <p><b>When an observer is installed</b>, it must be installed <i>before</i> the
 * {@link jfc.core.system.Snes} is constructed, because the wrapping happens in the
 * constructors. Buses already handed out aren't affected by a later {@link #install}.
 *
 * <p>Why a static, global hook: the call sites are deep inside the console (the APU's and the
 * coprocessors' constructors), and threading an observer parameter through every constructor
 * on the way would touch far more core code than these five lines. A process runs one
 * emulated console at a time, so one global observer is enough. Tests that install one must
 * remove it afterwards with {@code install(null)}.
 *
 * <p>Why one method per processor, not one overload per interface: the S-CPU and the SA-1 both
 * use {@link CpuBus} but see different memory maps, and {@code SnesBus} is both a
 * {@code CpuBus} and a {@link DmaController.Bus}. The method name says which processor the
 * bus belongs to, so no casts or extra arguments are needed.
 */
public final class Observable {

    /** The installed observer, or null when nothing is watching (the normal case). */
    private static Observer observer;

    private Observable() {
    }

    /**
     * Installs the observer that later-built consoles report to, or with null removes it.
     * Call it before constructing the {@link jfc.core.system.Snes}.
     */
    public static void install(Observer newObserver) {
        observer = newObserver;
    }

    /** See {@link Observer#wrapCpu}. Returns {@code bus} itself when nothing is installed. */
    public static CpuBus wrapCpu(CpuBus bus) {
        return observer == null ? bus : observer.wrapCpu(bus);
    }

    /** See {@link Observer#wrapDma}. Returns {@code bus} itself when nothing is installed. */
    public static DmaController.Bus wrapDma(DmaController.Bus bus) {
        return observer == null ? bus : observer.wrapDma(bus);
    }

    /** See {@link Observer#wrapSpc700}. Returns {@code bus} itself when nothing is installed. */
    public static Spc700Bus wrapSpc700(Spc700Bus bus) {
        return observer == null ? bus : observer.wrapSpc700(bus);
    }

    /** See {@link Observer#wrapSa1}. Returns {@code bus} itself when nothing is installed. */
    public static CpuBus wrapSa1(CpuBus bus) {
        return observer == null ? bus : observer.wrapSa1(bus);
    }

    /** See {@link Observer#wrapGsu}. Returns {@code bus} itself when nothing is installed. */
    public static GsuBus wrapGsu(GsuBus bus) {
        return observer == null ? bus : observer.wrapGsu(bus);
    }
}
