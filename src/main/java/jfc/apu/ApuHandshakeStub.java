package jfc.apu;

/**
 * A stand-in for the APU, until the real SPC700 and DSP arrive in Milestone 7. It makes no sound.
 * It only answers the main CPU well enough that games don't hang waiting for the sound chip.
 *
 * <h2>What games expect</h2>
 * At power-on the SPC700 runs a 64-byte boot ROM (the "IPL"), which:
 * <ol>
 *   <li>writes $AA to port 0 and $BB to port 1 to announce it's ready;</li>
 *   <li>waits for the CPU to write $CC to port 0, then echoes it back;</li>
 *   <li>receives data: the CPU puts a byte in port 1 and a running index in port 0, and the IPL
 *       echoes the index on port 0 to acknowledge each byte;</li>
 *   <li>finally jumps to the uploaded code (the game's sound driver).</li>
 * </ol>
 * Games often return to the IPL later (to upload new music) and wait for $AA/$BB again.
 *
 * <h2>How the stub fakes it</h2>
 * Two simple tricks satisfy nearly all of these waits:
 * <ul>
 *   <li><b>Echo:</b> each port reads back what the CPU last wrote to it. That acknowledges
 *       every byte of an upload.</li>
 *   <li><b>Alternation:</b> ports 0 and 1 swap between the echo and the IPL's "ready" signature
 *       ($AA/$BB) on each read of port 0. Whichever answer a wait loop is looking for turns up
 *       within two reads. Port 1 follows port 0's phase, so a 16-bit read of both ports sees a
 *       consistent pair.</li>
 * </ul>
 * This is a hack, and a game whose sound driver uses its own handshake may still loop forever.
 */
public final class ApuHandshakeStub implements ApuPorts {

    private static final int[] IPL_READY = {0xAA, 0xBB, 0x00, 0x00};

    private final int[] lastWritten = IPL_READY.clone();
    private boolean showReadySignature = true;

    @Override
    public int read(int port) {
        if (port == 0) {
            showReadySignature = !showReadySignature;
        }
        if (port <= 1 && showReadySignature) {
            return IPL_READY[port];
        }
        return lastWritten[port];
    }

    @Override
    public void write(int port, int value) {
        lastWritten[port] = value;
    }
}
