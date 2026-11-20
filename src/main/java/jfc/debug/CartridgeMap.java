package jfc.debug;

/**
 * Turns console-side cartridge addresses (what the S-CPU or DMA puts on the bus) into ROM and
 * cartridge-RAM offsets, and records the access there. One implementation per kind of board,
 * because each maps the cartridge space differently.
 */
interface CartridgeMap {

    /** Records an access to a cartridge-space address. Unmapped addresses record nothing. */
    void record(int address, Access kind);

    /**
     * Records a write. Boards with bank registers (SA-1) also follow the value, so later
     * addresses decode the way the board now maps them.
     */
    default void recordWrite(int address, int value, Access kind) {
        record(address, kind);
    }
}
