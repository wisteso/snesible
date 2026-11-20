package jfc.debug;

import jfc.core.coprocessor.superfx.GsuBus;

/**
 * Sits between the SuperFX's GSU and its bus (cartridge ROM and the GSU's RAM), records every
 * access, and passes it on unchanged. The same bus serves the GSU's pixel-plot unit, so
 * plotted pixels show up as RAM writes.
 *
 * <p>Fetches aren't told apart here: most GSU code runs from its 512-byte instruction cache
 * and never reaches the bus, so code bytes show up only as reads when the cache loads them.
 */
final class GsuBusWrapper implements GsuBus {

    private final GsuBus bus;
    /** Null until {@link MemoryObserver#attach}. */
    private SuperFxMap map;

    GsuBusWrapper(GsuBus bus) {
        this.bus = bus;
    }

    void attach(SuperFxMap map) {
        this.map = map;
    }

    @Override
    public int read(int address) {
        if (map != null) {
            map.recordGsu(address, Access.READ);
        }
        return bus.read(address);
    }

    @Override
    public void write(int address, int value) {
        if (map != null) {
            map.recordGsu(address, Access.WRITE);
        }
        bus.write(address, value);
    }
}
