package snesible.core.apu;

import static snesible.core.observe.Observable.wrapSpc700;

import snesible.core.system.MasterClock;

/**
 * The audio processing unit: an SPC700 CPU, 64 KiB of RAM, three timers, the DSP and a boot ROM.
 * In effect it's a separate little computer in the SNES, with its own clock.
 *
 * <h2>Memory map (SPC700 side)</h2>
 * <pre>
 *   $0000-$00EF  RAM (direct page 0)
 *   $00F0-$00FF  I/O registers (writes also reach the RAM underneath):
 *                  $F1 CONTROL   timer enables, port clearing, boot ROM on/off
 *                  $F2 DSPADDR   DSP register number
 *                  $F3 DSPDATA   read/write that DSP register
 *                  $F4-$F7       the four ports to/from the main CPU
 *                  $F8-$F9       two spare bytes of RAM-like storage
 *                  $FA-$FC       timer 0-2 targets
 *                  $FD-$FF       timer 0-2 outputs (4-bit counts, cleared when read)
 *   $0100-$FFBF  RAM
 *   $FFC0-$FFFF  boot ROM (while CONTROL bit 7 is set), otherwise RAM
 * </pre>
 *
 * <h2>Keeping two computers in step</h2>
 * The APU runs from its own crystal: the SPC700 does about 1,024,000 cycles a second, against the
 * main CPU's 21.477 MHz master clock. We run it lazily ("catch-up"): whenever the main CPU touches
 * the ports, and at the end of every frame, the APU runs until its cycle count matches the time
 * that has passed on the master clock. Between those moments nothing can observe the APU, so
 * running it late changes nothing.
 */
public final class Apu implements ApuPorts {

    /** The SPC700's clock rate: a 24.576 MHz crystal divided by 24. */
    private static final long SPC_CLOCK_HZ = 1_024_000;
    private static final long NTSC_MASTER_HZ = 21_477_272;
    private static final long PAL_MASTER_HZ = 21_281_370;
    /** The DSP produces one stereo sample every 32 SPC700 cycles: 32,000 per second. */
    public static final int SAMPLE_RATE = 32_000;
    private static final int CYCLES_PER_SAMPLE = 32;

    private final MasterClock clock;
    private final long masterClockHz;
    private final byte[] ram = new byte[0x10000];
    private final Spc700 cpu = new Spc700(wrapSpc700(new SpcSide()));
    private final Dsp dsp = new Dsp(ram);
    private final Timer[] timers = {new Timer(128), new Timer(128), new Timer(16)};

    private long cycles;
    private int cyclesUntilSample = CYCLES_PER_SAMPLE;

    /** Port latches: what the main CPU wrote (the SPC700 reads these) and the reverse. */
    private final int[] fromCpu = new int[4];
    private final int[] toCpu = new int[4];
    private int control;
    private int dspAddress;

    /** Generated audio, as interleaved left/right 16-bit samples, waiting to be collected. */
    private final short[] sampleBuffer = new short[SAMPLE_RATE];
    private int sampleCount;
    private final int[] stereoSample = new int[2];
    /** Makes the sound that's played from the DSP's work, or null to play the DSP's own output. */
    private SoundRenderer renderer;

    /**
     * Timer 0 and 1 tick at 8 kHz (every 128 SPC cycles), timer 2 at 64 kHz (every 16). Each
     * counts its ticks up to a target, then bumps a 4-bit output counter. Sound drivers read the
     * outputs to pace the music.
     */
    private static final class Timer {
        final int divider;
        int prescaler;
        int stage;
        int target;
        int output;
        boolean enabled;

        Timer(int divider) {
            this.divider = divider;
        }

        void tick() {
            if (++prescaler < divider) {
                return;
            }
            prescaler = 0;
            if (!enabled) {
                return;
            }
            stage = (stage + 1) & 0xFF;
            // A target of 0 means 256.
            if (stage == (target & 0xFF)) {
                stage = 0;
                output = (output + 1) & 0x0F;
            }
        }
    }

    public Apu(MasterClock clock, boolean pal) {
        this.clock = clock;
        this.masterClockHz = pal ? PAL_MASTER_HZ : NTSC_MASTER_HZ;
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(fromCpu, 0);
        java.util.Arrays.fill(toCpu, 0);
        control = 0x80; // boot ROM visible, timers off
        for (Timer timer : timers) {
            timer.enabled = false;
            timer.stage = 0;
            timer.output = 0;
        }
        dsp.reset();
        cycles = 0;
        cpu.reset();
        // Line the APU's time up with the master clock, so catch-up starts from now.
        cycles = clock.totalCycles() * SPC_CLOCK_HZ / masterClockHz;
    }

    /** Runs the APU until it has caught up with the master clock. */
    public void catchUp() {
        long target = clock.totalCycles() * SPC_CLOCK_HZ / masterClockHz;
        while (cycles < target) {
            cpu.step();
        }
    }

    /**
     * Moves the generated audio into {@code destination} (interleaved left/right).
     *
     * @return the number of 16-bit values copied (twice the number of stereo samples)
     */
    public int drainSamples(short[] destination) {
        int count = Math.min(sampleCount, destination.length);
        System.arraycopy(sampleBuffer, 0, destination, 0, count);
        System.arraycopy(sampleBuffer, count, sampleBuffer, 0, sampleCount - count);
        sampleCount -= count;
        return count;
    }

    /**
     * Attaches a renderer that makes the played sound from the DSP's work (the enhanced audio
     * presets), or detaches it with null. It only looks at the DSP, so the console runs exactly the
     * same either way. Call it before running, from the thread that runs the console.
     */
    public void setSoundRenderer(SoundRenderer renderer) {
        this.renderer = renderer;
    }

    public Spc700 cpu() {
        return cpu;
    }

    /**
     * The 64 KiB of sound RAM itself (not a copy), shared by the SPC700 and the DSP.
     *
     * <p><b>Debug support:</b> not needed to emulate the console; it exists for the visual
     * memory debugger (showing sound RAM) and other tools.
     */
    public byte[] ramBytes() {
        return ram;
    }

    /**
     * A DSP register (0-$7F), read without side effects. This is what the SPC700 would read
     * through $F2/$F3, including the values the DSP updates itself (ENVX, OUTX, ENDX).
     *
     * <p><b>Debug support:</b> not needed to emulate the console; the visual memory debugger
     * uses it to see which voices are playing which samples.
     */
    public int peekDspRegister(int register) {
        return dsp.read(register);
    }

    // =============================================================================================
    // The main CPU's side: $2140-$2143
    // =============================================================================================

    @Override
    public int read(int port) {
        catchUp();
        return toCpu[port];
    }

    @Override
    public void write(int port, int value) {
        catchUp();
        fromCpu[port] = value;
    }

    // =============================================================================================
    // The SPC700's side: its bus, one call per cycle
    // =============================================================================================

    /** The SPC700's view of the APU's address space. */
    private final class SpcSide implements Spc700Bus {

        @Override
        public int read(int address) {
            tick();
            if (address >= 0xF0 && address <= 0xFF) {
                return readRegister(address);
            }
            if (address >= BootRom.START && (control & 0x80) != 0) {
                return BootRom.CODE[address - BootRom.START];
            }
            return ram[address] & 0xFF;
        }

        @Override
        public void write(int address, int value) {
            tick();
            if (address >= 0xF0 && address <= 0xFF) {
                writeRegister(address, value);
            }
            ram[address] = (byte) value; // writes always reach RAM, even under registers or ROM
        }

        @Override
        public void idle() {
            tick();
        }
    }

    /** One SPC700 cycle passes: run the timers, and the DSP every 32nd cycle. */
    private void tick() {
        cycles++;
        for (Timer timer : timers) {
            timer.tick();
        }
        if (--cyclesUntilSample == 0) {
            cyclesUntilSample = CYCLES_PER_SAMPLE;
            dsp.generateSample(stereoSample);
            if (renderer != null) {
                renderer.afterSample(dsp, stereoSample); // may replace what's played
            }
            if (sampleCount + 2 > sampleBuffer.length) {
                sampleCount = 0; // nobody is collecting audio (e.g. a headless run): drop it
            }
            sampleBuffer[sampleCount++] = (short) stereoSample[0];
            sampleBuffer[sampleCount++] = (short) stereoSample[1];
        }
    }

    private int readRegister(int address) {
        return switch (address) {
            case 0xF2 -> dspAddress;
            case 0xF3 -> dsp.read(dspAddress);
            case 0xF4, 0xF5, 0xF6, 0xF7 -> fromCpu[address - 0xF4];
            case 0xF8, 0xF9 -> ram[address] & 0xFF;
            case 0xFD, 0xFE, 0xFF -> {
                Timer timer = timers[address - 0xFD];
                int value = timer.output;
                timer.output = 0; // reading clears the count
                yield value;
            }
            default -> 0; // $F0, $F1 and the timer targets are write-only
        };
    }

    private void writeRegister(int address, int value) {
        switch (address) {
            case 0xF1 -> writeControl(value);
            case 0xF2 -> dspAddress = value;
            case 0xF3 -> {
                // Only the first 128 DSP registers exist; $80-$FF are read-only mirrors.
                if (dspAddress < 0x80) {
                    dsp.write(dspAddress, value);
                }
            }
            case 0xF4, 0xF5, 0xF6, 0xF7 -> toCpu[address - 0xF4] = value;
            case 0xFA, 0xFB, 0xFC -> timers[address - 0xFA].target = value;
            default -> {
                // $F0 (TEST) is for factory testing; $F8-$F9 are plain storage; $FD-$FF read-only.
            }
        }
    }

    /**
     * $F1 CONTROL. Bits 0-2 enable the timers (switching one on restarts it from zero). Bits 4
     * and 5 clear the ports the main CPU wrote (ports 0-1 and 2-3), so a sound driver can
     * reset its mailbox. Bit 7 maps the boot ROM over the top 64 bytes of RAM.
     */
    private void writeControl(int value) {
        for (int i = 0; i < 3; i++) {
            boolean enable = (value & (1 << i)) != 0;
            if (enable && !timers[i].enabled) {
                timers[i].stage = 0;
                timers[i].output = 0;
            }
            timers[i].enabled = enable;
        }
        if ((value & 0x10) != 0) {
            fromCpu[0] = 0;
            fromCpu[1] = 0;
        }
        if ((value & 0x20) != 0) {
            fromCpu[2] = 0;
            fromCpu[3] = 0;
        }
        control = value;
    }
}
