package jfc.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import jfc.cpu.io.JoypadPorts;
import jfc.system.Snes;
import jfc.ui.input.Gamepads;

/**
 * A Swing window that runs the emulator and shows its picture.
 *
 * <p>Two threads are involved:
 * <ul>
 *   <li>The <b>emulation thread</b> runs one frame at a time, copies the finished picture into
 *       an image, and plays the frame's audio. Playing blocks until the sound device has room,
 *       which keeps the emulator at the console's real speed (about 60 frames per second on
 *       NTSC). Without a sound device it sleeps until each frame is due instead.</li>
 *   <li>Swing's <b>event thread</b> paints that image and turns key presses into controller
 *       buttons.</li>
 * </ul>
 * They share only the image (guarded by a lock) and the button state (an atomic integer).
 *
 * <p>Keys: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
 * Shift or Backspace = Select, Escape = quit. Gamepads are read by the emulation thread; the
 * first one plays together with the keyboard as controller 1, a second one as controller 2.
 *
 * <p>Battery-backed SRAM is written to the save file shortly after the game changes it, and
 * again when the window closes. A real cartridge's battery keeps SRAM alive the whole time,
 * so there's no "save" step to imitate; we just keep the file up to date.
 */
public final class EmulatorWindow {

    /** Printed when the window opens, so the key mapping is easy to find. */
    public static final String CONTROLS_HELP = """
            Controls:
              D-pad         Arrow keys
              A / B         X / Z
              X / Y         S / A
              L / R         Q / W
              Start         Enter
              Select        Shift or Backspace
              Quit          Escape""";

    private static final int SCALE = 3;
    private static final double NTSC_FRAMES_PER_SECOND = 21_477_272.0 / (1364 * 262);
    private static final double PAL_FRAMES_PER_SECOND = 21_281_370.0 / (1364 * 312);

    private static final Map<Integer, Integer> KEY_BINDINGS = Map.ofEntries(
            Map.entry(KeyEvent.VK_UP, JoypadPorts.BUTTON_UP),
            Map.entry(KeyEvent.VK_DOWN, JoypadPorts.BUTTON_DOWN),
            Map.entry(KeyEvent.VK_LEFT, JoypadPorts.BUTTON_LEFT),
            Map.entry(KeyEvent.VK_RIGHT, JoypadPorts.BUTTON_RIGHT),
            Map.entry(KeyEvent.VK_X, JoypadPorts.BUTTON_A),
            Map.entry(KeyEvent.VK_Z, JoypadPorts.BUTTON_B),
            Map.entry(KeyEvent.VK_S, JoypadPorts.BUTTON_X),
            Map.entry(KeyEvent.VK_A, JoypadPorts.BUTTON_Y),
            Map.entry(KeyEvent.VK_Q, JoypadPorts.BUTTON_L),
            Map.entry(KeyEvent.VK_W, JoypadPorts.BUTTON_R),
            Map.entry(KeyEvent.VK_ENTER, JoypadPorts.BUTTON_START),
            Map.entry(KeyEvent.VK_SHIFT, JoypadPorts.BUTTON_SELECT),
            Map.entry(KeyEvent.VK_BACK_SPACE, JoypadPorts.BUTTON_SELECT));

    /** Save SRAM at most this often while the game keeps changing it (about once a second). */
    private static final int SAVE_INTERVAL_FRAMES = 60;

    private final Snes snes;
    private final String title;
    private final Path saveFile;
    private final Gamepads gamepads;
    private final int[] gamepadButtons = new int[2];
    private final BufferedImage image = new BufferedImage(256, 239, BufferedImage.TYPE_INT_RGB);
    private final int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    private final Object imageLock = new Object();
    private volatile int visibleLines = 224;
    private final AtomicInteger heldButtons = new AtomicInteger();
    private volatile boolean running = true;

    private JFrame frame;
    private Display display;

    /**
     * @param saveFile where battery-backed SRAM is kept, or null for no saving
     * @param gamepads gamepad input, or null for keyboard only
     */
    public EmulatorWindow(Snes snes, String title, Path saveFile, Gamepads gamepads) {
        this.snes = snes;
        this.title = title;
        this.saveFile = saveFile;
        this.gamepads = gamepads;
    }

    /** Opens the window and starts emulating. Returns immediately. */
    public void start() {
        SwingUtilities.invokeLater(this::createWindow);
        // Not a daemon thread: when the window closes, the JVM waits for this thread to leave
        // its loop and write the final save before exiting.
        Thread emulation = new Thread(this::emulationLoop, "emulation");
        emulation.start();
    }

    private void createWindow() {
        frame = new JFrame("JFC - " + title);
        display = new Display();
        frame.add(display);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                running = false;
            }
        });
        // A KeyListener only hears keys while its own component has keyboard focus, and inside
        // a JFrame that's usually a child component, not the frame. A dispatcher registered
        // with the focus manager sees every key event in the application instead.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::handleKey);
        frame.setVisible(true);
    }

    /** Turns key presses and releases into controller buttons. */
    private boolean handleKey(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            frame.dispose();
            return true;
        }
        Integer button = KEY_BINDINGS.get(e.getKeyCode());
        if (button == null) {
            return false; // not ours: let Swing handle it normally
        }
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            heldButtons.updateAndGet(held -> held | button);
        } else if (e.getID() == KeyEvent.KEY_RELEASED) {
            heldButtons.updateAndGet(held -> held & ~button);
        }
        return true;
    }

    /**
     * Runs frames at the console's real rate. Sleeping until each frame's deadline (instead of
     * a fixed sleep) keeps the average rate exact even though individual frames vary in cost.
     */
    private void emulationLoop() {
        boolean pal = snes.cartridge().header().region().isPal();
        long framePeriod = (long) (1_000_000_000L / (pal ? PAL_FRAMES_PER_SECOND : NTSC_FRAMES_PER_SECOND));
        long nextFrameTime = System.nanoTime();
        long fpsWindowStart = System.nanoTime();
        int framesInWindow = 0;

        // With a sound device, playing each frame's audio paces the emulation (it blocks while
        // the device's buffer is full). Without one, we sleep until each frame is due instead.
        AudioOutput audio = AudioOutput.open().orElse(null);
        if (audio == null) {
            System.out.println("No sound device available; running without sound.");
        }
        short[] samples = new short[4096];

        loadSave();
        snes.reset();
        long frameNumber = 0;
        while (running) {
            if (gamepads != null) {
                gamepads.poll(gamepadButtons);
            }
            snes.joypads().setPressed(0, heldButtons.get() | gamepadButtons[0]);
            snes.joypads().setPressed(1, gamepadButtons[1]);
            snes.runFrame();
            publishFrame();
            framesInWindow++;
            if (++frameNumber % SAVE_INTERVAL_FRAMES == 0) {
                writeSaveIfChanged();
            }

            int sampleCount = snes.apu().drainSamples(samples);
            long now;
            if (audio != null) {
                audio.play(samples, sampleCount);
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
                int fps = framesInWindow;
                SwingUtilities.invokeLater(() -> {
                    if (frame != null) {
                        frame.setTitle("JFC - " + title + " - " + fps + " fps");
                    }
                });
                framesInWindow = 0;
                fpsWindowStart = now;
            }
        }
        if (audio != null) {
            audio.close();
        }
        writeSaveIfChanged();
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

    /** Copies the PPU's finished frame into the image and asks Swing to repaint. */
    private void publishFrame() {
        int lines = snes.ppu().visibleLines();
        synchronized (imageLock) {
            System.arraycopy(snes.ppu().frameBuffer(), 0, imagePixels, 0, 256 * lines);
            visibleLines = lines;
        }
        if (display != null) {
            display.repaint();
        }
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Paints the picture scaled to fill the window while keeping its shape. */
    private final class Display extends JComponent {

        Display() {
            setPreferredSize(new Dimension(256 * SCALE, 224 * SCALE));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            synchronized (imageLock) {
                int lines = visibleLines;
                double scale = Math.min(getWidth() / 256.0, getHeight() / (double) lines);
                int width = (int) (256 * scale);
                int height = (int) (lines * scale);
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                g2.drawImage(image, x, y, x + width, y + height, 0, 0, 256, lines, null);
            }
        }
    }
}
