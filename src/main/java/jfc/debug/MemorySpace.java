package jfc.debug;

import java.util.function.IntUnaryOperator;

/**
 * One of the console's memories as the debugger shows it: work RAM, ROM, VRAM and so on, each
 * numbered from offset 0 regardless of where (or whether) it appears on a bus.
 *
 * <p>For every byte it keeps, per {@link Access} kind, the number of the frame in which the
 * byte was last accessed that way (0 = never). The heat map turns "frames since" into a
 * fading colour only for the cells it draws, so nothing has to be decayed in bulk each frame.
 * The arrays are allocated the first time a kind is recorded, so ROM (which is never written)
 * costs no write array.
 *
 * <p>Memory use: 4 bytes per byte per kind seen. A 4 MiB ROM read, executed and DMA'd from
 * takes 48 MiB, which is acceptable for a debugging session. (16-bit frame stamps would halve
 * it, wrapping after 18 minutes; not needed so far.)
 *
 * <p>Written only on the emulation thread. The UI reads it on the same thread, between frames.
 */
public final class MemorySpace {

    private final String name;
    private final int size;
    private final IntUnaryOperator peek;
    private final boolean editable;
    private final int[][] lastFrame = new int[Access.values().length][];

    /**
     * @param name     what the UI calls it, e.g. "WRAM"
     * @param size     in bytes
     * @param peek     reads a byte (0-255) at an offset, without side effects
     * @param editable whether Milestone 9b's editor may change its values (only memories that
     *                 hold the game's logical state: work RAM and cartridge RAM)
     */
    MemorySpace(String name, int size, IntUnaryOperator peek, boolean editable) {
        this.name = name;
        this.size = size;
        this.peek = peek;
        this.editable = editable;
    }

    public String name() {
        return name;
    }

    public int size() {
        return size;
    }

    /** The byte at {@code offset} (0-255), read without side effects. */
    public int peek(int offset) {
        return peek.applyAsInt(offset);
    }

    /** Whether values may be edited (work RAM and cartridge RAM only; see Milestone 9b). */
    public boolean editable() {
        return editable;
    }

    /** Notes an access of {@code kind} to the byte at {@code offset} in frame {@code frame}. */
    void record(int offset, Access kind, int frame) {
        int[] frames = lastFrame[kind.ordinal()];
        if (frames == null) {
            frames = new int[size];
            lastFrame[kind.ordinal()] = frames;
        }
        frames[offset] = frame;
    }

    /** Notes the same access for {@code count} bytes from {@code offset} (clipped to the space). */
    void recordRange(int offset, int count, Access kind, int frame) {
        int end = Math.min(size, offset + count);
        for (int i = Math.max(0, offset); i < end; i++) {
            record(i, kind, frame);
        }
    }

    /**
     * The frame stamps for one kind: element {@code i} is the frame byte {@code i} was last
     * accessed that way, 0 if never. Null if this kind never happened here. Read-only for the
     * caller; returned directly so the heat map can scan millions of bytes quickly.
     */
    public int[] lastFrames(Access kind) {
        return lastFrame[kind.ordinal()];
    }

    /** The frame the byte was last accessed as {@code kind}, or 0 if never. */
    public int lastFrame(int offset, Access kind) {
        int[] frames = lastFrame[kind.ordinal()];
        return frames == null ? 0 : frames[offset];
    }

    @Override
    public String toString() {
        return name + " (" + size + " bytes)";
    }
}
