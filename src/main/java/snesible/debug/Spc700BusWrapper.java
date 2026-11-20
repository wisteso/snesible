package snesible.debug;

import snesible.core.apu.Spc700;
import snesible.core.apu.Spc700Bus;

/**
 * Sits between the SPC700 (the sound CPU) and its bus, records every access to sound RAM, and
 * passes it on unchanged.
 *
 * <p>Fetches are told apart the same way as for the 65816 (see {@link CpuBusWrapper}): a read
 * at the SPC700's current {@code pc} is an opcode or operand fetch. The SPC700 also makes
 * dummy reads during some internal cycles (often at {@code pc} too); those count as fetches,
 * which only slightly overstates execute activity on bytes that are being executed anyway.
 *
 * <p>The SPC700 reads its reset vector in its own constructor, before
 * {@link MemoryObserver#attach} gives this wrapper the CPU and its map; that isn't recorded.
 */
final class Spc700BusWrapper implements Spc700Bus {

    private final Spc700Bus bus;
    /** Null until {@link MemoryObserver#attach}. */
    private Spc700 cpu;
    private BusMap map;

    Spc700BusWrapper(Spc700Bus bus) {
        this.bus = bus;
    }

    void attach(Spc700 cpu, BusMap map) {
        this.cpu = cpu;
        this.map = map;
    }

    @Override
    public int read(int address) {
        if (map != null) {
            map.read(address, address == cpu.pc ? Access.EXECUTE : Access.READ);
        }
        return bus.read(address);
    }

    @Override
    public void write(int address, int value) {
        if (map != null) {
            map.write(address, value, Access.WRITE);
        }
        bus.write(address, value);
    }

    @Override
    public void idle() {
        bus.idle();
    }
}
