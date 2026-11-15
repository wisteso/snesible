package jfc.ui.swing;

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
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import jfc.ui.Frame;
import jfc.ui.InputSource;
import jfc.ui.VideoSink;
import jfc.ui.video.VideoFilter;
import jfc.ui.video.VideoMode;

/**
 * A Swing window that shows the emulator's picture and turns key presses into buttons.
 *
 * <p>It's one possible user interface for an {@link jfc.ui.EmulationSession}: as a
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
 * <p>Keys: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
 * Shift or Backspace = Select, F2 = next picture mode (sharp pixels or one of the NTSC
 * filters), F3 = NTSC scanlines on/off, F9 = look for newly plugged-in gamepads,
 * Escape = quit.
 *
 * <p>The window keeps the picture's shape when resized: 8:7 for sharp pixels (square SNES
 * pixels), and the NTSC filter's 602 x 448, which is close to a TV's 4:3.
 */
public final class SwingWindow implements VideoSink {

    /** Printed when the window opens, so the key mapping is easy to find. */
    public static final String CONTROLS_HELP = """
            Controls:
              D-pad         Arrow keys
              A / B         X / Z
              X / Y         S / A
              L / R         Q / W
              Start         Enter
              Select        Shift or Backspace
              Picture mode  F2 (sharp pixels, NTSC composite, S-Video, RGB, ...)
              Scanlines     F3 (with an NTSC mode)
              Find gamepads F9 (after plugging one in)
              Quit          Escape""";

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
        frame = new JFrame("JFC - " + title);
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
                frame.setTitle("JFC - " + title + " - " + fps + " fps");
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
