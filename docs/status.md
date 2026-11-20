# Project status

Last updated: 2026-11-20: the project was renamed from JFC to **Snesible** (folder
`C:\dev\projects\snesible`, package `snesible`, jar `snesible-0.1.0-SNAPSHOT.jar`, test
property `snesible.cpuTests`). Older commit messages still say JFC.

2026-11-17 (Milestone 9a built: the memory heat map). Milestone 10 is merged into `main` after the user's play-tests (the
frontend code is explained in `frontend.md`), and its `m10-video` worktree and branch are
removed. Milestone 11 (enhanced audio) was added as a plan. Milestone 9 was re-planned as an
ICU64-style visual memory debugger (`m9-debugger-plan.md`).

## Milestones

| # | Milestone | Commit | State |
|---|-----------|--------|-------|
| 1 | Maven skeleton, cartridge loading | `020fe43` | done |
| 2 | 65816 core (5.12M SingleStepTests pass) | `3a5751c` | done |
| 3 | Bus, timing, DMA, interrupts, CPU I/O, APU stub | `6b40bb6` | done |
| 4 | PPU backgrounds and sprites, Swing window, keyboard | `4aaed5f` | done |
| - | Scroll jitter fix, focus-independent keyboard | `c854205` | done |
| 5 | Battery saves (.srm), gamepad via winmm/FFM | `4278371` | done |
| 6 | HDMA, windows, color math, Mode 7, mosaic | `42fead7` | done |
| 7 | SPC700 (256k tests pass), DSP, sound output | `aa19df1` | done; the user confirmed audio "seems great" |
| - | BG line numbering (+1) and render at dot 22 (the FastROM action game's HUD) | see git log | done |
| 8 | Coprocessors: SuperFX (the GSU-1 and GSU-2 games) and SA-1 (the SA-1 game) | see git log | done; the user played all three ("pretty much perfectly") |
| - | Offset-per-tile in modes 2 and 4 (the GSU-1 game's horizon); GSU background ROM/RAM buffers | see git log | done |
| 9 | Visual memory debugger, ICU64-style: heat map, inspect and edit RAM, tile and sprite viewer, maybe a value scanner (plan: `m9-debugger-plan.md`) | - | 9a (heat map) built and play-tested by the user (`--debug`); maximized-window slowdown fixed, to re-check; 9b-9d planned |
| 10 | Move video and input to LWJGL; CRT/NTSC shaders (plan: `m10-shader-plan.md`) | see git log | done, merged 2026-11-15: 10a (frontend boundary, 15-bit frame buffer), 10b (Java NTSC filter), 10c (package layout, OpenGL window, frame pacing), 10d (NTSC on the GPU), 10f (GLFW gamepads), and from 10e the beam-profile scanlines (the user likes them). Optional extras (shadow mask, 4:3 sharp pixels, NTSC settings) are listed in `frontend.md`; curvature was declined |
| 11 | Enhanced audio, SNESamp-style: higher output rate, sinc/cubic interpolation, per-voice anti-aliasing, float mixing, stereo controls, Hardware/Quality presets (plan: `m11-audio-plan.md`) | - | planned; open decisions in the plan |

## What the user has confirmed by playing

- The LoROM platformer: plays well (keyboard and gamepad). Jitter fixed. Saves work at its
  save points.
- The FastROM action game: plays well in the first minutes.
- Gamepad: all buttons map correctly with the defaults.
- Audio: sounds great.
- A minor HUD glitch in the FastROM action game (the minimap's last line phasing in and out) was fixed by
  the BG line-numbering and render-timing changes. The user said per-pixel accuracy isn't worth
  chasing for glitches like this: make a moderate, scanline-level effort.

## Known simplifications and gaps

Each is documented where it lives in the code.

**CPU and system**
- Interrupts are checked at instruction boundaries, not during the last cycle.
- All lines are 1364 cycles (no long or short dots, no interlace line-length change).
- Multiply/divide results are available instantly (hardware takes 8/16 CPU cycles).
- Timer IRQs fire on the exact programmed dot (hardware is a few cycles later).
- DMA reads of blocked A-bus addresses return 0 instead of open bus.
- The PPU1/PPU2 open bus isn't modeled; write-only PPU registers read as CPU open bus.

**PPU**
- Scanline renderer (lines drawn at dot 22): writes during the visible part of a line only
  take effect on the next line. Writes in horizontal blank are handled correctly.
- Not implemented: hi-res modes 5/6 and pseudo-hi-res, interlace, offset-per-tile in mode 6
  (modes 2 and 4 have it), direct color, and the sprite interlace and range quirks of X=-256.
- Mosaic's vertical counter is based on the frame top, not on when the register was written.

**APU**
- The DSP produces each sample in one step, not through the real 32-step pipeline.
- Gaussian interpolation uses a computed curve (sigma 0.635), not the hardware table.
- KON/KOF are polled every other sample; the echo length is re-read when the position wraps.
- The boot ROM is a clean-room replacement (same protocol, different timing).

**Other**
- The Swing window's gamepads are Windows-only (winmm). Pads are found at startup and
  when the user presses F9, as in Snes9x. Each search of an empty winmm slot stalls joystick
  calls ~160 ms; the old automatic search every 2 s froze the game that often. The user
  asked for on-demand only, since many SNES games are latency-sensitive.
- Fixed in 10c: audio-paced frames used to arrive up to ~35 ms apart (the sound device frees
  buffer space in ~30 ms chunks). Frames are now paced by the display or a steady clock and
  the sound adapts (dynamic rate control); measured gaps 16.0-17.5 ms in both windows.

**NTSC on the GPU (10d) and GLFW gamepads (10f)**
- The OpenGL window's NTSC modes run a GLSL port of `snes_ntsc`'s filtering step; the output
  equals the Java filter's for every pixel (`GlRendererTest`). Checked in the real window:
  composite, S-Video and RGB at a steady 61 fps; a mode switch costs one ~25 ms frame.
- GLFW sees the user's Buffalo pad as a raw joystick (8 buttons, 2 axes; not in SDL's
  database), so it uses `GamepadMapping` with the defaults the user confirmed under winmm. It's
  detected as controller 1, and the user confirmed its buttons are right in the OpenGL
  window. `--gamepad-test` reads pads as the OpenGL window does (GLFW); with `--swing`, as
  the Swing window does (winmm).
- Optional future features (the user's list, not planned): a CRT shadow mask, 4:3 pixels
  in sharp mode, adjustable NTSC settings. Screen curvature was declined. Details in
  `frontend.md`, section 8.
- The refresh measurement once came out wrong right after the window appeared (swaps not
  waiting yet); it now skips the first 20 swaps, waits for each with `glFinish`, and
  re-measures after 2 s if vsync still looks off. Three launches then measured 143.65-144.02 Hz.

**OpenGL window (10c)**
- Verified on the user's machine (two 2560x1440 144 Hz G-Sync monitors, NVIDIA), driving it
  from scripts: picks the steady clock at 143.9 Hz, frame gaps 16.0-17.5 ms windowed and
  16.3-17.0 ms fullscreen, audio buffer settling at ~50% with nothing dropped, F2/F11 work,
  GPU colors identical to `SnesColor` (`GlRendererTest`). The user has since played a few games with it ("everything seems fine").
- Fullscreen has two keys (the user's choice): F11 = borderless, a window 1 px larger than
  the screen on each side, which switches instantly; Alt+Enter = exclusive, lowest latency.
  The user confirmed both work ("instant and looks perfect"). Exclusive fullscreen blanks the screen for ~4 s while the G-Sync
  monitor re-syncs, and so does a borderless window that exactly covers the screen; one 1 px
  short leaves the taskbar in front. Details in `frontend.md`. The user confirmed borderless
  was "very quick" in a test program; checked in the game with screen captures (no taskbar,
  picture fills the visible screen, frame gaps at most ~30 ms around the switch).
- The user has play-tested the OpenGL window (2026-11-15): "everything seems fine", on a few
  games.
- Scanlines (F3) in the OpenGL window are now CRT beams with brightness-dependent width, in
  every picture mode (10e's first part; the user asked for "improved scanlines"). They stay
  fixed from frame to frame, as 240p does. The user asked about alternating even and odd
  lines, which imitates 480i (interlace mode only). Checked with `GlRendererTest` and
  renders of the LoROM platformer. The user: "look better than the old ones", no tuning needed.
- `--refresh 120` in fullscreen still measured 144 Hz: the NVIDIA driver apparently keeps the
  highest refresh rate ("Preferred refresh rate: Highest available"). So the locked-to-vsync
  path hasn't run on real hardware yet; `PacingDecisionTest` covers the decision, and the
  loop itself is a few lines.
- G-Sync in a window needs NVIDIA's "Enable G-SYNC for windowed and full screen mode";
  whether it's on here is unknown. Fullscreen is the reliable case.
- Coprocessors: SuperFX and SA-1. DSP-n, S-DD1 and others aren't planned.

**SuperFX (Milestone 8)**
- Verified headless: the GSU-1 game reaches the title, controls screen (a 3D ship) and
  training flight; the GSU-2 game reaches the title, file select and first level, and its message box
  text (drawn by the GSU in 2bpp) is correct. Both run about 6x real time.
- Fixed after the user spotted it: the GSU-2 game's message box border had see-through holes,
  and its intro storybook text box showed stripes. Both had one cause, which was in the HDMA
  code, not the SuperFX: channels enabled mid-frame never ran (see hardware-notes.md, HDMA).
- The user played all three games in the window: "everything plays pretty much perfectly"
  except two issues in the GSU-1 game:
  - Fixed: the background's horizon sat lower than the 3D scene's vanishing point. The
    game uses mode 2 offset-per-tile, which wasn't implemented (see hardware-notes.md, PPU).
  - Improved, not closed: the squadron launch scene after the briefing ran slower than in
    Snes9x (the user compared; the briefing itself was fine). The GSU's ROM-buffer fetches and
    RAM writes now run in the background as on the chip, which took the scene from ~8.9 to
    ~10.4 pictures/s. The user's Snes9x recording shows ~11.8. The user said this is a known
    hard problem, that Snes9x may run the GSU faster than real hardware, and that ~15 fps is
    normal for this game, so this is just noted. Details in hardware-notes.md (SuperFX).
    Comparing against bsnes, or a real-hardware recording, would show which side is off.
- Simplifications: plotting writes pixels straight to RAM (same result as the pixel cache);
  a partly filled pixel-cache flush isn't charged its extra read; the GSU doesn't stall when it lacks the ROM/RAM bus;
  only CPU writes mark the battery RAM dirty (the GSU's frame-buffer writes don't).

**SA-1 (Milestone 8, second half; the user asked for it right after the SuperFX)**
- Verified headless on the SA-1 game: the publisher's logo, the full attract demo (several
  scenes and an isometric battle), title, file select, name entry, the opening story, and
  walking through the first area. Music plays (checked by
  recording audio). About 5x real time.
- After skipping the intro with Start, the title and file select are silent for about 6 s
  before the story music starts. The user confirmed the real game is silent there too.
- Not implemented (the SA-1 game doesn't use them in the parts tested; a temporary log
  showed it only initializes the timer to off and never starts a character-conversion DMA):
  the SA-1 H/V timer and its IRQ, character-conversion DMA (types 1 and 2), BW-RAM and I-RAM
  write protection, and ROM/BW-RAM bus-conflict waits.
- All BW-RAM writes (console or SA-1) mark the save dirty, because the game keeps both work
  data and saves in BW-RAM. Saves are throttled to one per second by the window.

## Milestone 9 plan: visual memory debugger

Planned with the user on 2026-11-17; the full plan is `m9-debugger-plan.md`. In short: an
ICU64-style debugger in two packages, `snesible.debug` (model and the `DebugMain` entry point, a
subclass of `Main`) and `snesible.ui.debug` (raster UI in its own window). The core only gets
`Observable.wrapXxx(...)` at five call sites (no-ops unless a debugger installed an observer)
and a few read-only getters. Sub-milestones: 9a heat map, 9b inspect and edit (work RAM and
cartridge RAM only), 9c tile and sprite viewer, 9d (maybe) value scanner. It replaces the older
plan (disassemblers, tracing, breakpoints, Swing window).

**9a is built** (2026-11-17): `java -jar target/snesible-0.1.0-SNAPSHOT.jar --debug <rom>` opens the
heat map beside the game (F12 toggles it); `--debug-screenshot` renders it headless. Checked
with headless PNGs of the LoROM platformer, the GSU-1 game and the SA-1 game and short runs of both windows. The
user's first play-test: it works, but the game dropped frames in F11 fullscreen with the
debugger maximized on the other monitor; fixed (a pixel buffer object upload, ~8.4 to ~0.7 ms;
see `frontend.md`), not yet re-checked by the user. What was built, and what's ready for 9b, is at the end of
`m9-debugger-plan.md`.
