package snesible.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import snesible.core.apu.Apu;
import snesible.core.apu.enhanced.AudioSettings;
import snesible.core.apu.enhanced.EnhancedRenderer;
import snesible.core.cartridge.Cartridge;
import snesible.core.system.Snes;

/**
 * {@code --record-audio}: runs a game without a window and saves its sound as a WAV file. Useful
 * for checking the APU without listening in real time, and for comparing changes.
 *
 * <p>The sound options of the windows work here too ({@code --audio}, {@code --stereo-width},
 * {@code --surround-fix}), so the presets can be recorded and compared side by side.
 */
public final class AudioRecording {

    private AudioRecording() {
    }

    public static void record(Path rom, int frames, Path output, String[] arguments) throws IOException {
        List<String> presses = new ArrayList<>();
        AudioSettings audio = AudioSettings.parse(arguments, presses);
        InputScript input = new InputScript(presses.toArray(new String[0]));
        Snes snes = new Snes(Cartridge.load(rom));
        snes.reset();
        snes.apu().setSoundRenderer(new EnhancedRenderer(audio));
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] samples = new short[4096];
        for (int frame = 1; frame <= frames; frame++) {
            snes.joypads().setPressed(0, input.buttonsAt(frame));
            snes.runFrame();
            int count = snes.apu().drainSamples(samples);
            for (int i = 0; i < count; i++) {
                pcm.write(samples[i] & 0xFF);
                pcm.write((samples[i] >> 8) & 0xFF);
            }
        }
        try (OutputStream out = Files.newOutputStream(output)) {
            writeWav(out, pcm.toByteArray());
        }
        System.out.printf("Saved %.1f seconds of audio (%s) to %s%n", pcm.size() / 4.0 / Apu.SAMPLE_RATE,
                audio.preset().label(), output);
    }

    /**
     * A WAV file is a 44-byte RIFF header followed by raw samples: here 16-bit signed
     * little-endian, two channels, 32 kHz, the DSP's native format.
     */
    static void writeWav(OutputStream out, byte[] pcm) throws IOException {
        int channels = 2;
        int bytesPerSample = 2;
        int byteRate = Apu.SAMPLE_RATE * channels * bytesPerSample;
        out.write("RIFF".getBytes());
        writeInt(out, 36 + pcm.length);
        out.write("WAVEfmt ".getBytes());
        writeInt(out, 16);                           // size of the format chunk
        writeShort(out, 1);                          // PCM
        writeShort(out, channels);
        writeInt(out, Apu.SAMPLE_RATE);
        writeInt(out, byteRate);
        writeShort(out, channels * bytesPerSample);  // bytes per sample frame
        writeShort(out, 16);                         // bits per sample
        out.write("data".getBytes());
        writeInt(out, pcm.length);
        out.write(pcm);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        writeShort(out, value & 0xFFFF);
        writeShort(out, value >>> 16);
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
