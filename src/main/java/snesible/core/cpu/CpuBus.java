package snesible.core.cpu;

/**
 * The CPU's view of the outside world: one call per CPU cycle.
 *
 * <p>Every cycle of the 65816 either reads a byte, writes a byte, or is an "internal operation"
 * (IO) cycle where the CPU is busy computing and the bus is idle. Modeling each one as a call
 * keeps the CPU free of timing tables. The SNES bus implementation decides how long each access
 * takes (6, 8 or 12 master cycles depending on the address) and uses those moments to advance
 * the rest of the system.
 *
 * <p>Addresses are 24-bit: {@code (bank << 16) | offset}.
 */
public interface CpuBus {

    /** Performs a read cycle and returns the byte (0-255) on the data bus. */
    int read(int address);

    /** Performs a write cycle. Only the low 8 bits of {@code value} are used. */
    void write(int address, int value);

    /** Performs an internal-operation cycle, during which the CPU doesn't use the bus. */
    void idle();
}
