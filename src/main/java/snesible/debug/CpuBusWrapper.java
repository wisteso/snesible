package snesible.debug;

import snesible.core.cpu.CpuBus;
import snesible.core.cpu.Registers;

/**
 * Sits between a 65816 (the S-CPU or the SA-1) and its real bus, records every access through
 * its {@link BusMap}, and passes it on unchanged.
 *
 * <p><b>Telling instruction fetches from data reads.</b> The bus interface only carries an
 * address, but the CPU fetches every opcode and operand byte at {@code PBR:PC} and only then
 * increments PC ({@code Cpu.fetchByte}). So a read at exactly the current {@code PBR:PC} is a
 * fetch. Data reads go through the data bank or direct page instead, and only coincide with
 * {@code PBR:PC} if code reads its own next byte, which is rare and harmless to miscount.
 *
 * <p>The wrapper is created before its {@code Cpu} exists ({@code new Cpu(wrapCpu(bus))}), so
 * it gets the registers and its map later, from {@link MemoryObserver#attach}. Until then it
 * records nothing (the console isn't running yet anyway).
 */
final class CpuBusWrapper implements CpuBus {

    private final CpuBus bus;
    /** Null until {@link MemoryObserver#attach}. */
    private Registers registers;
    private BusMap map;

    CpuBusWrapper(CpuBus bus) {
        this.bus = bus;
    }

    void attach(Registers registers, BusMap map) {
        this.registers = registers;
        this.map = map;
    }

    @Override
    public int read(int address) {
        if (map != null) {
            boolean fetch = address == ((registers.pbr << 16) | registers.pc);
            map.read(address, fetch ? Access.EXECUTE : Access.READ);
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
        // An internal cycle: time passes, but no memory is touched, so nothing to record.
        bus.idle();
    }
}
