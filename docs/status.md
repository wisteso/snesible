# Project status

Last updated: 2026-11-10, after Milestone 7.

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
| 7 | SPC700 (256k tests pass), DSP, sound output | `aa19df1` | done; the user is listening ("so far so good") |
| 8 | Debug and state-viewer tools | - | **next** |
| later | Move video and input to LWJGL; CRT/NTSC shaders | - | planned |

## What the user has confirmed by playing

- SMW: plays well (keyboard and gamepad). Jitter fixed. Saves work at SMW's save points.
- Super Metroid: plays well in the first minutes.
- Gamepad: all buttons map correctly with the defaults.
- Audio: being tested, positive so far.

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
- Scanline renderer: mid-line register writes (outside HDMA) only take effect on the next line.
- Not implemented: hi-res modes 5/6 and pseudo-hi-res, interlace, offset-per-tile (modes
  2/4/6), direct color, and the sprite interlace and range quirks of X=-256.
- Mosaic's vertical counter is based on the frame top, not on when the register was written.

**APU**
- The DSP produces each sample in one step, not through the real 32-step pipeline.
- Gaussian interpolation uses a computed curve (sigma 0.635), not the hardware table.
- KON/KOF are polled every other sample; the echo length is re-read when the position wraps.
- The boot ROM is a clean-room replacement (same protocol, different timing).

**Other**
- Gamepads are Windows-only (winmm) until the LWJGL move.
- No coprocessor support (SA-1, SuperFX, DSP-1, ...). None of the user's 4 ROMs need it; the user
  can supply other ROMs if a target needs one.

## Ideas for Milestone 8 (debug and state-viewer tools)

The user's stated goal after the emulator works: tools for viewing system state, for
educational value. Hooks that already exist:
- `Cpu.setInstructionListener(Registers)`: tracing, breakpoints, PC histograms.
- `Ppu.peekVram/peekCgram/peekOam`, package-private layer state, `bgMode()`.
- `Snes` accessors: `cpu()`, `bus()`, `clock()`, `ppu()`, `wram()`, `joypads()`,
  `cartridge()`, `apu()`; `Apu.cpu()` exposes the SPC700 registers.

Candidates: a 65816 disassembler and trace log; CPU and SPC700 register panels; memory viewer
(WRAM, VRAM, ARAM); tile/tilemap/palette/OAM viewers; per-layer toggles; DSP voice meters;
an HDMA/DMA activity log; step/pause/frame-advance. Keep tools out of the core packages
(for example a `jfc.debug` package) and the core free of Swing.
