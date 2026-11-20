package jfc.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jfc.core.system.Snes;
import jfc.ui.audio.AudioOutput;

/**
 * Runs a console in real time: one frame after another, with sound, battery saves and input,
 * sending each picture to a {@link VideoSink}.
 *
 * <p>This is everything a playable emulator needs that isn't the console itself and isn't a
 * particular user interface. A window only has to show frames and supply buttons; it never
 * touches the {@link Snes} directly.
 *
 * <h2>Who decides when a frame runs</h2>
 * The session runs one frame per {@link #runFrame()} call and leaves the timing to its caller:
 * <ul>
 *   <li>{@link #start()} runs frames on a thread of its own, paced by a steady
 *       {@link FrameClock} at the console's rate (the Swing window uses this).</li>
 *   <li>A window that syncs to the display (the OpenGL window) calls {@link #powerOn()},
 *       then {@link #runFrame()} from its own loop whenever the display wants a frame, then
 *       {@link #powerOff()}.</li>
 * </ul>
 * Either way the sound adapts to the frames, not the other way round (see
 * {@link AudioOutput}).
 *
 * <h2>Battery saves</h2>
 * Battery-backed SRAM is written to the save file shortly after the game changes it, and again
 * at {@link #powerOff()}. A real cartridge's battery keeps SRAM alive the whole time, so there's
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
    private final short[] samples = new short[4096];
    private volatile boolean running = true;
    private boolean printStats;

    private AudioOutput audio;
    private long frameNumber;
    /** Run after every frame, or null (see {@link #setAfterFrame}). */
    private Runnable afterFrame;

    // Once-a-second measurements: frames run, and the longest and shortest gap between them.
    private long windowStart;
    private long lastFrameTime;
    private int framesInWindow;
    private long longestGap;
    private long shortestGap = Long.MAX_VALUE;

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

    /** The console's frame rate: about 60.10 for NTSC, 50.01 for PAL. */
    public double framesPerSecond() {
        boolean pal = snes.cartridge().header().region().isPal();
        return pal ? PAL_FRAMES_PER_SECOND : NTSC_FRAMES_PER_SECOND;
    }

    /** Prints timing and audio statistics once a second (the {@code --stats} option). */
    public void setPrintStats(boolean printStats) {
        this.printStats = printStats;
    }

    /**
     * Runs {@code action} after every frame, on the thread that runs frames: the place where a
     * tool window such as the memory debugger updates and redraws.
     *
     * <p><b>Debug support:</b> playing a game doesn't need it.
     */
    public void setAfterFrame(Runnable action) {
        this.afterFrame = action;
    }

    /**
     * Starts emulating on a new thread, paced by a steady clock. Returns immediately; call
     * {@link #stop()} to end it.
     */
    public void start() {
        // Not a daemon thread: when the window closes, the JVM waits for this thread to leave
        // its loop and write the final save before exiting.
        Thread thread = new Thread(() -> {
            powerOn();
            FrameClock clock = new FrameClock(framesPerSecond());
            while (running) {
                runFrame();
                clock.waitForNextFrame();
            }
            powerOff();
        }, "emulation");
        thread.start();
    }

    /** Asks the thread started by {@link #start()} to finish its frame, save and exit. */
    public void stop() {
        running = false;
    }

    /** Opens the sound device, loads the battery save and resets the console. */
    public void powerOn() {
        audio = AudioOutput.open().orElse(null);
        if (audio == null) {
            System.out.println("No sound device available; running without sound.");
        }
        loadSave();
        snes.reset();
        windowStart = System.nanoTime();
        lastFrameTime = windowStart;
    }

    /** Runs one frame: read the buttons, emulate, show the picture, play the sound. */
    public void runFrame() {
        pollInputs();
        snes.joypads().setPressed(0, combinedButtons[0]);
        snes.joypads().setPressed(1, combinedButtons[1]);
        snes.runFrame();
        frameNumber++;
        video.present(new Frame(snes.ppu().frameBuffer(), 256, snes.ppu().visibleLines(), frameNumber));
        if (frameNumber % SAVE_INTERVAL_FRAMES == 0) {
            writeSaveIfChanged();
        }
        int sampleCount = snes.apu().drainSamples(samples);
        if (audio != null) {
            audio.play(samples, sampleCount);
        }
        if (afterFrame != null) {
            afterFrame.run();
        }
        measure();
    }

    /** Closes the sound device and writes the final save. */
    public void powerOff() {
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

    /** Tracks the frame rate and gaps, reporting once a second. */
    private void measure() {
        long now = System.nanoTime();
        long gap = now - lastFrameTime;
        lastFrameTime = now;
        longestGap = Math.max(longestGap, gap);
        shortestGap = Math.min(shortestGap, gap);
        framesInWindow++;
        if (now - windowStart < 1_000_000_000L) {
            return;
        }
        video.showFramesPerSecond(framesInWindow);
        if (printStats) {
            System.out.printf("%d fps, frame gaps %.1f-%.1f ms%s%n", framesInWindow,
                    shortestGap / 1e6, longestGap / 1e6,
                    audio == null ? "" : String.format(", audio buffer %.0f%%, rate %+.2f%%, dropped %d bytes",
                            audio.averageFill() * 100, (audio.ratio() - 1) * 100, audio.droppedBytes()));
        }
        framesInWindow = 0;
        longestGap = 0;
        shortestGap = Long.MAX_VALUE;
        windowStart = now;
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
}
