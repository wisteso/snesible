# Milestone 10 plan: LWJGL video, NTSC filter, CRT shaders

Status: **agreed** (2026-11-15); 10a and 10b done on `m10-video`. The user's decisions are at the bottom. Work happens on the
`m10-video` branch, in its own worktree, while emulation work continues on `main`.

## Goals

1. An authentic NTSC look in the spirit of Blargg's `snes_ntsc` (composite / S-Video / RGB),
   with the real SNES signal timing, not a generic "scanlines over pixels" filter.
2. A GPU shader pipeline (LWJGL + OpenGL) for that and for CRT effects (beam shape, mask,
   gamma, 4:3 aspect).
3. Cross-platform keyboard and gamepad input from the same library.
4. **Minimal impact on the emulation classes**: the core (`jfc.system` and below) should need
   no changes at all. Everything happens behind a small frontend boundary.

## What the NTSC filter actually needs (answering "does it need prior frames?")

**No frame history is needed.** Blargg's filter is a pure function of
(current frame, starting colour-burst phase, settings). Everything that looks "temporal" comes
from the phase:

- The SNES master clock (21.477 MHz) is exactly **6x the NTSC colour subcarrier**
  (3.5795 MHz). One SNES pixel is 4 master cycles, so **3 pixels = 2 subcarrier cycles**. That
  is where Blargg's 3-in / 7-out chunking and its 3 "alignments" come from, and why the
  artifact pattern repeats every 3 pixels. (Sampling at 4x the subcarrier gives exactly
  8 samples per 3 pixels, which is the filter's `rescale_in = 8`.)
- A line is 1364 master cycles = 227 1/3 subcarrier cycles, so **each line starts 120 degrees
  later** than the one before. That is `snes_ntsc_burst_count = 3`, advanced per row.
- Each frame also starts at a different phase, which is what makes "dot crawl" shimmer.
  Snes9x cycles the starting phase 0, 1, 2 per frame, and **we do the same** (decision 3). On
  real hardware it is probably a two-frame cycle (one line of every other non-interlaced
  frame is 4 master cycles short, per fullsnes), but with field merging on the difference is
  hard to see, and matching Snes9x keeps the user's screenshots a direct reference.
- `merge_fields` (on in Snes9x's Composite/S-Video/RGB presets) averages each phase with the
  next **inside the precomputed kernel**, which imitates the eye blending two 60 Hz fields
  into a static image. Same frame, no history. A shader does the same by decoding at two
  phases and averaging.

So the boundary only has to deliver: the frame's pixels, its width (256 now, 512 once hi-res
lands), its height (224/239) and a frame counter. The frontend already has all of that.

What the user's examples show, mapped to the model:
- Horizontal softening and colour bleed: luma low-pass (`sharpness`, `resolution`) and the
  narrower chroma band (`bleed`).
- Dithered checkerboards blending into a solid colour, with rainbow fringes on edges:
  composite luma/chroma crosstalk (`artifacts`, `fringing`). The Genesis/Saturn examples are
  stronger than the SNES will be: their dot clocks relate differently to the subcarrier. On the
  SNES the 3-pixel period gives the characteristic Snes9x look in the Zelda/Chrono shots.
- Faint horizontal lines (the Snes9x shots): a separate scanline option, not part of the NTSC
  model. In our pipeline it belongs to the CRT pass (a proper beam profile rather than
  Snes9x's "darken every other row by 12.5%").

## Target design

### The boundary (in the frontend; one small change in the PPU's output stage)

```
            jfc.system.Snes  (unchanged)
                   ^
                   |  runFrame(), joypads().setPressed(), apu().drainSamples(),
                   |  ppu().frameBuffer()/visibleLines(), cartridge() for saves
                   |
   jfc.frontend.EmulationSession   <- extracted from today's EmulatorWindow
     - emulation thread, frame pacing (audio blocks), SRAM saves, fps counter
     - each frame: buttons = InputSource.poll(); snes.runFrame(); VideoSink.submit(frame)
                   |                              |
          InputSource (interface)        VideoSink (interface)
                   |                              |
     +-------------+-----------+         +--------+-----------------+
     | SwingKeyboard           |         | SwingVideo (today's      |
     | GlfwInput (kbd+gamepad) |         |   window, kept as         |
     | Gamepads (winmm, legacy)|         |   fallback / --swing)     |
     +-------------------------+         | GlVideo (LWJGL, shaders)  |
                                         +---------------------------+
```

- `Frame` is a small record: `int[] pixels, int width, int height, long number`. The session
  copies `ppu().frameBuffer()` into one of three buffers (triple buffering) and hands the
  newest to the sink, so the render thread never waits on the emulator and never reads a
  half-drawn frame. This replaces today's `imageLock`.
- **The core stops at what the PPU computes: 15-bit colour plus master brightness.** Each
  pixel is one `int`, `brightness << 15 | bgr15` (19 bits). Turning that into monitor RGB
  moves to the frontend (`jfc.video.SnesColor`, today's lookup table), used by Swing, PNG
  screenshots and the future debugger. The NTSC port reads the colour and brightness
  directly (applying brightness in 5-bit steps as Snes9x does), and the GPU path uploads the
  raw values as an integer texture (`GL_R32UI`) and does the maths in floating point. The
  24-bit buffer couldn't serve this: below full brightness it no longer holds the exact SNES
  colour. This is the plan's only change to emulation code (~20 lines in `Renderer`/`Ppu`
  plus `RendererTest`/`EffectsTest`).
- Audio stays on `javax.sound` (`AudioOutput`) and keeps pacing the emulator. OpenAL isn't
  needed.

### Threads with GLFW

GLFW wants its window and event handling on the main thread (mandatory on macOS, good
practice everywhere). So with `GlVideo`:
- **Main thread**: GLFW event loop, key/gamepad callbacks, render at the monitor's vsync,
  always drawing the newest finished frame.
- **Emulation thread**: unchanged role (paced by audio).
- 60.10 Hz emulation on a 60 Hz monitor shows one repeated frame about every 10 s. Fine to
  start with; syncing to vsync with audio resampling is a later option.

### Shader pipeline (GPU)

OpenGL 3.3 core (works on Windows/Linux, and macOS's 4.1 core). Multi-pass, each pass a
small, heavily commented GLSL file in `src/main/resources/shaders/`, chained through
framebuffer textures:

| Pass | Input -> output | What it models |
|------|-----------------|----------------|
| 0 upload | 256x224 `GL_R32UI` texture (colour + brightness) | the PPU's digital output |
| 1 encode | -> ~683x224 single-channel float (4 samples per subcarrier cycle) | the S-ENC chip: RGB -> YIQ, chroma modulated onto the subcarrier with the per-line/per-frame phase; composite = Y + C (S-Video keeps Y and C apart) |
| 2 decode | -> ~602x224 RGB | the TV: luma low-pass/notch, chroma demodulation and band-limit, YIQ -> RGB with the decoder matrix, optional two-phase average (merge fields) |
| 3 CRT | -> window size | 4:3 aspect, scanline beam profile (brightness-dependent width), optional shadow/slot/aperture mask, display gamma, optional slight curvature |

Presets mirror Blargg's (composite, S-Video, RGB, monochrome, plus "RF" = composite without
field merge), with the same knobs (`sharpness`, `resolution`, `artifacts`, `fringing`,
`bleed`, `hue`, `saturation`, `gamma`). A "sharp pixels" chain (pass 0 + integer/aspect
scaling only) stays available. Hotkeys cycle presets and toggle the CRT pass.

### Input with GLFW

The GLFW window owns keyboard focus, so Swing's `KeyEventDispatcher` can't follow; keyboard
input has to move with the window. Gamepads then come almost for free and become
cross-platform:
- `glfwGetGamepadState` with SDL's controller database (`glfwUpdateGamepadMappings`) gives
  standard button names for known pads.
- The raw `glfwGetJoystickButtons` path plus our existing `GamepadMapping`
  (`gamepad.properties`) covers anything not in the database, including the Buffalo pad
  (0583:2060) if needed. `--gamepad-test` moves to it.
- Hot-plug via `glfwSetJoystickCallback` replaces the 2-second rescan.
- `WinmmJoysticks` **stays** for the Swing window: GLFW's joystick functions need GLFW's
  main-thread setup, which doesn't mix well with Swing. Both gamepad readers feed the same
  `InputSource` interface and share `GamepadMapping`.

## Sub-steps (one reviewable commit each)

**10a. Frontend boundary (no visible change).**
First, as its own small commit: the PPU outputs colour + brightness and `jfc.video.SnesColor`
converts to RGB. Then extract `EmulationSession` from `EmulatorWindow`; add `VideoSink`,
`InputSource`, `Frame`; make the Swing window implement them. Triple-buffer handoff. Check:
all tests pass, the game plays exactly as before (user check), `--screenshot` unchanged.

**10b. NTSC filter in plain Java (CPU), usable in Swing right away.**
`jfc.video.ntsc`: a readable Java port of `snes_ntsc` (decision 2), under its own LGPL
header with credit to Shay Green, with the presets. Runs per frame on the emulation side of the boundary (602x224 output, a few
million multiply-adds, a millisecond or two). Adds `--screenshot ... --video composite`,
and a hotkey in the Swing window. Why first:
- It's the readable, educational version of the signal chain, and it's testable headless:
  PNGs I can read and compare to the user's Snes9x shots (Zelda title, Chrono Trigger) before
  any GPU work.
- It becomes the **reference** the GLSL port is diffed against in 10d.
- The user gets the NTSC look early, without new dependencies.
Tests: a flat colour field decodes back to (nearly) itself; RGB preset ~ identity; the
artifact pattern has a 3-pixel period; per-line phase rotates 120 degrees; the frame phase
cycles 0, 1, 2 as in Snes9x.

**10c. LWJGL window (sharp pixels only).**
Add LWJGL (BOM; `lwjgl`, `lwjgl-glfw`, `lwjgl-opengl`, with natives for Windows plus Linux/
macOS via Maven profiles). `GlVideo`: GLFW window, vsync, texture upload, aspect-correct
scaling, fullscreen toggle, keyboard via GLFW. Swing stays behind `--swing`. Packaging:
the jar plus `target/lib/`, with a manifest `Class-Path` so `java -jar` keeps working
(decision 6; `jpackage` can make an installer for the open-source release). Update the "no runtime
dependencies" rule in CLAUDE.md/development.md to "LWJGL only, frontend only".

**10d. Shader pipeline + NTSC passes.**
Pass framework (load GLSL from resources, FBO chain, uniforms for presets), then passes 1-2.
Verification: an offscreen test (hidden GLFW window) renders a frame through the GPU chain
and compares it with the 10b Java reference within a tolerance; plus the user's eye.

**10e. CRT pass.** Beam profile, mask options, gamma, 4:3, curvature off by default.

**10f. GLFW gamepads.** Gamepad database + raw fallback, hot-plug, `--gamepad-test` on GLFW.
User check with the Buffalo pad.

Rough size: 10a and 10c are small; 10b and 10d are the substance; 10e/10f are medium.

## Impact on emulation code

One small change: the PPU's frame buffer holds colour + brightness instead of RGB (see
above). Otherwise the frontend uses only accessors that exist today (`runFrame`, `joypads`,
`apu().drainSamples`, `ppu().frameBuffer/visibleLines`, `cartridge`). The frame counter and
burst phase live in the frontend. The Milestone 8/9 work in the other session can continue in
parallel; the only shared files are `Main.java` (new options) and the docs. This work should
live on its own branch (or worktree) and merge when 10a is ready.

Interaction with Milestone 9 (debugger): Swing windows and a GLFW window in one process work
on Windows and Linux; on macOS they fight over the main thread. If the debugger stays in
Swing (the M9 recommendation), on macOS it would open with the Swing video sink. Worth
deciding together with M9's open decision 4.

## Decisions (the user, 2026-11-15)

1. **Java version first** (10b before any GPU work).
2. **Port `snes_ntsc`** for now (the project will be open-sourced later; the port stays LGPL
   in its own package, and a clean-room version may replace it someday).
3. **Frame phase: do what Snes9x does** (cycle 0, 1, 2 per frame; field merge on in the
   Composite, S-Video and RGB presets).
4. **Keep the Swing window** as `--swing`. The frontend layer lets any number of video and
   input implementations sit on the same core.
5. **Keep winmm gamepads** for Swing mode; GLFW gamepads in LWJGL mode.
6. **Packaging**: jar plus `lib/` (my call; the user had no preference).
7. **The core outputs 15-bit colour + brightness**; RGB conversion lives in the frontend.
