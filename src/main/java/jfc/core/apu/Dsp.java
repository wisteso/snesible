package jfc.core.apu;

/**
 * The S-DSP: the APU's sound generator. It plays eight voices of compressed samples from APU RAM,
 * mixes them, adds echo, and outputs 32,000 stereo samples per second.
 *
 * <h2>Registers</h2>
 * 128 registers, reached by the SPC700 through $F2 (address) and $F3 (data). Each voice has
 * ten at {@code $v0-$v9} (v = voice number); the rest are global, mostly in column $xC/$xD:
 * <pre>
 *   $v0/$v1 VOL L/R   signed volume            $0C/$1C MVOL L/R  master volume
 *   $v2/$v3 PITCH     14-bit, $1000 = 32 kHz   $2C/$3C EVOL L/R  echo volume
 *   $v4 SRCN          sample number            $4C KON   key on (start voices)
 *   $v5/$v6 ADSR1/2   envelope (see below)     $5C KOF   key off (release voices)
 *   $v7 GAIN          envelope, simple mode    $6C FLG   reset, mute, echo off, noise rate
 *   $v8 ENVX          current envelope (read)  $7C ENDX  voices that reached a sample's end
 *   $v9 OUTX          current output (read)    $0D EFB   echo feedback
 *   $xF FIR0-FIR7     echo filter              $2D PMON  pitch modulation   $3D NON noise
 *                                              $4D EON   echo on   $5D DIR sample table page
 *                                              $6D ESA   echo buffer page   $7D EDL echo delay
 * </pre>
 *
 * <h2>Samples: BRR</h2>
 * Samples are stored as "bit rate reduction" blocks: 9 bytes holding 16 samples of 4 bits.
 * The header byte gives a shift (range) and a prediction filter, which reconstructs each
 * sample from the previous two. It's a simple ADPCM. The header's end and loop bits say where
 * the sample stops or loops. A directory at page DIR lists each sample's start and loop
 * addresses, 4 bytes per sample number.
 *
 * <h2>Simplification</h2>
 * The real DSP runs a 32-step pipeline per sample, touching each voice at different moments.
 * We produce each sample in one go, which sounds the same except for some register-timing edge
 * cases. Interpolation uses a computed Gaussian curve close to the hardware's table, not the
 * table itself.
 */
final class Dsp {

    // Global register addresses.
    private static final int MVOLL = 0x0C;
    private static final int MVOLR = 0x1C;
    private static final int EVOLL = 0x2C;
    private static final int EVOLR = 0x3C;
    private static final int KON = 0x4C;
    private static final int KOF = 0x5C;
    private static final int FLG = 0x6C;
    private static final int ENDX = 0x7C;
    private static final int EFB = 0x0D;
    private static final int PMON = 0x2D;
    private static final int NON = 0x3D;
    private static final int EON = 0x4D;
    private static final int DIR = 0x5D;
    private static final int ESA = 0x6D;
    private static final int EDL = 0x7D;

    // Per-voice register offsets.
    private static final int VOLL = 0x0;
    private static final int VOLR = 0x1;
    private static final int PITCHL = 0x2;
    private static final int PITCHH = 0x3;
    private static final int SRCN = 0x4;
    private static final int ADSR1 = 0x5;
    private static final int ADSR2 = 0x6;
    private static final int GAIN = 0x7;
    private static final int ENVX = 0x8;
    private static final int OUTX = 0x9;

    /**
     * Envelope and noise rates. Many DSP events happen "every N samples" for one of 32 rates.
     * A shared counter runs down through 30,720 values, and rate r fires when
     * {@code (counter + OFFSET[r]) % PERIOD[r] == 0}. The offsets keep events of different rates
     * from bunching up. Rate 0 never fires; rate 31 fires every sample.
     */
    private static final int[] RATE_PERIODS = {
        0x7801, 2048, 1536, 1280, 1024, 768, 640, 512, 384, 320, 256, 192, 160, 128, 96, 80,
        64, 48, 40, 32, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 1,
    };
    private static final int[] RATE_OFFSETS = {
        1, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536,
        0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 536, 0, 1040, 0, 0,
    };
    private static final int COUNTER_RANGE = 0x7800;

    /** Interpolation weights [fraction 0-255][tap 0-3], summing to 2048 (11-bit fixed point). */
    private static final int[][] GAUSSIAN = buildGaussianTable();

    private enum EnvelopeMode { ATTACK, DECAY, SUSTAIN, RELEASE }

    /** Per-voice playback state that isn't visible in registers. */
    private static final class Voice {
        int brrAddress;          // start of the current 9-byte BRR block
        int brrNibble;           // next 4-bit sample within the block (0-15)
        final int[] buffer = new int[12];  // the last 12 decoded samples, a ring
        int bufferPosition;      // where the next group of 4 decoded samples goes
        int interpolationPosition;  // 12.12 fixed point: how far into the buffered samples
        int envelope;            // 0-$7FF
        EnvelopeMode envelopeMode = EnvelopeMode.RELEASE;
        int keyOnDelay;          // samples left before a newly keyed-on voice starts
        int output;              // last output, after the envelope (for PMON and OUTX)
    }

    private final byte[] ram;
    private final int[] registers = new int[128];
    private final Voice[] voices = new Voice[8];

    private int counter;
    private int noise = 0x4000;
    private int pendingKeyOn;
    private boolean everyOtherSample;

    // Echo state.
    private int echoPosition;
    private int echoLength;
    private final int[][] echoHistory = new int[2][8];
    private int echoHistoryPosition;

    Dsp(byte[] ram) {
        this.ram = ram;
        for (int i = 0; i < voices.length; i++) {
            voices[i] = new Voice();
        }
        reset();
    }

    void reset() {
        java.util.Arrays.fill(registers, 0);
        registers[FLG] = 0xE0; // after reset: soft reset, mute, echo writes off
        for (Voice voice : voices) {
            voice.envelope = 0;
            voice.envelopeMode = EnvelopeMode.RELEASE;
            voice.keyOnDelay = 0;
        }
        counter = 0;
        noise = 0x4000;
        pendingKeyOn = 0;
        echoPosition = 0;
        echoLength = 0;
    }

    int read(int address) {
        return registers[address & 0x7F];
    }

    void write(int address, int value) {
        address &= 0x7F;
        registers[address] = value;
        if (address == KON) {
            pendingKeyOn |= value;
        } else if (address == ENDX) {
            registers[ENDX] = 0; // any write clears all the end flags
        }
    }

    // =============================================================================================
    // Producing one sample
    // =============================================================================================

    /** Generates one stereo sample, storing it as {left, right} in {@code out}. */
    void generateSample(int[] out) {
        if (--counter < 0) {
            counter = COUNTER_RANGE - 1;
        }
        int flags = registers[FLG];
        if (rateFires(flags & 0x1F)) {
            stepNoise();
        }

        // Key on and key off are only checked every other sample (16 kHz).
        everyOtherSample = !everyOtherSample;
        if (everyOtherSample) {
            handleKeyOnAndOff(flags);
        }

        int mainLeft = 0;
        int mainRight = 0;
        int echoInLeft = 0;
        int echoInRight = 0;
        int previousOutput = 0;
        for (int v = 0; v < 8; v++) {
            int amplitude = runVoice(v, previousOutput);
            previousOutput = voices[v].output;
            int base = v << 4;
            int left = (amplitude * (byte) registers[base + VOLL]) >> 7;
            int right = (amplitude * (byte) registers[base + VOLR]) >> 7;
            mainLeft = clamp16(mainLeft + left);
            mainRight = clamp16(mainRight + right);
            if ((registers[EON] & (1 << v)) != 0) {
                echoInLeft = clamp16(echoInLeft + left);
                echoInRight = clamp16(echoInRight + right);
            }
        }

        int[] echoOut = runEcho(echoInLeft, echoInRight, flags);
        int outLeft = clamp16(((mainLeft * (byte) registers[MVOLL]) >> 7)
                + ((echoOut[0] * (byte) registers[EVOLL]) >> 7));
        int outRight = clamp16(((mainRight * (byte) registers[MVOLR]) >> 7)
                + ((echoOut[1] * (byte) registers[EVOLR]) >> 7));
        boolean muted = (flags & 0x40) != 0;
        out[0] = muted ? 0 : outLeft;
        out[1] = muted ? 0 : outRight;
    }

    private boolean rateFires(int rate) {
        return (counter + RATE_OFFSETS[rate]) % RATE_PERIODS[rate] == 0;
    }

    /** The noise generator: a 15-bit linear feedback shift register, used by voices in NON. */
    private void stepNoise() {
        // The new top bit (bit 14) is bit 0 XOR bit 1 of the old value: shifting left by 14 and
        // 13 lines those two bits up at bit 14. Everything else shifts down by one.
        int feedback = (noise << 13) ^ (noise << 14);
        noise = (feedback & 0x4000) | (noise >> 1);
    }

    private void handleKeyOnAndOff(int flags) {
        boolean softReset = (flags & 0x80) != 0;
        for (int v = 0; v < 8; v++) {
            Voice voice = voices[v];
            int bit = 1 << v;
            if ((pendingKeyOn & bit) != 0) {
                keyOn(v);
            } else if (softReset || ((registers[KOF] & bit) != 0 && voice.keyOnDelay == 0)) {
                voice.envelopeMode = EnvelopeMode.RELEASE;
            }
            if (softReset) {
                voice.envelope = 0;
            }
        }
        pendingKeyOn = 0;
    }

    /** Starts a voice from the beginning of its sample. */
    private void keyOn(int v) {
        Voice voice = voices[v];
        int entry = (registers[DIR] << 8) + registers[(v << 4) + SRCN] * 4;
        voice.brrAddress = readWord(entry);
        voice.brrNibble = 0;
        java.util.Arrays.fill(voice.buffer, 0);
        voice.bufferPosition = 0;
        voice.interpolationPosition = 0;
        voice.envelope = 0;
        voice.envelopeMode = EnvelopeMode.ATTACK;
        // A keyed-on voice stays silent for 5 samples while the hardware fetches its first data.
        voice.keyOnDelay = 5;
        registers[ENDX] &= ~(1 << v);
        decodeFourSamples(v);
    }

    // =============================================================================================
    // One voice: sample playback, interpolation, envelope
    // =============================================================================================

    /** Advances voice {@code v} by one output sample and returns its amplitude. */
    private int runVoice(int v, int previousVoiceOutput) {
        Voice voice = voices[v];
        int base = v << 4;
        if (voice.keyOnDelay > 0) {
            voice.keyOnDelay--;
            voice.output = 0;
            registers[base + OUTX] = 0;
            registers[base + ENVX] = 0;
            return 0;
        }

        int sample = interpolate(voice);
        if ((registers[NON] & (1 << v)) != 0) {
            sample = (short) (noise << 1); // noise replaces the sample (which still advances)
        }

        updateEnvelope(voice, base);
        int amplitude = ((sample * voice.envelope) >> 11) & ~1;
        voice.output = amplitude;
        registers[base + OUTX] = (amplitude >> 8) & 0xFF;
        registers[base + ENVX] = voice.envelope >> 4;

        // Move through the sample at the voice's pitch. Pitch modulation (PMON) lets the previous
        // voice's output bend this voice's pitch, for vibrato-like effects.
        int pitch = ((registers[base + PITCHH] & 0x3F) << 8) | registers[base + PITCHL];
        if (v > 0 && (registers[PMON] & (1 << v)) != 0) {
            pitch += ((previousVoiceOutput >> 5) * pitch) >> 10;
        }
        voice.interpolationPosition = Math.min(0x7FFF, voice.interpolationPosition + pitch);
        while (voice.interpolationPosition >= 0x4000) {
            decodeFourSamples(v);
            voice.interpolationPosition -= 0x4000;
        }
        return amplitude;
    }

    /**
     * Gaussian interpolation between four consecutive samples. It smooths the jump between
     * samples when a sound is played at a pitch different from its recording. That softness is
     * part of the SNES's characteristic sound.
     */
    private static int interpolate(Voice voice) {
        // The position is fixed point: the bits above 12 count whole samples past the oldest
        // buffered one, the low 12 bits are the fraction between samples. The interpolation
        // table only has 256 steps, so the top 8 bits of the fraction choose the weights.
        int[] weights = GAUSSIAN[(voice.interpolationPosition >> 4) & 0xFF];
        int start = voice.bufferPosition + (voice.interpolationPosition >> 12);
        int result = 0;
        for (int tap = 0; tap < 4; tap++) {
            result += (weights[tap] * voice.buffer[(start + tap) % 12]) >> 11;
        }
        return clamp16(result) & ~1;
    }

    /**
     * The envelope shapes a voice's volume over time.
     *
     * <p>ADSR mode (ADSR1 bit 7): <b>attack</b> rises linearly to the top, <b>decay</b> falls
     * exponentially to the sustain level, <b>sustain</b> keeps falling exponentially (often
     * slowly or not at all), and after key off, <b>release</b> falls linearly and fast.
     * <pre>
     *   ADSR1: 1 ddd aaaa   (decay rate, attack rate)
     *   ADSR2: lll rrrrr    (sustain level, sustain rate)
     * </pre>
     * GAIN mode is simpler: either a fixed level, or a linear/exponential/bent slide at a rate.
     */
    private void updateEnvelope(Voice voice, int base) {
        int envelope = voice.envelope;
        if (voice.envelopeMode == EnvelopeMode.RELEASE) {
            voice.envelope = Math.max(0, envelope - 8);
            return;
        }

        int adsr1 = registers[base + ADSR1];
        int rate;
        int sustainLevel = registers[base + ADSR2] >> 5;
        if ((adsr1 & 0x80) != 0) {
            if (voice.envelopeMode == EnvelopeMode.ATTACK) {
                rate = (adsr1 & 0x0F) * 2 + 1;
                envelope += rate < 31 ? 0x20 : 0x400;
            } else {
                envelope -= ((envelope - 1) >> 8) + 1; // exponential: ~1/256 of the level
                rate = voice.envelopeMode == EnvelopeMode.DECAY
                        ? ((adsr1 >> 3) & 0x0E) + 0x10
                        : registers[base + ADSR2] & 0x1F;
            }
        } else {
            int gain = registers[base + GAIN];
            if ((gain & 0x80) == 0) {
                envelope = (gain & 0x7F) * 16; // direct: the level is set immediately
                rate = 31;
            } else {
                rate = gain & 0x1F;
                switch ((gain >> 5) & 3) {
                    case 0 -> envelope -= 0x20;                               // linear decrease
                    case 1 -> envelope -= ((envelope - 1) >> 8) + 1;          // exponential decrease
                    case 2 -> envelope += 0x20;                               // linear increase
                    default -> envelope += envelope < 0x600 ? 0x20 : 0x08;    // bent line increase
                }
            }
        }

        if (voice.envelopeMode == EnvelopeMode.DECAY && (envelope >> 8) == sustainLevel) {
            voice.envelopeMode = EnvelopeMode.SUSTAIN;
        }
        if (envelope < 0 || envelope > 0x7FF) {
            envelope = envelope < 0 ? 0 : 0x7FF;
            if (voice.envelopeMode == EnvelopeMode.ATTACK) {
                voice.envelopeMode = EnvelopeMode.DECAY;
            }
        }
        if (rateFires(rate)) {
            voice.envelope = envelope;
        }
    }

    // =============================================================================================
    // BRR decoding
    // =============================================================================================

    /** Decodes the next 4 samples of voice {@code v}'s BRR data into its ring buffer. */
    private void decodeFourSamples(int v) {
        Voice voice = voices[v];
        int header = ram[voice.brrAddress & 0xFFFF] & 0xFF;
        int range = header >> 4;
        int filter = (header >> 2) & 3;

        for (int i = 0; i < 4; i++) {
            int dataByte = ram[(voice.brrAddress + 1 + (voice.brrNibble >> 1)) & 0xFFFF] & 0xFF;
            int nibble = (voice.brrNibble & 1) == 0 ? dataByte >> 4 : dataByte & 0x0F;
            voice.brrNibble++;

            int sample = (nibble << 28) >> 28;              // sign-extend 4 bits
            // Scale by the range (a left shift of 0-12), landing at half scale (the >> 1).
            // Ranges 13-15 aren't valid; the hardware treats them as "0 or -2048 by sign".
            sample = range <= 12 ? (sample << range) >> 1 : (sample < 0 ? -2048 : 0);

            // Prediction filters: add a weighted guess based on the two previous samples.
            // The new sample is at half scale, as is p2 (thanks to its >> 1); p1 is kept at full
            // scale, so each p1 term below is twice what it looks like. The shifts work out to:
            //   filter 1:  + 15/16 * previous
            //   filter 2:  + 61/32 * previous  - 15/16 * the one before
            //   filter 3:  + 115/64 * previous - 13/16 * the one before
            // Hardware computes these with shifts, rounding down at each step; so do we.
            int p1 = voice.buffer[(voice.bufferPosition + 11) % 12];
            int p2 = voice.buffer[(voice.bufferPosition + 10) % 12] >> 1;
            switch (filter) {
                case 1 -> sample += (p1 >> 1) + ((-p1) >> 5);
                case 2 -> sample += p1 - p2 + (p2 >> 4) + ((p1 * -3) >> 6);
                case 3 -> sample += p1 - p2 + ((p1 * -13) >> 7) + ((p2 * 3) >> 4);
                default -> {
                    // filter 0: no prediction
                }
            }
            // Samples are kept as 15-bit values doubled into 16 bits, wrapping like the hardware.
            voice.buffer[voice.bufferPosition] = (short) (clamp16(sample) * 2);
            voice.bufferPosition = (voice.bufferPosition + 1) % 12;
        }

        if (voice.brrNibble == 16) {
            finishBlock(v, header);
        }
    }

    /** At the end of a 9-byte block: move to the next one, or loop/stop at the sample's end. */
    private void finishBlock(int v, int header) {
        Voice voice = voices[v];
        voice.brrNibble = 0;
        boolean end = (header & 0x01) != 0;
        boolean loop = (header & 0x02) != 0;
        if (end) {
            registers[ENDX] |= 1 << v;
            int entry = (registers[DIR] << 8) + registers[(v << 4) + SRCN] * 4;
            voice.brrAddress = readWord(entry + 2);   // the loop address
            if (!loop) {
                voice.envelopeMode = EnvelopeMode.RELEASE;
                voice.envelope = 0;
            }
        } else {
            voice.brrAddress = (voice.brrAddress + 9) & 0xFFFF;
        }
    }

    // =============================================================================================
    // Echo
    // =============================================================================================

    private final int[] echoOutput = new int[2];

    /**
     * Echo: a delay line in APU RAM (at page ESA, EDL * 2 KiB long, i.e. up to 240 ms), run through
     * an 8-tap FIR filter. Each sample: read the delayed sample, filter it, mix it into the output
     * at EVOL, and write back the echo voices plus feedback (EFB) of the filtered signal.
     *
     * @return the filtered echo output {left, right}
     */
    private int[] runEcho(int inLeft, int inRight, int flags) {
        int bufferStart = registers[ESA] << 8;
        int address = (bufferStart + echoPosition) & 0xFFFF;

        // Read the delayed sample into the filter history.
        echoHistoryPosition = (echoHistoryPosition + 1) & 7;
        echoHistory[0][echoHistoryPosition] = (short) readWord(address) >> 1;
        echoHistory[1][echoHistoryPosition] = (short) readWord(address + 2) >> 1;

        for (int channel = 0; channel < 2; channel++) {
            // FIR0 weights the oldest sample, FIR7 the newest. The first seven products wrap at
            // 16 bits, and only the last addition saturates, as on the hardware.
            int sum = 0;
            for (int tap = 0; tap < 7; tap++) {
                sum += firProduct(channel, tap);
            }
            sum = (short) sum;
            sum = clamp16(sum + firProduct(channel, 7));
            echoOutput[channel] = sum & ~1;
        }

        boolean writesEnabled = (flags & 0x20) == 0;
        if (writesEnabled) {
            int feedback = (byte) registers[EFB];
            int left = clamp16(inLeft + ((echoOutput[0] * feedback) >> 7)) & ~1;
            int right = clamp16(inRight + ((echoOutput[1] * feedback) >> 7)) & ~1;
            writeWord(address, left);
            writeWord(address + 2, right);
        }

        // The buffer length is only re-read when the position wraps around.
        if (echoPosition == 0) {
            echoLength = (registers[EDL] & 0x0F) * 0x800;
        }
        echoPosition += 4;
        if (echoPosition >= echoLength) {
            echoPosition = 0;
        }
        return echoOutput;
    }

    private int firProduct(int channel, int tap) {
        int sample = echoHistory[channel][(echoHistoryPosition + 1 + tap) & 7];
        return (sample * (byte) registers[(tap << 4) | 0x0F]) >> 6;
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private int readWord(int address) {
        return (ram[address & 0xFFFF] & 0xFF) | ((ram[(address + 1) & 0xFFFF] & 0xFF) << 8);
    }

    private void writeWord(int address, int value) {
        ram[address & 0xFFFF] = (byte) value;
        ram[(address + 1) & 0xFFFF] = (byte) (value >> 8);
    }

    private static int clamp16(int value) {
        return Math.max(-32768, Math.min(32767, value));
    }

    /**
     * Builds the interpolation weights from a Gaussian bell curve, close to the hardware's
     * (which was measured from a real chip). For an output point a fraction f of the way between
     * the two middle samples, the four taps are 1+f, f, 1-f and 2-f samples away.
     */
    private static int[][] buildGaussianTable() {
        double sigma = 0.635;
        int[][] table = new int[256][4];
        for (int fraction = 0; fraction < 256; fraction++) {
            double f = fraction / 256.0;
            double[] distances = {1 + f, f, 1 - f, 2 - f};
            double[] raw = new double[4];
            double total = 0;
            for (int tap = 0; tap < 4; tap++) {
                raw[tap] = Math.exp(-(distances[tap] * distances[tap]) / (2 * sigma * sigma));
                total += raw[tap];
            }
            for (int tap = 0; tap < 4; tap++) {
                table[fraction][tap] = (int) Math.round(raw[tap] / total * 2048);
            }
        }
        return table;
    }
}
