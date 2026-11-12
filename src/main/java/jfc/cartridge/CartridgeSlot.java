package jfc.cartridge;

/**
 * Whatever answers the CPU in cartridge space: the part of the 24-bit address space that isn't
 * work RAM or a console register.
 *
 * <p>On a plain board that's just the {@link Cartridge}'s ROM and SRAM. Boards with a
 * coprocessor (SuperFX, SA-1) put the chip in charge of the whole space: it adds its own
 * registers, owns the RAM, and sometimes takes the ROM away from the CPU while it works. Those
 * boards implement this interface themselves and the bus talks to them instead.
 */
public interface CartridgeSlot {

    /**
     * Reads one byte at a 24-bit CPU address.
     *
     * @return the byte as 0-255, or {@link Cartridge#OPEN_BUS} if nothing on the board drives the
     *         data bus at that address
     */
    int read(int address);

    /** Writes one byte. Addresses nothing on the board answers to ignore the write. */
    void write(int address, int value);
}
