package jfc.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import jfc.core.apu.Apu;
import jfc.core.cartridge.Cartridge;
import jfc.core.system.Snes;

/**
 * {@code --record-audio}: runs a game without a window and saves its sound as a WAV file. Useful
 * for checking the APU without listening in real time, and for comparing changes.
 */
public final class AudioRecording {

    private AudioRecording() {
    }

    public static void record(Path rom, int frames, Path output, String[] presses) throws IOException {
        InputScript input = new InputScript(presses);
        Snes snes = new Snes(Cartridge.load(rom));
        snes.reset();
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
        System.out.printf("Saved %.1f seconds of audio to %s%n", pcm.size() / 4.0 / Apu.SAMPLE_RATE, output);
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
