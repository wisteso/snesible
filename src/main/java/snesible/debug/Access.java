package snesible.debug;

/**
 * The kinds of memory access the heat map tells apart. Each byte of each {@link MemorySpace}
 * remembers, per kind, the frame it was last accessed that way.
 *
 * <p>The first three are recorded exactly, one bus access at a time. The last two are not:
 * {@link #CHIP} is worked out once a frame from the PPU's and DSP's registers, because those
 * chips index their memories directly, without any bus call that could be watched.
 */
public enum Access {

    /** A data read by a processor (S-CPU, SA-1, SuperFX GSU or SPC700). */
    READ("read"),

    /** A write by a processor. */
    WRITE("write"),

    /**
     * An instruction fetch by a processor: the opcode and operand bytes of executed code. (The
     * GSU's fetches show up as {@link #READ}: they mostly come from its instruction cache.)
     */
    EXECUTE("execute"),

    /** A DMA or HDMA transfer, in either direction. */
    DMA("DMA"),

    /**
     * Read (or written) by the PPU or DSP on their own: tilemaps, tiles, sprites and palettes
     * being drawn; samples being played; the echo buffer. <i>Derived</i> from register values
     * once a frame, not recorded access by access (see {@link VideoActivity} and
     * {@link SoundActivity}).
     */
    CHIP("PPU/DSP (derived)");

    private final String label;

    Access(String label) {
        this.label = label;
    }

    /** A short name for legends. */
    public String label() {
        return label;
    }
}
