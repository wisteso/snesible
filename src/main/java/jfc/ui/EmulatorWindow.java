package jfc.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
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
import jfc.system.Snes;

/**
 * A Swing window that runs the emulator and shows its picture.
 *
 * <p>Two threads are involved:
 * <ul>
 *   <li>The <b>emulation thread</b> runs one frame at a time, copies the finished picture into
 *       an image, and sleeps until the next frame is due (about 60 per second on NTSC).</li>
 *   <li>Swing's <b>event thread</b> paints that image and turns key presses into controller
 *       buttons.</li>
 * </ul>
 * They share only the image (guarded by a lock) and the button state (an atomic integer).
 *
 * <p>Keys: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
 * Shift or Backspace = Select, Escape = quit.
 */
public final class EmulatorWindow {

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

    private final Snes snes;
    private final String title;
    private final BufferedImage image = new BufferedImage(256, 239, BufferedImage.TYPE_INT_RGB);
    private final int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    private final Object imageLock = new Object();
    private volatile int visibleLines = 224;
    private final AtomicInteger heldButtons = new AtomicInteger();
    private volatile boolean running = true;

    private JFrame frame;
    private Display display;

    public EmulatorWindow(Snes snes, String title) {
        this.snes = snes;
        this.title = title;
    }

    /** Opens the window and starts emulating. Returns immediately. */
    public void start() {
        SwingUtilities.invokeLater(this::createWindow);
        Thread emulation = new Thread(this::emulationLoop, "emulation");
        emulation.setDaemon(true);
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
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    frame.dispose();
                    return;
                }
                Integer button = KEY_BINDINGS.get(e.getKeyCode());
                if (button != null) {
                    heldButtons.updateAndGet(held -> held | button);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                Integer button = KEY_BINDINGS.get(e.getKeyCode());
                if (button != null) {
                    heldButtons.updateAndGet(held -> held & ~button);
                }
            }
        });
        frame.setVisible(true);
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

        snes.reset();
        while (running) {
            snes.joypads().setPressed(0, heldButtons.get());
            snes.runFrame();
            publishFrame();
            framesInWindow++;

            nextFrameTime += framePeriod;
            long now = System.nanoTime();
            if (now < nextFrameTime) {
                sleepNanos(nextFrameTime - now);
            } else if (now - nextFrameTime > 5 * framePeriod) {
                nextFrameTime = now; // fell far behind (e.g. a pause): don't try to catch up
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
