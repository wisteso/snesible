package jfc.debug;

import jfc.core.dma.DmaController;

/**
 * Sits between the DMA controller and the system bus, records every transfer as
 * {@link Access#DMA}, and passes it on unchanged. Each DMA or HDMA byte is one access on the
 * A-bus (work RAM, cartridge) and one on the B-bus ($21xx: PPU, APU ports, the work-RAM port),
 * both decoded by the same {@link SystemBusMap} as the S-CPU's accesses. HDMA's table reads
 * are A-bus reads too.
 */
final class DmaBusWrapper implements DmaController.Bus {

    private final DmaController.Bus bus;
    /** Null until {@link MemoryObserver#attach}. */
    private SystemBusMap map;

    DmaBusWrapper(DmaController.Bus bus) {
        this.bus = bus;
    }

    void attach(SystemBusMap map) {
        this.map = map;
    }

    @Override
    public int readA(int address) {
        if (map != null) {
            map.read(address, Access.DMA);
        }
        return bus.readA(address);
    }

    @Override
    public void writeA(int address, int value) {
        if (map != null) {
            map.write(address, value, Access.DMA);
        }
        bus.writeA(address, value);
    }

    @Override
    public int readB(int register) {
        if (map != null) {
            map.readB(register, Access.DMA);
        }
        return bus.readB(register);
    }

    @Override
    public void writeB(int register, int value) {
        if (map != null) {
            map.writeB(register, value, Access.DMA);
        }
        bus.writeB(register, value);
    }
}
