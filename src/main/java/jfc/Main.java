package jfc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jfc.core.cartridge.Cartridge;
import jfc.core.system.Snes;
import jfc.tools.AudioRecording;
import jfc.tools.CartridgeInfo;
import jfc.tools.GamepadTest;
import jfc.tools.HeadlessRun;
import jfc.tools.Screenshot;
import jfc.ui.Controls;
import jfc.ui.InputSource;
import jfc.ui.PacingMode;
import jfc.ui.PlayOptions;
import jfc.ui.input.GamepadMapping;
import jfc.ui.input.winmm.Gamepads;
import jfc.ui.lwjgl.LwjglWindow;
import jfc.ui.swing.SwingWindow;
import jfc.ui.video.VideoMode;

/**
 * Command-line entry point: plays a game in a window, or runs one of the headless tools in
 * {@link jfc.tools}. The emulated console itself is in {@link jfc.core}, and everything that
 * presents it to a person (windows, sound, input, picture filters) in {@link jfc.ui}.
 */
public final class Main {

    private Main() {
    }

    /** Optional gamepad button mapping, looked for in the working directory. */
    private static final Path GAMEPAD_MAPPING_FILE = Path.of("gamepad.properties");

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length >= 1 && !args[0].startsWith("--")) {
            play(Path.of(args[0]), Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length == 1 && args[0].equals("--gamepad-test")) {
            GamepadTest.run(GamepadMapping.load(GAMEPAD_MAPPING_FILE));
            return;
        }
        if (args.length >= 2 && args[0].equals("--info")) {
            for (int i = 1; i < args.length; i++) {
                CartridgeInfo.print(Path.of(args[i]));
            }
            return;
        }
        if (args.length >= 2 && args[0].equals("--run")) {
            int frames = args.length >= 3 ? Integer.parseInt(args[2]) : 120;
            String[] presses = args.length > 3 ? Arrays.copyOfRange(args, 3, args.length) : new String[0];
            HeadlessRun.run(Path.of(args[1]), frames, presses);
            return;
        }
        if (args.length >= 4 && args[0].equals("--record-audio")) {
            String[] presses = Arrays.copyOfRange(args, 4, args.length);
            AudioRecording.record(Path.of(args[1]), Integer.parseInt(args[2]), Path.of(args[3]), presses);
            return;
        }
        if (args.length >= 4 && args[0].equals("--screenshot")) {
            String[] presses = Arrays.copyOfRange(args, 4, args.length);
            Screenshot.take(Path.of(args[1]), Integer.parseInt(args[2]), Path.of(args[3]), presses);
            return;
        }
        System.err.println("""
                Usage:
                  jfc <rom-file> [options]       Play a game in a window. Options:
                    --video <mode>                 sharp (default), composite, svideo, rgb,
                                                   monochrome, rf (NTSC TV filters)
                    --scanlines                    NTSC scanlines
                    --swing                        the Swing window instead of OpenGL
                    --pacing auto|vsync|clock      frame pacing (OpenGL window; default auto)
                    --refresh <hz>                 refresh rate to ask for in fullscreen (F11)
                    --stats                        print timing and audio statistics each second
                  jfc --gamepad-test             Show gamepad button numbers (for gamepad.properties)
                  jfc --info <rom-file>...       Print the cartridge header of each ROM
                  jfc --run <rom-file> [frames] [BUTTON@frame ...]
                                                 Run without video and report what the CPU does
                  jfc --screenshot <rom-file> <frames> <out.png> [BUTTON@frame ...]
                                                 [--video <mode>] [--scanlines]
                                                 Run headless and save the last frame as a PNG,
                                                 pressing buttons (e.g. START@200) on the way
                  jfc --record-audio <rom-file> <frames> <out.wav> [BUTTON@frame ...]
                                                 Run headless and save the sound as a WAV file""");
        System.exit(1);
    }

    /**
     * Plays a game in a window: the OpenGL window by default, or Swing with {@code --swing} (and
     * as a fallback when OpenGL 3.3 or LWJGL isn't available).
     */
    private static void play(Path romFile, String[] options) throws IOException {
        VideoMode videoMode = VideoMode.SHARP;
        boolean scanlines = false;
        boolean swing = false;
        PacingMode pacing = PacingMode.AUTO;
        int fullscreenRefresh = 0;
        boolean stats = false;
        for (int i = 0; i < options.length; i++) {
            switch (options[i]) {
                case "--video" -> videoMode = VideoMode.parse(options[++i]);
                case "--scanlines" -> scanlines = true;
                case "--swing" -> swing = true;
                case "--pacing" -> pacing = PacingMode.parse(options[++i]);
                case "--refresh" -> fullscreenRefresh = Integer.parseInt(options[++i]);
                case "--stats" -> stats = true;
                default -> throw new IllegalArgumentException("Unknown option: " + options[i]);
            }
        }
        PlayOptions playOptions = new PlayOptions(videoMode, scanlines, pacing, fullscreenRefresh, stats);

        Cartridge cart = Cartridge.load(romFile);
        String title = cart.header().title();
        Path saveFile = cart.header().hasBattery() ? Cartridge.saveFileFor(romFile) : null;
        System.out.println(Controls.HELP);
        Gamepads gamepads = Gamepads.open(GamepadMapping.load(GAMEPAD_MAPPING_FILE)).orElse(null);
        List<InputSource> gamepadInputs = gamepads == null ? List.of() : List.of(gamepads);
        Runnable rescan = gamepads == null ? null : gamepads::requestRescan;

        if (!swing) {
            LwjglWindow window = null;
            try {
                window = LwjglWindow.open(title, playOptions);
            } catch (LinkageError e) {
                // Run with "java -cp target/classes" instead of "java -jar": no target/lib.
                System.out.println("LWJGL isn't on the class path; using the Swing window instead.");
            } catch (RuntimeException e) {
                System.out.println("The OpenGL window isn't available (" + e.getMessage()
                        + "); using the Swing window instead.");
            }
            if (window != null) {
                window.play(new Snes(cart), saveFile, gamepadInputs, rescan); // returns when closed
                return;
            }
        }
        SwingWindow.play(new Snes(cart), title, saveFile, gamepadInputs, rescan, playOptions);
    }
}
