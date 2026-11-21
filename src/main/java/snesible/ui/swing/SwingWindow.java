package snesible.ui.swing;

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import snesible.core.system.Snes;
import snesible.ui.EmulationSession;
import snesible.ui.Frame;
import snesible.ui.InputSource;
import snesible.ui.PlayOptions;
import snesible.ui.ToolScreen;
import snesible.ui.VideoSink;
import snesible.ui.video.VideoFilter;
import snesible.ui.video.VideoMode;

/**
 * A Swing window that shows the emulator's picture and turns key presses into buttons.
 *
 * <p>It's one possible user interface for an {@link snesible.ui.EmulationSession}: as a
 * {@link VideoSink} it receives each finished frame, and {@link #keyboard()} is an
 * {@link InputSource} for controller 1. Two threads are involved:
 * <ul>
 *   <li>The <b>emulation thread</b> calls {@link #present}, which draws the frame into an
 *       image (under a lock) through the current {@link VideoFilter} and asks Swing to
 *       repaint.</li>
 *   <li>Swing's <b>event thread</b> paints that image and passes key presses to the
 *       {@link SwingKeyboard}, which the emulation thread polls.</li>
 * </ul>
 *
 * <p>Keys are listed in {@link snesible.ui.Controls}: the controller keys (see
 * {@link SwingKeyboard}), F2 = next picture mode (sharp pixels or one of the NTSC filters),
 * F3 = NTSC scanlines on/off, F9 = look for newly plugged-in gamepads, F12 = show the tool
 * window again (the memory debugger, when there is one), Escape = quit.
 *
 * <p>The window keeps the picture's shape when resized: 8:7 for sharp pixels (square SNES
 * pixels), and the NTSC filter's 602 x 448, which is close to a TV's 4:3.
 */
public final class SwingWindow implements VideoSink {

    private static final int SCALE = 3;

    private final String title;
    private final Object imageLock = new Object();
    // Guarded by imageLock: the picture, and how much of the image it fills.
    private BufferedImage image = new BufferedImage(256, 239, BufferedImage.TYPE_INT_RGB);
    private int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    private int pictureWidth = 256;
    private int pictureHeight = 224;
    private final SwingKeyboard keyboard = new SwingKeyboard();

    // The picture mode the user asked for (set on Swing's thread) and the filter that draws it
    // (built and used on the emulation thread, since building NTSC tables takes a moment).
    private volatile VideoMode requestedMode;
    private volatile boolean requestedScanlines;
    private VideoFilter filter;

    /** What F9 does: look for newly plugged-in gamepads. */
    private Runnable rescanDevices = () -> System.out.println("Gamepads aren't supported here.");

    private JFrame frame;
    private Display display;
    /** F12: shows the tool window again (does nothing without one). */
    private Runnable showTool = () -> { };
    /** F10: switches to the next sound preset. */
    private Runnable cycleAudioPreset = () -> { };

    /**
     * Opens a Swing window and starts playing on an emulation thread paced by a steady clock.
     * Returns immediately; the game ends when the window closes.
     *
     * @param saveFile      where battery-backed SRAM is kept, or null
     * @param extraInputs   input sources besides the keyboard (gamepads)
     * @param rescanDevices what F9 does (look for new gamepads), or null
     */
    public static void play(Snes snes, String title, Path saveFile, List<InputSource> extraInputs,
                            Runnable rescanDevices, PlayOptions options) {
        play(snes, title, saveFile, extraInputs, rescanDevices, options, null);
    }

    /**
     * Plays the game as above, with a tool screen (the memory debugger) in a second window,
     * updated after every frame. F12 shows that window again after it was closed.
     *
     * @param toolScreen the screen for the second window, or null for none
     */
    public static void play(Snes snes, String title, Path saveFile, List<InputSource> extraInputs,
                            Runnable rescanDevices, PlayOptions options, ToolScreen toolScreen) {
        SwingWindow window = new SwingWindow(title, options.videoMode(), options.scanlines());
        List<InputSource> inputs = new ArrayList<>(List.of(window.keyboard()));
        inputs.addAll(extraInputs);
        if (rescanDevices != null) {
            window.onRescanDevices(rescanDevices);
        }
        EmulationSession session = new EmulationSession(snes, saveFile, window, inputs);
        session.setPrintStats(options.stats());
        session.setAudioSettings(options.audio());
        window.cycleAudioPreset = session::cycleAudioPreset;
        Runnable onClose = session::stop;
        if (toolScreen != null) {
            SwingToolWindow tool = SwingToolWindow.open(toolScreen);
            session.setAfterFrame(tool::afterFrame);
            window.showTool = tool::show;
            onClose = () -> {
                session.stop();
                tool.dispose();
            };
        }
        window.open(onClose);
        session.start();
    }

    /**
     * @param mode      the picture mode to start with
     * @param scanlines whether NTSC modes start with scanlines
     */
    public SwingWindow(String title, VideoMode mode, boolean scanlines) {
        this.title = title;
        this.requestedMode = mode;
        this.requestedScanlines = scanlines;
    }

    /**
     * Opens the window (on Swing's event thread). Returns immediately.
     *
     * @param onClose run when the user closes the window or presses Escape
     */
    public void open(Runnable onClose) {
        SwingUtilities.invokeLater(() -> createWindow(onClose));
    }

    /**
     * Sets what F9 does: ask the gamepad code to look for newly plugged-in pads. (It doesn't
     * look automatically, because the search briefly stalls the game.)
     */
    public void onRescanDevices(Runnable action) {
        this.rescanDevices = action;
    }

    /** The keyboard, as buttons on controller 1. */
    public InputSource keyboard() {
        return keyboard;
    }

    private void createWindow(Runnable onClose) {
        frame = new JFrame("Snesible - " + title);
        display = new Display();
        frame.add(display);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                onClose.run();
            }
        });
        // A KeyListener only hears keys while its own component has keyboard focus, and inside
        // a JFrame that's usually a child component, not the frame. A dispatcher registered
        // with the focus manager sees every key event in the application instead.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::handleKey);
        frame.setVisible(true);
    }

    /** Handles the window's own keys (quit, picture mode, ...) and passes the rest to the keyboard. */
    private boolean handleKey(KeyEvent e) {
        boolean ours = e.getComponent() != null
                && (e.getComponent() == frame || SwingUtilities.getWindowAncestor(e.getComponent()) == frame);
        if (!ours && e.getID() != KeyEvent.KEY_RELEASED) {
            // A key typed into another window (the tool window) is not a controller key. Key
            // releases still go to the keyboard, so a key held while switching windows lets go.
            return false;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_F12) {
            showTool.run();
            return true;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            frame.dispose();
            return true;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_F2) {
            requestedMode = requestedMode.next();
            System.out.println("Picture: " + requestedMode.label());
            return true;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_F9) {
            System.out.println("Looking for gamepads...");
            rescanDevices.run();
            return true;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_F10) {
            // F10 also opens a window's menu bar on Windows; there's none here, and returning
            // true consumes the key so nothing else sees it.
            cycleAudioPreset.run();
            return true;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_F3) {
            requestedScanlines = !requestedScanlines;
            System.out.println("NTSC scanlines " + (requestedScanlines ? "on" : "off"));
            return true;
        }
        return keyboard.handle(e); // false if it's not a controller key: Swing handles it
    }

    /** Draws the frame into the image through the current filter and asks Swing to repaint. */
    @Override
    public void present(Frame f) {
        VideoMode mode = requestedMode;
        boolean scanlines = requestedScanlines;
        if (filter == null || filter.mode() != mode || filter.scanlines() != scanlines) {
            filter = new VideoFilter(mode, scanlines);
        }
        int width = filter.outputWidth(f.width());
        int height = filter.outputHeight(f.height());
        synchronized (imageLock) {
            if (image.getWidth() != width || image.getHeight() < height) {
                // Room for the tallest picture this mode makes (overscan has 239 lines).
                image = new BufferedImage(width, filter.outputHeight(239), BufferedImage.TYPE_INT_RGB);
                imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            }
            filter.apply(f, imagePixels);
            pictureWidth = width;
            pictureHeight = height;
        }
        if (display != null) {
            display.repaint();
        }
    }

    @Override
    public void showFramesPerSecond(int fps) {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.setTitle("Snesible - " + title + " - " + fps + " fps");
            }
        });
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
                int w = pictureWidth;
                int h = pictureHeight;
                if (w > 256) {
                    // The NTSC picture already has fine horizontal detail: smooth scaling
                    // looks closer to a TV than repeating whole output pixels.
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                }
                double scale = Math.min(getWidth() / (double) w, getHeight() / (double) h);
                int width = (int) (w * scale);
                int height = (int) (h * scale);
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                g2.drawImage(image, x, y, x + width, y + height, 0, 0, w, h, null);
            }
        }
    }
}
