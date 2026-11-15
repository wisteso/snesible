# Project status

Last updated: 2026-11-11, Milestone 8 (SuperFX and SA-1).

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
| - | BG line numbering (+1) and render at dot 22 (Super Metroid HUD) | see git log | done |
| 8 | Coprocessors: SuperFX (Star Fox, Yoshi's Island) and SA-1 (Super Mario RPG) | see git log | done; the user played all three ("pretty much perfectly") |
| - | Offset-per-tile in modes 2 and 4 (Star Fox horizon); GSU background ROM/RAM buffers | see git log | done |
| 9 | Debug and state-viewer tools (plan below) | - | planned |
| 10 | Move video and input to LWJGL; CRT/NTSC shaders (plan: `m10-shader-plan.md`) | branch `m10-video` | in progress: 10a (frontend boundary, 15-bit frame buffer) and 10b (Java NTSC filter) done; 10c-10f (LWJGL window, GPU shaders, CRT pass, GLFW gamepads) to do |

## What the user has confirmed by playing

- SMW: plays well (keyboard and gamepad). Jitter fixed. Saves work at SMW's save points.
- Super Metroid: plays well in the first minutes.
- Gamepad: all buttons map correctly with the defaults.
- Audio: sounds great.
- A minor HUD glitch in Super Metroid (the minimap's last line phasing in and out) was fixed by
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
- Gamepads are Windows-only (winmm) until the LWJGL move. Pads are found at startup and
  when the user presses F9, as in Snes9x. Each search of an empty winmm slot stalls joystick
  calls ~160 ms; the old automatic search every 2 s froze the game that often. The user
  asked for on-demand only, since many SNES games are latency-sensitive.
- With the window, audio-paced frames occasionally arrive ~35 ms apart (the sound device frees
  buffer space in ~30 ms chunks), showing one repeated frame. Planned fix in 10c: pace to
  the monitor's vsync and keep the sound buffer level with dynamic rate control.
- Coprocessors: SuperFX and SA-1. DSP-n, S-DD1 and others aren't planned.

**SuperFX (Milestone 8)**
- Verified headless: Star Fox reaches the title, controls screen (3D Arwing) and training
  flight; Yoshi's Island reaches the title, file select and first level, and its message box
  text (drawn by the GSU in 2bpp) is correct. Both run about 6x real time.
- Fixed after the user spotted it: Yoshi's Island's message box border had see-through holes,
  and its intro storybook text box showed stripes. Both had one cause, which was in the HDMA
  code, not the SuperFX: channels enabled mid-frame never ran (see hardware-notes.md, HDMA).
- The user played all three games in the window: "everything plays pretty much perfectly"
  except two Star Fox issues:
  - Fixed: the background's horizon sat lower than the 3D scene's vanishing point. Star Fox
    uses mode 2 offset-per-tile, which wasn't implemented (see hardware-notes.md, PPU).
  - Improved, not closed: the "scramble" launch scene after the briefing ran slower than in
    Snes9x (the user compared; the briefing itself was fine). The GSU's ROM-buffer fetches and
    RAM writes now run in the background as on the chip, which took the scene from ~8.9 to
    ~10.4 pictures/s. The user's Snes9x recording shows ~11.8. The user said this is a known
    hard problem, that Snes9x may run the GSU faster than real hardware, and that ~15 fps is
    normal for Star Fox, so this is just noted. Details in hardware-notes.md (SuperFX).
    Comparing against bsnes, or a real-hardware recording, would show which side is off.
- Simplifications: plotting writes pixels straight to RAM (same result as the pixel cache);
  a partly filled pixel-cache flush isn't charged its extra read; the GSU doesn't stall when it lacks the ROM/RAM bus;
  only CPU writes mark the battery RAM dirty (the GSU's frame-buffer writes don't).

**SA-1 (Milestone 8, second half; the user asked for it right after the SuperFX)**
- Verified headless on Super Mario RPG: Square logo, the full attract demo (garden, Koopa
  Clown Car, beanstalk, an isometric battle), title, file select, name entry, the opening story,
  the walk over the bridge into Bowser's Keep and around its hall. Music plays (checked by
  recording audio). About 5x real time.
- After skipping the intro with Start, the title and file select are silent for about 6 s
  before the story music starts. The user confirmed the real game is silent there too.
- Not implemented (Super Mario RPG doesn't use them in the parts tested; a temporary log
  showed it only initializes the timer to off and never starts a character-conversion DMA):
  the SA-1 H/V timer and its IRQ, character-conversion DMA (types 1 and 2), BW-RAM and I-RAM
  write protection, and ROM/BW-RAM bus-conflict waits.
- All BW-RAM writes (console or SA-1) mark the save dirty, because the game keeps both work
  data and saves in BW-RAM. Saves are throttled to one per second by the window.

## Milestone 9 plan: debug and state-viewer tools

The user's stated goal after the emulator works: tools for viewing system state, for
educational value. This plan was agreed in outline (the user inserted SuperFX as M8 first);
the open decisions at the bottom still need the user's answer.

**Shape**
- A tabbed "Debugger" window in a new `jfc.debug` package (Swing), opened with F12. The core
  only gets small hooks and stays free of Swing and of any dependency on `jfc.debug`.
- A `DebugController` owns run / pause / step. It uses `Cpu.setInstructionListener`: the
  listener blocks the emulation thread when a breakpoint hits or a step ends (audio just
  underruns to silence while paused).
- Viewers never read live state from the Swing thread. Between frames (or while paused) the
  emulation thread copies a snapshot of what the open viewers need (VRAM, CGRAM, OAM,
  registers, ...); views draw from it, refreshing about 10 times a second while running.
- Viewers use side-effect-free `peek` accessors, so watching never changes what the game does
  (no VRAM read-buffer or latch side effects).

**Sub-steps (one reviewable commit each)**
- **9a. Execution control and CPU view.** A table-driven 65816 disassembler (operand size from
  M/X). Test idea: for every non-branch SingleStepTests case, the disassembled length must
  equal the PC delta. Register and flag panel, disassembly around PC, pause / run / step
  instruction / step scanline / step frame, PC breakpoints, read/write watchpoints. A headless
  `--trace <rom> <frames> <out.txt>` in a common trace format, to diff against other emulators.
- **9b. Memory and graphics viewers.** Hex viewer for WRAM, VRAM, CGRAM, OAM, ARAM, SRAM, ROM
  (and SuperFX RAM) with change highlighting; palette viewer; tile viewer (2/4/8bpp, palette
  choice); tilemap viewer per BG with the scroll viewport drawn on it; OAM table with sprite
  previews; decoded PPU state (mode, layers, windows, color math, Mode 7 matrix); per-layer
  main/sub toggles (a small mask in `Renderer`).
- **9c. Event viewer.** A 341x262 beam-position grid with a dot for every register write, DMA,
  HDMA, IRQ and NMI, with hover details; a per-channel DMA/HDMA table. Built on
  `SnesBus.setRegisterWriteListener` (how the Super Metroid HUD split was found).
- **9d. APU.** SPC700 disassembler (tested the same way), registers, stepping; DSP voice panel
  (pitch, volume, ADSR/GAIN, envelope, source, KON), level meters, per-voice mute/solo (a small
  mask in `Dsp`).

**Hooks that already exist**
- `Cpu.setInstructionListener(Registers)`: tracing, breakpoints, PC histograms.
- `SnesBus.setRegisterWriteListener((address, value) -> ...)`: every hardware register write
  from the CPU, DMA or HDMA. Combine it with `clock().v()/dot()` for a beam-position log.
- `Ppu.peekVram/peekCgram/peekOam`, package-private layer state, `bgMode()`.
- `Snes` accessors: `cpu()`, `bus()`, `clock()`, `ppu()`, `wram()`, `joypads()`,
  `cartridge()`, `apu()`; `Apu.cpu()` exposes the SPC700 registers.

**Open decisions (ask the user before starting M9)**
1. One tabbed window (recommended) or one window per tool?
2. Trace-log format: which emulator will the user diff against?
3. Order 9a-9d as above, or graphics viewers first?
4. Keep the tools in Swing after the LWJGL move (recommended), or Dear ImGui via LWJGL?
