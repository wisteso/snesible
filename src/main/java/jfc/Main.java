package jfc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jfc.core.cartridge.Cartridge;
import jfc.core.system.Snes;
import jfc.tools.AudioRecording;
import jfc.tools.CartridgeInfo;
import jfc.tools.GamepadTest;
import jfc.tools.HeadlessRun;
import jfc.tools.Screenshot;
import jfc.ui.EmulationSession;
import jfc.ui.InputSource;
import jfc.ui.input.GamepadMapping;
import jfc.ui.input.winmm.Gamepads;
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
            Path romFile = Path.of(args[0]);
            VideoMode mode = VideoMode.SHARP;
            boolean scanlines = false;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--video" -> mode = VideoMode.parse(args[++i]);
                    case "--scanlines" -> scanlines = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            Cartridge cart = Cartridge.load(romFile);
            System.out.println(SwingWindow.CONTROLS_HELP);
            Gamepads gamepads = Gamepads.open(GamepadMapping.load(GAMEPAD_MAPPING_FILE)).orElse(null);
            Path saveFile = cart.header().hasBattery() ? Cartridge.saveFileFor(romFile) : null;
            SwingWindow window = new SwingWindow(cart.header().title(), mode, scanlines);
            List<InputSource> inputs = new ArrayList<>(List.of(window.keyboard()));
            if (gamepads != null) {
                inputs.add(gamepads);
                window.onRescanDevices(gamepads::requestRescan);
            }
            EmulationSession session = new EmulationSession(new Snes(cart), saveFile, window, inputs);
            window.open(session::stop);
            session.start();
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
                  jfc <rom-file> [--video <mode>] [--scanlines]
                                                 Play a game in a window. Modes: sharp
                                                 (default), composite, svideo, rgb,
                                                 monochrome, rf (NTSC TV filters)
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
}
