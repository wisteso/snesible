# Milestone 11 plan: enhanced audio (SNESamp-style)

Status: planned (added 2026-11-16 at the user's request). Nothing here is built yet. The open
decisions at the end need the user's answer before work starts.

## Goal

An optional audio mode that sounds **better than the real console can**, using the techniques
of SNESamp, Alpha-II's Winamp plug-in for SNES music (`.spc` files). The current output stays
available as the "Hardware" preset; the new one is "Quality".

## What SNESamp does

SNESamp (v3.3.4, 2008) is built on Alpha-II's SNESAPU emulation library (v3.0). From its
download page (alpha-ii.com, Downloads), the library offers:
- an 8-192 kHz output rate and 8/16/24/32-bit samples;
- linear, cubic, 8-point sinc and Gaussian interpolation (Gaussian is what the console does);
- per-voice anti-aliasing (added with the sinc option in SNESAPU 3.0);
- integer or floating-point mixing;
- stereo controls: L/R separation, feedback crosstalk, phase invert (also reverse stereo);
- presets for "hardware compatibility" and for "quality".

Alpha-II's article "Audio Interpolation" (alpha-ii.com, Info) explains why: repeating samples
gives hard edges that sound like static, linear interpolation draws straight lines, and cubic
(spline) interpolation follows the wave's curves more closely. Detail lost when the sample was
recorded can't be recovered.

SNESAPU's source code is published on the same site, but the page doesn't state a licence.
We re-implement the techniques from their descriptions and don't copy that code.

## Why the console sounds the way it does

Our DSP (`snesible.core.apu.Dsp`) reproduces the hardware's sound path, including what limits it:
- **32 kHz output.** Nothing above 16 kHz can exist. Windows then resamples our 32 kHz stream to
  the device rate.
- **Gaussian interpolation.** Each voice resamples its BRR samples to 32 kHz using 4 points
  and a Gaussian curve. That curve is a gentle low-pass filter, which is a large part of the
  famous "muffled" SNES sound. Most game samples are stored at low rates, so it rounds off
  the treble that remains.
- **Aliasing when a sample is played faster than it was recorded.** A 4-point Gaussian can't
  band-limit a sample whose pitch step is above 1, so high notes fold back as harsh
  frequencies.
- **16-bit integer stages.** The envelope multiply (`>> 11 & ~1`), each volume (`>> 7`) and
  each partial sum (`clamp16`) truncate or clip as they go.
- **Stepped envelopes and volumes.** These change at 32 kHz with no smoothing between steps.

## The key design decision: the hardware DSP stays in charge

Games can observe the DSP. They read ENVX, OUTX and ENDX, and the echo unit writes its buffer
into audio RAM, which some games also use as working memory. If enhanced audio changed any of
that, it could change what a game does.

So the existing `Dsp` keeps running exactly as now and remains the only thing the emulation
sees. A new **enhanced renderer** runs alongside it and produces only the audible output. On
every 32 kHz tick the DSP hands the renderer each voice's state: newly decoded BRR samples,
effective pitch (after pitch modulation), envelope, volumes, key-on/off and noise. The renderer
turns that into sound at the output rate. Checking this is easy: emulation state must match
bit for bit with the renderer on or off (see Testing).

The renderer lives in `snesible.core.apu`. It's pure computation with no UI, so `CoreBoundaryTest`
is unaffected. Choosing the preset and the output rate belongs to `snesible.ui.audio`.

## Sub-steps (one reviewable commit each)

**11a. A real output rate and a good resampler.**
- `AudioOutput` opens the line at 48 kHz (or the rate decided below) instead of 32 kHz.
- A band-limited polyphase resampler (windowed sinc) converts the Hardware preset's 32 kHz
  stream, so we no longer rely on Windows' resampler.
- Dynamic rate control keeps its tiny linear stretch (at most 0.5%) at the end of the chain.
- The Hardware preset must still sound the same; the only change is where the resampling
  happens.

**11b. The enhanced voice renderer.** For each voice:
- *Sample history:* keep a longer ring of decoded BRR samples than the DSP's 12, taken from
  the DSP rather than decoded again, so loop points and key-on timing can't drift. Output is
  delayed by a few samples so 8-point kernels can look ahead.
- *Interpolators:* Gaussian (as hardware), linear, cubic (4-point Hermite / Catmull-Rom), and
  8-point windowed sinc. The pitch step becomes `pitch / 4096 * 32000 / outputRate` source
  samples per output sample.
- *Per-voice anti-aliasing:* when the step is above 1, the sinc's cutoff is lowered by
  1/step (the kernel is stretched), so the sample is band-limited before it's resampled. Use a
  few precomputed kernel tables, one per range of steps, rather than computing kernels per
  sample.
- *Envelope and volume:* full precision, ramped linearly between 32 kHz ticks to avoid zipper
  noise.
- *Noise voices:* the hardware LFSR is a 32 kHz signal, so it goes through the resampler as
  is. Its character comes from that rate, so it isn't made "smoother".

**11c. Floating-point mixing and the echo.**
- Sum voices in `float` with no intermediate clipping, then apply the main volume.
- *Echo:* the DSP's echo stays at 32 kHz. It must keep writing audio RAM, and its FIR filter
  is designed for 32 kHz. Its wet output is upsampled with the 11a resampler and mixed in.
  This is the recommended approach; the alternative is in the open decisions.
- Clip once at the end, with a gentle limiter rather than hard clipping. Output 16-bit, or
  24-bit if Java Sound's line accepts it on the user's machine.

**11d. Stereo controls and presets.**
- *Separation:* mid/side width, from mono to wider than the original.
- *Crosstalk:* blend a little of each side into the other, for headphones.
- *Phase "un-invert":* many games set one negative volume for a fake surround effect, which
  sounds hollow on headphones. This option uses the absolute volume.
- *Reverse stereo.*
- *Presets:*
  - "Hardware": 32 kHz, Gaussian, integer path, as now.
  - "Quality": output rate, 8-point sinc with anti-aliasing, float mixing, a limiter, and
    stereo options at neutral.
- A key in both windows cycles the presets; the choice is saved with the other settings.

**11e. Tools.**
- `--record-audio` gets `--audio-preset hardware|quality` (plus individual options), so
  recordings can be compared.
- A small spectrum check for the tests, and for documenting results.

## Testing

- **Emulation untouched:** run several games (SMW, Yoshi's Island, Super Mario RPG) for 600
  frames with the renderer on and off. Hashes of audio RAM, DSP registers, WRAM and the frame
  buffer must be identical.
- **Hardware preset unchanged:** its 32 kHz samples match the current `Dsp` output bit for bit.
- **Interpolators:** each reproduces a constant (DC) exactly and a slow sine within a small
  error.
- **Anti-aliasing:** a stored sine near the source Nyquist frequency, played at pitch steps
  2 and 4, leaves alias images below about -60 dB with anti-aliasing on (measured with a DFT
  in the test), and clearly above that with it off.
- **Continuity:** no clicks at key-on, key-off, loop points or preset changes (check for
  large sample-to-sample jumps).
- **Listening:** the user compares Hardware and Quality recordings and the live window (the
  final judge, as for the CRT scanlines).

## Not planned

- Quadraphonic output, and 8-bit output.
- Running the echo FIR at the higher rate (unless the user picks it below).
- Any change to what the hardware preset or the emulation produces.

## Open decisions (ask the user before starting)

1. **Default preset:** Hardware or Quality? (Suggestion: Hardware, matching the emulator's
   accuracy-first stance, with Quality one key away.)
2. **Output rate:** 48 kHz, the device's own rate, or also 96/192 kHz? Windows' shared mode
   converts to the device rate anyway, so above 48 kHz gains little unless the device runs
   that fast.
3. **Echo:** upsample the hardware's 32 kHz echo (recommended, exact and simple), or render
   echo at the higher rate as well (costly to get right, and the echo RAM writes would still
   have to happen at 32 kHz)?
4. **Which controls, and where:** presets only, or the individual options (interpolation,
   stereo) too? Keys, or a settings file?
5. **Order:** before or after Milestone 9 (debug tools)?
