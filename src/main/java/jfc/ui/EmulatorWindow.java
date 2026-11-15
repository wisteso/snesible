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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import jfc.cpu.io.JoypadPorts;
import jfc.frontend.Frame;
import jfc.frontend.InputSource;
import jfc.frontend.VideoSink;
import jfc.video.SnesColor;

/**
 * A Swing window that shows the emulator's picture and turns key presses into buttons.
 *
 * <p>It's one possible user interface for an {@link jfc.frontend.EmulationSession}: as a
 * {@link VideoSink} it receives each finished frame, and {@link #keyboard()} is an
 * {@link InputSource} for controller 1. Two threads are involved:
 * <ul>
 *   <li>The <b>emulation thread</b> calls {@link #present}, which converts the frame to RGB
 *       into an image (under a lock) and asks Swing to repaint.</li>
 *   <li>Swing's <b>event thread</b> paints that image and records key presses in an atomic
 *       integer, which the emulation thread reads when it polls the keyboard.</li>
 * </ul>
 *
 * <p>Keys: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
 * Shift or Backspace = Select, Escape = quit.
 */
public final class EmulatorWindow implements VideoSink {

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

    private final String title;
    private final BufferedImage image = new BufferedImage(256, 239, BufferedImage.TYPE_INT_RGB);
    private final int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    private final Object imageLock = new Object();
    private int visibleLines = 224;  // guarded by imageLock
    private final AtomicInteger heldKeys = new AtomicInteger();

    private JFrame frame;
    private Display display;

    public EmulatorWindow(String title) {
        this.title = title;
    }

    /**
     * Opens the window (on Swing's event thread). Returns immediately.
     *
     * @param onClose run when the user closes the window or presses Escape
     */
    public void open(Runnable onClose) {
        SwingUtilities.invokeLater(() -> createWindow(onClose));
    }

    /** The keyboard, as buttons on controller 1. */
    public InputSource keyboard() {
        return buttonsPerPlayer -> buttonsPerPlayer[0] |= heldKeys.get();
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
            heldKeys.updateAndGet(held -> held | button);
        } else if (e.getID() == KeyEvent.KEY_RELEASED) {
            heldKeys.updateAndGet(held -> held & ~button);
        }
        return true;
    }

    /** Converts the frame to RGB into the image and asks Swing to repaint. */
    @Override
    public void present(Frame f) {
        synchronized (imageLock) {
            SnesColor.toRgb(f.pixels(), imagePixels, f.width() * f.height());
            visibleLines = f.height();
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
