package jfc.core.cpu;

import java.util.ArrayList;
import java.util.List;

/**
 * A test bus: a flat 16 MiB of RAM that records every cycle the CPU performs.
 *
 * <p>The SingleStepTests suite was generated on such a machine, with no SNES memory map in the
 * way, so this lets us compare the CPU's bus activity against it cycle by cycle.
 */
final class RecordingBus implements CpuBus {

    enum Kind { READ, WRITE, IDLE }

    /** One bus cycle. {@code address} and {@code value} are meaningless for IDLE cycles. */
    record Cycle(Kind kind, int address, int value) {
        @Override
        public String toString() {
            return switch (kind) {
                case READ -> String.format("R %06X", address);
                case WRITE -> String.format("W %06X=%02X", address, value);
                case IDLE -> "idle";
            };
        }
    }

    private final byte[] memory = new byte[1 << 24];
    /** Addresses set by the current test, so they can be cleared cheaply afterwards. */
    private final List<Integer> touched = new ArrayList<>();
    private final List<Cycle> cycles = new ArrayList<>();

    void poke(int address, int value) {
        memory[address] = (byte) value;
        touched.add(address);
    }

    int peek(int address) {
        return memory[address] & 0xFF;
    }

    List<Cycle> cycles() {
        return cycles;
    }

    /** Forgets the previous test: clears the memory it touched and the recorded cycles. */
    void reset() {
        for (int address : touched) {
            memory[address] = 0;
        }
        touched.clear();
        cycles.clear();
    }

    @Override
    public int read(int address) {
        cycles.add(new Cycle(Kind.READ, address, 0));
        return memory[address] & 0xFF;
    }

    @Override
    public void write(int address, int value) {
        cycles.add(new Cycle(Kind.WRITE, address, value & 0xFF));
        memory[address] = (byte) value;
        touched.add(address);
    }

    @Override
    public void idle() {
        cycles.add(new Cycle(Kind.IDLE, 0, 0));
    }
}
