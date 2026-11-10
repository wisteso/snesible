package jfc.apu;

/**
 * The four communication ports between the main CPU and the audio unit, at $2140-$2143 (mirrored
 * through $217F).
 *
 * <p>The APU is a separate computer: a Sony SPC700 CPU with 64 KiB of its own RAM and a DSP that
 * mixes eight sampled voices. The main CPU can't touch the APU's memory. The only link is four
 * byte-wide mailboxes, and each port is really two latches: what the CPU writes is what the
 * SPC700 reads, and what the SPC700 writes is what the CPU reads. Games load their sound driver
 * and music through these four bytes.
 */
public interface ApuPorts {

    /** The CPU reads port 0-3. */
    int read(int port);

    /** The CPU writes port 0-3. */
    void write(int port, int value);
}
