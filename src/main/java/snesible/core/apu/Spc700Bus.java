package snesible.core.apu;

/**
 * The SPC700's view of its 64 KiB address space, one call per CPU cycle, the same design as
 * {@link snesible.core.cpu.CpuBus}: every cycle is a read, a write or an idle ("wait") cycle.
 *
 * <p>Unlike the 65816, the SPC700 performs real (but ignored) reads during many internal
 * cycles, usually of the byte after the opcode. Hardware registers in the address space can
 * notice them, so the core performs them as genuine reads.
 */
public interface Spc700Bus {

    int read(int address);

    void write(int address, int value);

    void idle();
}
