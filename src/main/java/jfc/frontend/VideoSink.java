package jfc.frontend;

/**
 * Something that shows the emulator's pictures: a Swing window, an OpenGL window, a PNG writer.
 *
 * <p>This and {@link InputSource} are the whole boundary between the emulation and a user
 * interface. {@link EmulationSession} runs the console and calls these from its own thread;
 * the implementation decides how and when the picture reaches the screen.
 */
public interface VideoSink {

    /**
     * Receives a finished frame, on the emulation thread. The frame's pixel array is only valid
     * until this returns, so copy or convert what you need before returning. Don't block for
     * long: the emulator waits.
     */
    void present(Frame frame);

    /** Reports the measured speed about once a second (for a title bar, say). */
    default void showFramesPerSecond(int fps) {
    }
}
