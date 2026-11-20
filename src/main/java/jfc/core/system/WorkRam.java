package jfc.core.system;

/**
 * The console's 128 KiB of work RAM ("WRAM").
 *
 * <p>The CPU sees WRAM in two places:
 * <ul>
 *   <li>Banks $7E-$7F: all 128 KiB, linearly.</li>
 *   <li>The first 8 KiB ($0000-$1FFF) of banks $00-$3F and $80-$BF. This "low RAM" mirror lets
 *       code reach the stack, direct page and common variables with short 16-bit addresses,
 *       whatever the data bank is.</li>
 * </ul>
 *
 * <p>WRAM can also be reached through a port on the B-bus ($2180-$2183). That looks redundant,
 * but it's what lets DMA copy between WRAM and memory on the A-bus. DMA always moves data
 * between the two buses, so it can use this port to fill WRAM from ROM.
 */
public final class WorkRam {

    public static final int SIZE = 128 * 1024;

    private final byte[] data = new byte[SIZE];
    /** The 17-bit address used by the $2180 port, set through $2181-$2183. */
    private int portAddress;

    /** Reads at an offset into the 128 KiB (0-$1FFFF). */
    public int read(int offset) {
        return data[offset] & 0xFF;
    }

    public void write(int offset, int value) {
        data[offset] = (byte) value;
    }

    /** $2180 WMDATA read: the byte at the port address, then advance it. */
    public int readPort() {
        int value = read(portAddress);
        portAddress = (portAddress + 1) & (SIZE - 1);
        return value;
    }

    /** $2180 WMDATA write. */
    public void writePort(int value) {
        write(portAddress, value);
        portAddress = (portAddress + 1) & (SIZE - 1);
    }

    /**
     * The 17-bit address the next $2180 read or write will use (0-$1FFFF). Reading it has no
     * side effects. The bus only sees "an access to $2180", so this is how a memory viewer
     * learns which work-RAM byte that access really touches.
     *
     * <p><b>Debug support:</b> not needed to emulate the console; it exists for the visual
     * memory debugger and other tools.
     */
    public int portAddress() {
        return portAddress;
    }

    /** $2181-$2183 WMADDL/M/H: set one byte of the 17-bit port address. */
    public void writePortAddress(int register, int value) {
        switch (register) {
            case 0 -> portAddress = (portAddress & 0x1FF00) | value;
            case 1 -> portAddress = (portAddress & 0x100FF) | (value << 8);
            case 2 -> portAddress = (portAddress & 0x0FFFF) | ((value & 1) << 16);
            default -> throw new IllegalArgumentException("WRAM port register " + register);
        }
    }
}
