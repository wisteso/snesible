package jfc.frontend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jfc.system.Snes;

/**
 * Runs a console in real time: one frame after another on its own thread, with sound, battery
 * saves and input, sending each picture to a {@link VideoSink}.
 *
 * <p>This is everything a playable emulator needs that isn't the console itself and isn't a
 * particular user interface. A window (Swing or OpenGL) only has to show frames and supply
 * buttons; it never touches the {@link Snes} directly.
 *
 * <h2>Pacing</h2>
 * Playing each frame's audio blocks while the sound device's short buffer is full, and the device
 * consumes exactly 32,000 samples a second, so audio is what keeps the emulator at the console's
 * real speed (about 60.1 frames per second on NTSC). Without a sound device the loop sleeps until
 * each frame is due instead.
 *
 * <h2>Battery saves</h2>
 * Battery-backed SRAM is written to the save file shortly after the game changes it, and again
 * when the session stops. A real cartridge's battery keeps SRAM alive the whole time, so there's
 * no "save" step to imitate; we just keep the file up to date.
 */
public final class EmulationSession {

    private static final double NTSC_FRAMES_PER_SECOND = 21_477_272.0 / (1364 * 262);
    private static final double PAL_FRAMES_PER_SECOND = 21_281_370.0 / (1364 * 312);

    /** Save SRAM at most this often while the game keeps changing it (about once a second). */
    private static final int SAVE_INTERVAL_FRAMES = 60;

    private static final int PLAYERS = 2;

    private final Snes snes;
    private final Path saveFile;
    private final VideoSink video;
    private final List<InputSource> inputs;
    private final int[] sourceButtons = new int[PLAYERS];
    private final int[] combinedButtons = new int[PLAYERS];
    private volatile boolean running = true;
    private Thread thread;

    /**
     * @param saveFile where battery-backed SRAM is kept, or null for no saving
     * @param inputs   polled every frame; their buttons are combined
     */
    public EmulationSession(Snes snes, Path saveFile, VideoSink video, List<InputSource> inputs) {
        this.snes = snes;
        this.saveFile = saveFile;
        this.video = video;
        this.inputs = List.copyOf(inputs);
    }

    /** Starts emulating on a new thread. Returns immediately. */
    public void start() {
        // Not a daemon thread: when the window closes, the JVM waits for this thread to leave
        // its loop and write the final save before exiting.
        thread = new Thread(this::run, "emulation");
        thread.start();
    }

    /** Asks the emulation thread to finish its frame, write the save and exit. Returns at once. */
    public void stop() {
        running = false;
    }

    /**
     * Runs frames at the console's real rate. Sleeping until each frame's deadline (instead of a
     * fixed sleep) keeps the average rate exact even though individual frames vary in cost.
     */
    private void run() {
        boolean pal = snes.cartridge().header().region().isPal();
        long framePeriod = (long) (1_000_000_000L / (pal ? PAL_FRAMES_PER_SECOND : NTSC_FRAMES_PER_SECOND));
        long nextFrameTime = System.nanoTime();
        long fpsWindowStart = System.nanoTime();
        int framesInWindow = 0;

        AudioOutput audio = AudioOutput.open().orElse(null);
        if (audio == null) {
            System.out.println("No sound device available; running without sound.");
        }
        short[] samples = new short[4096];

        loadSave();
        snes.reset();
        long frameNumber = 0;
        while (running) {
            pollInputs();
            snes.joypads().setPressed(0, combinedButtons[0]);
            snes.joypads().setPressed(1, combinedButtons[1]);
            snes.runFrame();
            frameNumber++;
            video.present(new Frame(snes.ppu().frameBuffer(), 256, snes.ppu().visibleLines(), frameNumber));
            framesInWindow++;
            if (frameNumber % SAVE_INTERVAL_FRAMES == 0) {
                writeSaveIfChanged();
            }

            int sampleCount = snes.apu().drainSamples(samples);
            long now;
            if (audio != null) {
                audio.play(samples, sampleCount); // blocks while the device's buffer is full
                now = System.nanoTime();
            } else {
                nextFrameTime += framePeriod;
                now = System.nanoTime();
                if (now < nextFrameTime) {
                    sleepNanos(nextFrameTime - now);
                } else if (now - nextFrameTime > 5 * framePeriod) {
                    nextFrameTime = now; // fell far behind (e.g. a pause): don't try to catch up
                }
            }

            if (now - fpsWindowStart >= 1_000_000_000L) {
                video.showFramesPerSecond(framesInWindow);
                framesInWindow = 0;
                fpsWindowStart = now;
            }
        }
        if (audio != null) {
            audio.close();
        }
        writeSaveIfChanged();
    }

    /** Asks every input source for its buttons and ORs them together per player. */
    private void pollInputs() {
        Arrays.fill(combinedButtons, 0);
        for (InputSource input : inputs) {
            Arrays.fill(sourceButtons, 0);
            input.poll(sourceButtons);
            for (int player = 0; player < PLAYERS; player++) {
                combinedButtons[player] |= sourceButtons[player];
            }
        }
    }

    private void loadSave() {
        if (saveFile == null || !snes.cartridge().header().hasBattery()) {
            return;
        }
        try {
            snes.cartridge().loadSramFrom(saveFile);
        } catch (IOException e) {
            System.err.println("Couldn't read save file " + saveFile + ": " + e.getMessage());
        }
    }

    private void writeSaveIfChanged() {
        if (saveFile == null || !snes.cartridge().hasUnsavedSram()) {
            return;
        }
        try {
            snes.cartridge().saveSramTo(saveFile);
        } catch (IOException e) {
            System.err.println("Couldn't write save file " + saveFile + ": " + e.getMessage());
        }
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
