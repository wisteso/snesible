package snesible.core.apu.enhanced;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import snesible.core.apu.DspState;
import snesible.core.apu.SoundRenderer;
import snesible.core.cartridge.Cartridge;
import snesible.core.cpu.io.JoypadPorts;
import snesible.core.system.Snes;
import snesible.core.system.WorkRam;

/**
 * Checks the enhanced renderer against real games in the (git-ignored) {@code roms/} folder:
 * that it follows the DSP exactly, and that it never changes what the console does. Skipped when
 * that folder is absent.
 */
class EnhancedRendererGameTest {

    private static final Path ROM_DIR = Path.of("roms");
    /** Long enough for every test game to reach its title music (Start pressed on the way). */
    private static final int FRAMES = 500;

    static Stream<Path> romFiles() throws IOException {
        if (!Files.isDirectory(ROM_DIR)) {
            return Stream.of(Path.of("<no roms folder>"));
        }
        return Files.list(ROM_DIR)
                .filter(p -> p.toString().matches("(?i).*\\.(sfc|smc)$"))
                .sorted();
    }

    /**
     * With the hardware's own Gaussian interpolation, the renderer must reproduce the DSP's
     * output: everything it needs comes through {@link DspState}. The only differences allowed
     * are the DSP's integer rounding and its clipping when a mix gets too loud (those samples are
     * skipped). The DSP rounds down at several steps in each of its 8 voices, so the differences
     * add up to a few units, never much more (the test games stay within 14 of 32,768).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("romFiles")
    void withTheGaussianItMatchesTheDsp(Path rom) throws IOException {
        assumeTrue(Files.isRegularFile(rom), "no ROMs available");
        EnhancedRenderer renderer = new EnhancedRenderer(AudioSettings.DEFAULT.withPreset(AudioPreset.SINC));
        renderer.configureForTest(Interpolator.GAUSSIAN, false, false, false);
        Recorder recorder = new Recorder(renderer);
        run(rom, recorder);

        int compared = 0;
        int largest = 0;
        long sumOfSquares = 0;
        for (int i = EnhancedRenderer.DELAY; i < recorder.count; i++) {
            int hardware = recorder.hardware[i - EnhancedRenderer.DELAY];
            if (Math.abs(hardware) >= 32000) {
                continue; // the hardware clipped here, the renderer doesn't
            }
            int difference = Math.abs(recorder.enhanced[i] - hardware);
            compared++;
            largest = Math.max(largest, difference);
            sumOfSquares += (long) hardware * hardware;
        }
        assumeTrue(sumOfSquares > 0, "the game made no sound in " + FRAMES + " frames");
        System.out.printf("%s: %d samples compared, largest difference %d%n",
                rom.getFileName(), compared, largest);
        assertTrue(largest <= 24, "largest difference " + largest);
    }

    /** The console must run exactly the same with the renderer attached as without it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("romFiles")
    void theRendererDoesNotChangeTheEmulation(Path rom) throws IOException {
        assumeTrue(Files.isRegularFile(rom), "no ROMs available");
        byte[] without = run(rom, null);
        byte[] with = run(rom, new EnhancedRenderer(AudioSettings.DEFAULT.withPreset(AudioPreset.SINC)));
        assertArrayEquals(without, with);
    }

    /** The Hardware preset with neutral stereo passes the DSP's samples through untouched. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("romFiles")
    void theHardwarePresetIsTheDspOutput(Path rom) throws IOException {
        assumeTrue(Files.isRegularFile(rom), "no ROMs available");
        Recorder recorder = new Recorder(new EnhancedRenderer(AudioSettings.DEFAULT));
        run(rom, recorder);
        for (int i = 0; i < recorder.count; i++) {
            assertEquals(recorder.hardware[i], recorder.enhanced[i], "sample " + i);
        }
    }

    /**
     * Runs a game, pressing Start now and then to get past title screens, and returns a hash of
     * what a game can see: audio RAM, DSP registers, work RAM and the last picture.
     */
    private static byte[] run(Path rom, SoundRenderer renderer) throws IOException {
        Snes snes = new Snes(Cartridge.load(rom));
        snes.reset();
        snes.apu().setSoundRenderer(renderer);
        short[] drain = new short[8192];
        for (int frame = 1; frame <= FRAMES; frame++) {
            boolean start = frame % 120 >= 100 && frame % 120 < 105;
            snes.joypads().setPressed(0, start ? JoypadPorts.BUTTON_START : 0);
            snes.runFrame();
            snes.apu().drainSamples(drain);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(snes.apu().ramBytes());
            for (int register = 0; register < 0x80; register++) {
                digest.update((byte) snes.apu().peekDspRegister(register));
            }
            for (int offset = 0; offset < WorkRam.SIZE; offset++) {
                digest.update((byte) snes.wram().read(offset));
            }
            for (int pixel : snes.ppu().frameBuffer()) {
                digest.update((byte) pixel);
                digest.update((byte) (pixel >> 8));
                digest.update((byte) (pixel >> 16));
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Wraps a renderer and records the left channel before (the DSP's) and after (the renderer's). */
    private static final class Recorder implements SoundRenderer {
        private final SoundRenderer inner;
        final int[] hardware = new int[FRAMES * 600];
        final int[] enhanced = new int[FRAMES * 600];
        int count;

        Recorder(SoundRenderer inner) {
            this.inner = inner;
        }

        @Override
        public void afterSample(DspState dsp, int[] sample) {
            int dspLeft = sample[0];
            inner.afterSample(dsp, sample);
            if (count < hardware.length) {
                hardware[count] = dspLeft;
                enhanced[count] = sample[0];
                count++;
            }
        }
    }
}
