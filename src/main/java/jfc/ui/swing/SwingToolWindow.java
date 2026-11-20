package jfc.ui.swing;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import jfc.ui.ToolRedrawPacer;
import jfc.ui.ToolScreen;
import jfc.ui.ToolScreen.ToolKey;

/**
 * A second Swing window that shows a {@link ToolScreen} (the memory debugger) next to the
 * game, for the Swing frontend.
 *
 * <p><b>Threads:</b> the tool screen must only be touched on the emulation thread, but Swing
 * delivers mouse and key events on its own event thread. So events are queued here and
 * handed to the screen at the start of {@link #afterFrame()}, which the session runs on the
 * emulation thread after each frame. The screen then renders into this window's image under
 * {@link #imageLock}, and Swing paints the image on its thread, the same hand-off the game
 * picture uses ({@code SwingWindow.imageLock}).
 *
 * <p>Closing the window hides it; the game keeps running.
 *
 * <p><b>Debug support:</b> used only by {@code jfc.debug.DebugMain}.
 */
public final class SwingToolWindow {

    private final ToolScreen screen;
    private final Queue<Consumer<ToolScreen>> events = new ConcurrentLinkedQueue<>();
    private final ToolRedrawPacer pacer = new ToolRedrawPacer();
    private final Object imageLock = new Object();
    // Guarded by imageLock.
    private BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

    // The panel's size in device pixels, and device pixels per Swing unit (1.25 at 125%
    // display scaling). Written on Swing's thread, read on the emulation thread. The screen is
    // rendered at device pixels and drawn back at the panel's size, so it stays sharp.
    private volatile int panelWidth;
    private volatile int panelHeight;
    private volatile double pixelScale = 1;
    private volatile boolean shown = true;

    private JFrame frame;
    private Panel panel;
    private int[] pixels = new int[0];

    private SwingToolWindow(ToolScreen screen) {
        this.screen = screen;
    }

    /** Opens the window (on Swing's thread, shortly). */
    public static SwingToolWindow open(ToolScreen screen) {
        SwingToolWindow window = new SwingToolWindow(screen);
        SwingUtilities.invokeLater(window::create);
        return window;
    }

    private void create() {
        frame = new JFrame(screen.title());
        panel = new Panel();
        frame.add(panel);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setLocationByPlatform(true);
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                shown = true;
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                shown = false;
            }
        });
        frame.setVisible(true);
    }

    /** Shows the window again after it was closed. */
    public void show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    /** Closes the window for good (when the game window closes). */
    public void dispose() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
    }

    /** Hands queued input to the screen, lets it update, and redraws (emulation thread). */
    public void afterFrame() {
        for (Consumer<ToolScreen> event = events.poll(); event != null; event = events.poll()) {
            event.accept(screen);
        }
        screen.afterFrame();
        int width = panelWidth;
        int height = panelHeight;
        if (!shown || width <= 0 || height <= 0 || !pacer.shouldRedraw()) {
            return;
        }
        long started = System.nanoTime();
        if (pixels.length != width * height) {
            pixels = new int[width * height];
        }
        screen.render(pixels, width, height);
        synchronized (imageLock) {
            if (image.getWidth() != width || image.getHeight() != height) {
                image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            }
            int[] target = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(pixels, 0, target, 0, pixels.length);
        }
        pacer.redrawTook(System.nanoTime() - started);
        panel.repaint();
    }

    private static ToolKey toolKey(int code) {
        return switch (code) {
            case KeyEvent.VK_ENTER -> ToolKey.ENTER;
            case KeyEvent.VK_ESCAPE -> ToolKey.ESCAPE;
            case KeyEvent.VK_BACK_SPACE -> ToolKey.BACKSPACE;
            case KeyEvent.VK_TAB -> ToolKey.TAB;
            case KeyEvent.VK_LEFT -> ToolKey.LEFT;
            case KeyEvent.VK_RIGHT -> ToolKey.RIGHT;
            case KeyEvent.VK_UP -> ToolKey.UP;
            case KeyEvent.VK_DOWN -> ToolKey.DOWN;
            case KeyEvent.VK_PAGE_UP -> ToolKey.PAGE_UP;
            case KeyEvent.VK_PAGE_DOWN -> ToolKey.PAGE_DOWN;
            case KeyEvent.VK_HOME -> ToolKey.HOME;
            case KeyEvent.VK_END -> ToolKey.END;
            default -> null;
        };
    }

    /** Paints the latest image and turns Swing events into queued tool-screen calls. */
    private final class Panel extends JComponent {

        Panel() {
            setPreferredSize(new Dimension(screen.preferredWidth(), screen.preferredHeight()));
            setFocusable(true);
            setFocusTraversalKeysEnabled(false); // let Tab reach the screen
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int x = deviceX(e);
                    int y = deviceY(e);
                    events.add(s -> s.mouseMoved(x, y));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    mouseMoved(e);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    button(e, true);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    button(e, false);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    events.add(ToolScreen::mouseExited);
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    // Swing: rotation > 0 is towards the user; the tool screen counts away as positive.
                    double clicks = -e.getPreciseWheelRotation();
                    int x = deviceX(e);
                    int y = deviceY(e);
                    events.add(s -> s.mouseWheel(clicks, x, y));
                }

                private void button(MouseEvent e, boolean pressed) {
                    int button = switch (e.getButton()) {
                        case MouseEvent.BUTTON1 -> 0;
                        case MouseEvent.BUTTON3 -> 1;
                        case MouseEvent.BUTTON2 -> 2;
                        default -> -1;
                    };
                    if (button >= 0) {
                        int x = deviceX(e);
                        int y = deviceY(e);
                        events.add(s -> s.mouseButton(button, pressed, x, y));
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    ToolKey key = toolKey(e.getKeyCode());
                    if (key != null) {
                        events.add(s -> s.keyPressed(key));
                    }
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    char c = e.getKeyChar();
                    if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
                        events.add(s -> s.keyTyped(c));
                    }
                }
            });
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            if (getGraphicsConfiguration() != null) {
                pixelScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
            }
            panelWidth = (int) Math.round(width * pixelScale);
            panelHeight = (int) Math.round(height * pixelScale);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            setBounds(getX(), getY(), getWidth(), getHeight()); // now the scale is known
        }

        private int deviceX(MouseEvent e) {
            return (int) (e.getX() * pixelScale);
        }

        private int deviceY(MouseEvent e) {
            return (int) (e.getY() * pixelScale);
        }

        @Override
        protected void paintComponent(Graphics g) {
            synchronized (imageLock) {
                // Back to Swing units; at device scaling this lands pixel for pixel.
                g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }
}
