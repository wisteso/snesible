# Milestone 11 plan: enhanced audio (SNESamp-style)

Status: **built** (2026-11-20); the user has listened to the recordings (see "After listening"). Added 2026-11-16 at the
user's request; detailed and simplified with the user on 2026-11-20. The user's decisions: our
own implementation (route C), Hardware as the default, F10 as the key, 32 kHz output, stereo
width and surround fix kept, anti-aliasing always on. "Results" near the end has the
measurements; where the build differs from the first plan, the text below has been updated.

## Goal

An optional audio mode that sounds **better than the real console can**, using the techniques of
SNESamp, Alpha-II's Winamp plug-in for SNES music (`.spc` files). It's an alternative to the
standard audio, not a replacement: the current output stays as the "Hardware" preset, and one key
cycles between it and two "Quality" presets while a game runs.

Agreed with the user: minimal changes to the existing APU classes (a few getters), with all the
enhancement happening outside them, and only the options that are worth their code.

## What SNESamp is

SNESamp (v3.3.4, 2008) is a thin player around Alpha-II's **SNESAPU** library, which does all the
emulation and all the sound processing. What we learned from alpha-ii.com (Downloads and Source
pages) and from the maintained fork used by the SPCPlay player (github.com/dgrfactory/spcplay):

- **What it emulates:** the whole APU, both the SPC700 and the DSP. Its API is built for `.spc`
  files, which are snapshots of a self-contained sound program: `SetAPUOpt(mixType, channels,
  bits, rate, interpolation, options)`, `LoadSPCFile(data)`, then `EmuAPU(buffer, length, type)`
  to run it and collect sound. The documentation doesn't describe a way to drive the DSP alone.
- **Language:** the emulation is x86 assembly (NASM), with a small C++ layer for the DLL
  interface. It was written for 32-bit Windows; the fork adds a 64-bit build.
- **Licence:** GNU GPL version 2 or later (the fork's `LICENSE` and readme). Alpha-II's own
  Source page shows no licence text.
- **Sound features** (SNESamp 3.3.4, SNESAPU 3.0 and the fork together):
  - 8-192 kHz output, 8/16/24/32-bit samples;
  - linear, cubic, Gaussian (what the console does) and 8-point sinc interpolation;
  - per-voice anti-aliasing (added with the sinc option in 3.0);
  - integer or 32-bit floating-point mixing;
  - stereo controls: L/R separation, feedback crosstalk, phase invert, reverse stereo;
  - in the fork: a "crackle-noise canceller", a bass boost, and a low-pass filter with the same
    cut-off as the real console's output;
  - presets for "hardware compatibility" and "quality".

Alpha-II's article "Audio Interpolation" explains the reasoning: repeating samples gives hard
edges that sound like static, linear interpolation draws straight lines, and cubic (spline)
interpolation follows the wave's curves more closely. Detail lost when a sample was recorded can't
be recovered.

## Three ways to get it: we write our own

### A. Load the SNESAPU DLL (through Java's foreign function API): rejected

- **It's a second APU, not a better DSP.** SNESAPU runs its own SPC700 from a snapshot. In an
  emulator the sound program talks to the main CPU through ports $2140-$2143 thousands of times a
  second, so we would have to run two APUs side by side (ours for the game, SNESAPU for the sound)
  and keep them in step through an API that was never designed for it. Any difference in their
  timing makes the two drift apart, and the sound would then no longer match the game.
- **It breaks a project rule.** The only native code allowed is LWJGL. A DLL also ties the
  feature to Windows.
- **32-bit.** The original DLL can't load into a 64-bit JVM; only the fork's x64 build would.
- **Licence.** Shipping Snesible with a GPL library makes the distributed whole GPL.
- **Nothing to learn from.** An assembly DLL behind a function call goes against the project's
  educational purpose.

### B. Port SNESAPU's code to Java: rejected

- **Licence.** A port is a derivative work, so it stays GPL. The NTSC filter could be ported
  because it's LGPL, which lets the rest of the project stay MIT. GPL doesn't: once linked into
  Snesible, it would make the whole program GPL.
- **Readability.** Translating hand-tuned x86 assembly (FPU and MMX code) gives unreadable Java.
- **Accuracy.** SNESAPU's DSP is a 2001-2008 design. Our `Dsp` is modelled on later research and
  is what games actually see.

### C. Re-implement the techniques: chosen

Interpolation kernels, anti-aliasing, floating-point mixing and stereo processing are standard
signal processing; ideas and algorithms aren't covered by copyright. We write our own from the
published feature descriptions and textbook DSP, without reading SNESAPU's source. The project
stays MIT and pure Java, and our accurate `Dsp` stays in charge of everything a game can see.

## Would it improve sound effects too?

**Yes, as much as the music.** The SNES has no separate sound-effect hardware: a game plays its
effects on the same eight DSP voices as its music, often taking a voice away from the music for
the length of the effect. Everything here applies to every voice:
- **Sharper samples.** Effects are often stored at very low sample rates to save room in the
  64 KiB of audio RAM, so they're the most muffled sounds in a game. Cubic or sinc interpolation
  keeps more of their remaining treble.
- **Less harshness when pitched up.** Effects are often played far above their recorded pitch, or
  swept in pitch (lasers, engines, rising jingles). That's exactly when the hardware aliases;
  per-voice anti-aliasing removes most of it.
- **No clipping when it gets busy.** A loud effect on top of the music can overflow the hardware's
  16-bit sums and clip. Floating-point mixing with one limiter at the end avoids that.
- **Fewer clicks.** When a game takes a voice for an effect, the hardware cuts the music note off
  at once, which clicks. De-click smooths that.

Some things stay as they are, by design:
- **Noise effects** (explosions, wind, static) come from the DSP's noise generator, whose sound
  comes from its 32 kHz rate.
- **BRR's own noise.** Samples are stored as 4-bit ADPCM, and its quantisation noise is part of
  the recording.
- **The echo** (reverb) is the hardware's own (see 11c).

**One risk to listen for:** many games' samples were recorded with extra treble, to make up for
the Gaussian filter's softening. Without that filter some samples may sound brighter than the
composers intended, or hissy. That's why there are two Quality presets, cubic and sinc: listening
decides which becomes the usual choice.

## Why the console sounds the way it does

Our DSP (`snesible.core.apu.Dsp`) reproduces the hardware's sound path, including what limits it:
- **32 kHz output.** Nothing above 16 kHz can exist.
- **Gaussian interpolation.** Each voice resamples its BRR samples to 32 kHz using 4 points and a
  Gaussian curve. That curve is a gentle low-pass filter, and a large part of the famous
  "muffled" SNES sound.
- **Aliasing when a sample is played faster than it was recorded.** A 4-point Gaussian can't
  band-limit a sample whose pitch step is above 1, so high notes fold back as harsh frequencies.
- **16-bit integer stages.** The envelope multiply (`>> 11 & ~1`), each volume (`>> 7`) and each
  partial sum (`clamp16`) truncate or clip as they go.

The enhanced renderer fixes all of these except the first, on purpose (next section).

## The output rate stays at 32 kHz

The Quality presets output 32 kHz, the console's own rate, like the Hardware preset. Two different
things hide behind "a higher rate", and only one of them would gain anything:
- **Resampling the finished 32 kHz sound to 48 kHz ourselves** would only repeat what Windows'
  audio engine already does with our stream. It would help only if our resampler were better
  than Windows', and the difference would be tiny.
- **Rendering the voices at 48 kHz** does keep something 32 kHz can't hold: the band from 16 to
  24 kHz, which a sample played above its recorded pitch can genuinely contain. But game samples
  have almost nothing up there, and most adults hear little of it.

Staying at 32 kHz removes a lot of code: no resampler for the Hardware preset, no positions
computed between DSP ticks, no envelope ramps between ticks, and no upsampling of the echo and the
noise. `AudioOutput` doesn't change. Everything that makes the Quality presets sound better
(sharper interpolation up to 16 kHz, anti-aliasing, no clipping, de-click, stereo) works the same
at 32 kHz. Nothing in the design blocks adding a higher rate later.

## The key design decision: the hardware DSP stays in charge

Games can observe the DSP. They read ENVX, OUTX and ENDX, and the echo unit writes its buffer into
audio RAM, which some games also use as working memory. If enhanced audio changed any of that, it
could change what a game does.

So the existing `Dsp` keeps running exactly as now and remains the only thing the emulation sees.
A new **enhanced renderer** runs alongside it and produces only what you hear. It never writes
registers or audio RAM. With the Hardware preset the renderer isn't attached at all, and the cost
is one null check per sample.

```
  SPC700 ──registers──▶ Dsp (unchanged, 32 kHz) ──▶ ENVX/OUTX/ENDX, echo RAM  (what games see)
                         │                    │
                         │ read-only view     └─▶ 32 kHz output ──▶ Hardware preset
                         │ (DspState)
                         ▼
                EnhancedRenderer (32 kHz) ───────────────────────▶ Quality presets
```

### Keeping `Dsp` simple: the renderer observes, the DSP doesn't know

`Dsp` gets no hooks, callbacks or subclass, and none of its logic changes. Instead:
- `Dsp` implements a small public, read-only interface, `DspState`: one-line getters for what it
  already stores (registers, and each voice's 12-sample ring, ring position, interpolation
  position, envelope, key-on delay and last output; the noise value; the echo output, which is
  already kept in the `echoOutput` field). That's the whole change to `Dsp`: a block of getters
  at the end of the file.
- `Apu.tick()` gains one line after `dsp.generateSample(...)`:
  `if (renderer != null) renderer.afterSample(dsp)`.
- Everything else lives in `snesible.core.apu.enhanced`, which the console never imports.

After each 32 kHz tick the renderer compares the DSP's state with the previous tick's:
- **New samples:** if the ring position moved, the DSP decoded 4 new BRR samples, and the renderer
  copies them into its own longer history. It never decodes BRR itself, so loop points, the end
  flag and the prediction filters can't disagree. (The interpolation position is capped at $7FFF,
  and a voice in its key-on delay doesn't advance, so a tick decodes at most one group of 4.)
- **Key-on:** a key-on delay of 5 means the voice was just restarted and its ring cleared; the
  renderer does the same, or fades the old sound out (de-click).
- **Position and pitch:** the renderer interpolates at the DSP's own position, so it can't drift
  from the hardware, even over hours. The position's change over the tick (plus $4000 if 4 samples
  were decoded) is the pitch the DSP actually used, after pitch modulation; the anti-aliasing
  needs it, and the renderer doesn't have to recompute pitch modulation.
- **Envelope, volumes, noise, echo:** read directly. At 32 kHz each tick is exactly one output
  sample, so none of these need ramping.

Why not a subclass of `Dsp`? To be extended, `Dsp` would have to stop being `final` and turn its
private steps (`runVoice`, `decodeFourSamples`, `keyOn`) into overridable hooks. A subclass would
then depend on the order those steps run in, and changing the hardware model later could break
it. Observing from the outside keeps the two independent: `Dsp` can be read and tested without
knowing the renderer exists.

### Look-ahead

An 8-point kernel centred on the current position needs 4 samples ahead of it, and a stretched
anti-aliasing kernel needs up to 16. The renderer therefore delays its whole output (every voice
and the echo alike) by a fixed 16 ticks, 0.5 ms, which can't be heard. Each voice keeps a
64-sample ring of decoded samples, enough for the widest kernel.

## The options

| Option | Values | Where it's set |
|--------|--------|----------------|
| Preset | Hardware (default), Quality (sinc), Quality (cubic) | F10 cycles them in that order (the new one is printed); `--audio hardware\|sinc\|cubic` sets the start |
| Stereo width | 0-200% (100% = as the game mixes it) | `--stereo-width` |
| Surround fix | on / off | `--surround-fix` |

Always on in the Quality presets, with no switch, because they only remove problems:
- **Anti-aliasing** (sinc). It does nothing when a sample plays at or below its recorded pitch,
  and above it only removes frequencies that weren't in the sample; those are the harsh ones. The
  hardware's sound is still one key away. Tests can switch it off internally.
- **Floating-point mixing** and full-precision envelopes and volumes.
- **A soft limiter** instead of hard clipping, and **dither** when converting to 16-bit.
- **De-click.**

Stereo width also applies to the Hardware preset's output. The surround fix works only in the
Quality presets: it needs each voice's volume, and the Hardware preset's output is the DSP's
finished mix. There's no settings file yet (only `gamepad.properties`), so the options are
command-line flags in `PlayOptions`, like the existing ones.

## Sub-steps (one reviewable commit each)

**11a. The read-only view and a renderer skeleton.**
- `DspState` and its getters on `Dsp`, the one line in `Apu`, and an `EnhancedRenderer` with eight
  `EnhancedVoice`s.
- First version: Gaussian only, float mixing. That should sound like the hardware.
- Done when: the renderer's output matches `Dsp`'s within a couple of least significant bits over
  several minutes of several games (the integer rounding steps are the only difference). This
  proves the view carries everything before any enhancement is added.

**11b. Cubic, sinc and anti-aliasing.**
- Interpolators, as an enum: Gaussian (as hardware, needed for 11a's check), cubic (4-point
  Catmull-Rom spline) and 8-point windowed sinc (Kaiser window).
- *Anti-aliasing:* when the pitch step is above 1, the sinc's cut-off is lowered to 1/step, which
  stretches the kernel to `8 × step` taps. The pitch register tops out at a step of about 4 (more
  with pitch modulation, where we cap the kernel at step 4). We precompute tables for 17 step
  bands between 1 and 4 (eight per octave, each about 9% apart; a voice uses the first band at or
  above its step), rather than compute kernels per sample. As built, the cubic is stretched the
  same way, so both Quality presets are anti-aliased, and both stay continuous through a step of 1.
- Done when: the interpolator and anti-aliasing tests (below) pass.

**11c. Mixing, echo and output.**
- *Mixing:* sum the voices in `float` with full-precision envelopes and volumes and no clipping
  along the way, then apply the main volume.
- *Noise voices:* the hardware's noise value, used as is.
- *Echo:* the `Dsp`'s echo output (after its FIR filter), mixed in at the echo volume. The echo
  has to keep running in `Dsp` anyway, since it writes audio RAM.
- *De-click:* when a key-on restarts a voice that is still sounding, the hardware cuts it to
  silence at once. The renderer instead fades the old sound out over about 2 ms while the new one
  starts.
- *Output:* a soft limiter (gentle above -1 dBFS), then TPDF dither to 16-bit.
- Done when: the continuity tests pass, and a recording at a busy moment (music plus effects)
  shows no clipped samples.

**11d. Stereo, presets and the UI.**
- *Stereo width:* mid/side, from mono to wider than the original.
- *Surround fix:* many games set one negative volume for a fake surround effect, which sounds
  hollow on headphones. With the fix, each voice and the echo use the absolute value of their
  volumes.
- An `AudioSettings` record holds the preset and the two stereo options; `snesible.ui` chooses it.
- F10 in both windows cycles the presets and prints the new one, as F2 does for the picture.
  Switching crossfades over 5 ms so it doesn't click.
- Done when: switching presets while a game plays is click-free in both windows.

**11e. Tools and documents.**
- `--record-audio` accepts the same `--audio`, `--stereo-width` and `--surround-fix` options, so
  recordings can be compared side by side.
- A small `Spectrum` helper (one DFT bin over a Hann window), in the tests.
- Update `docs/architecture.md` (APU section and diagram), `docs/frontend.md` (audio options and
  key) and `docs/status.md`.

## Where the code goes

| Class | Package | Role |
|-------|---------|------|
| `DspState` | `snesible.core.apu` | read-only view of the DSP (getters only) |
| `SoundRenderer` | `snesible.core.apu` | the one hook in `Apu`: called after each DSP sample, may replace it |
| `EnhancedRenderer`, `EnhancedVoice` | `snesible.core.apu.enhanced` | the renderer |
| `Interpolator`, `SincKernels` | `snesible.core.apu.enhanced` | interpolation and the kernel tables |
| `AudioPreset`, `AudioSettings` | `snesible.core.apu.enhanced` | the presets, and preset plus stereo options (a record, with the command-line parsing) |
| changes to `PlayOptions`, `EmulationSession`, the windows | `snesible.ui` | flags, F10, printed message |

Everything in `snesible.core` stays pure computation, so `CoreBoundaryTest` is unaffected.
Expected size: about 300-350 lines of main code (with the usual documentation), most of it the
sinc tables and the voice renderer, plus tests.

## Cost

The worst case is eight voices with 32-tap stretched kernels at 32 kHz: about 8 million
multiply-adds a second, around 1% of one CPU core. Typical music uses 8-tap kernels, a quarter of
that. It runs on the emulation thread, as the DSP does now.

## Testing

- **Emulation untouched:** run several games (the LoROM platformer, the GSU-2 game, the SA-1 game)
  for 600 frames with the renderer on and off. Hashes of audio RAM, DSP registers, WRAM and the
  frame buffer must be identical.
- **Hardware preset unchanged:** its samples match the current `Dsp` output bit for bit.
- **View complete:** 11a's Gaussian renderer matches `Dsp` within a couple of bits.
- **Interpolators:** each reproduces a constant (DC) exactly and a slow sine within a small error.
- **Anti-aliasing:** a stored sine near the source Nyquist frequency, played at pitch steps 2 and
  4, leaves alias images below about -60 dB with anti-aliasing on (measured with a DFT in the
  test), and clearly above that with it off.
- **Continuity:** no clicks at key-on, key-off, loop points or preset changes (checked as large
  sample-to-sample jumps).
- **Listening:** the user compares Hardware, cubic and sinc recordings, then the live windows.
  This is the final judge, as it was for the CRT scanlines. Listen especially to effects pitched
  far up, busy moments, and bright samples.

## Results (2026-11-20)

- **Tests:** 964 pass (32 new). `EnhancedRendererGameTest` runs every test game for 500 frames:
  the emulation hashes (audio RAM, DSP registers, WRAM, picture) are identical with the renderer
  on and off; the Hardware preset is the DSP's output sample for sample; and with the Gaussian
  the renderer matches the DSP within 14 units of 32,768 (the DSP's integer rounding), which
  shows `DspState` carries everything.
- **Anti-aliasing** (`KernelTableTest`): a tone at 0.45 of the sample's rate, played 2 and 4
  times faster than recorded, folds back at -5.5 dB without it, -77 / -82 dB with the stretched
  sinc and -48 / -51 dB with the stretched cubic. Tones inside the output's range keep their
  level within 0.5 dB.
- **De-click** (`EnhancedRendererTest`): restarting a loud voice jumps by over 15,000 units on
  the hardware and under 5,000 with de-click. Switching presets while a note plays stays under
  6,000 (the note itself changes by up to ~4,000 per sample).
- **Cost:** none measurable. 25 s of the GSU-2 game recorded in 4.30 s with Hardware and 4.25 s
  with sinc.
- **Recordings** (25 s each, git-ignored, in `testdata/m11/`): the LoROM platformer has 4.5 dB
  more energy at 4-8 kHz and 8 dB more at 8-12 kHz with sinc than with Hardware; above 12 kHz both
  games have less energy in the Quality presets, because on the hardware much of that band is
  aliasing. No full-scale samples in any recording.
- **Not yet checked:** F10 in the live windows, stereo width and the surround fix by ear.

## Not planned

- Loading or porting SNESAPU itself (routes A and B above).
- Output above 32 kHz, and our own resampling to the device rate (see "The output rate stays at
  32 kHz"). Can be added later if listening shows a need.
- Linear interpolation (it aliases more than Gaussian and sounds harsher), integer mixing in the
  renderer (the Hardware preset already is that), a switch for anti-aliasing, 24-bit output,
  crosstalk, reverse stereo, quadraphonic output.
- A settings file.
- Any change to what the Hardware preset or the emulation produces. A model of the console's
  analog output filter would change Hardware, so it would be its own decision.

## After listening

- 2026-11-20, from the recordings of the LoROM platformer: the user finds **sinc sounds a bit
  better**; cubic is "almost too tinny", but may suit some games, so both presets stay. The
  default stays Hardware. F10 now goes Hardware, sinc, cubic (the user's order). The recordings
  fit the "tinny" impression: cubic has less energy than sinc at 4-12 kHz but more above
  12 kHz, where its weaker anti-aliasing lets more fold-back through.
- Still open: Kaiser β for the sinc (6.0 now): lower keeps a little more top end, higher filters
  harder.
