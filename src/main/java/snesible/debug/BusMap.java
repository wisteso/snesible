package snesible.debug;

/**
 * Decodes one processor's addresses and records each access in the memory it reaches. Each
 * bus wrapper asks its map <i>before</i> passing the access on, because some targets (the
 * PPU's and WRAM's data ports) step their address during the access.
 */
interface BusMap {

    /** Records a read ({@link Access#READ}, {@link Access#EXECUTE} or {@link Access#DMA}). */
    void read(int address, Access kind);

    /** Records a write ({@link Access#WRITE} or {@link Access#DMA}). */
    void write(int address, int value, Access kind);
}
