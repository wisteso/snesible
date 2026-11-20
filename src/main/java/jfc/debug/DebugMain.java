package jfc.debug;

import java.io.IOException;
import jfc.Main;
import jfc.core.cartridge.Cartridge;
import jfc.core.observe.Observable;
import jfc.core.system.Snes;
import jfc.ui.ToolScreen;
import jfc.ui.debug.DebugScreen;

/**
 * Entry point for playing a game with the visual memory debugger. It takes the same command
 * line as {@link Main} and changes only two things: the console is built with a
 * {@link MemoryObserver} installed, so every processor's bus is wrapped and recorded; and a
 * {@link DebugScreen} (the heat map) is shown in a second window beside the game.
 * <pre>
 *   java -jar target/jfc-0.1.0-SNAPSHOT.jar --debug game.sfc [options]
 * </pre>
 * ({@code --debug} just hands over to this class; running it directly with {@code -cp} works
 * too, but misses the jar manifest's native-access setting, so the JVM prints warnings.)
 * F12 in the game window shows or hides the debugger. It stays out of exclusive fullscreen
 * (Alt+Enter); borderless fullscreen (F11) keeps it.
 */
public final class DebugMain extends Main {

    /** Set by {@link #createSnes}, used by {@link #createToolScreen}. */
    private MemoryObserver observer;
    private String gameTitle;

    private DebugMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        new DebugMain().run(args);
    }

    @Override
    protected Snes createSnes(Cartridge cart) {
        observer = new MemoryObserver();
        gameTitle = cart.header().title();
        Observable.install(observer);
        Snes snes = new Snes(cart);   // the wrapXxx calls in the constructors wrap the buses
        observer.attach(snes);        // ...and this gives the wrappers their processors and memories
        Observable.install(null);     // nothing else gets built with it
        return snes;
    }

    @Override
    protected ToolScreen createToolScreen(Snes snes) {
        return new DebugScreen(observer, gameTitle);
    }
}
