# Architecture

JFC is an educational SNES emulator. Each package mirrors a piece of real hardware, and clarity
beats speed. This file explains how the pieces fit together; the code's Javadoc explains each
piece in detail.

## Package map

| Package         | Hardware / role | Key classes |
|-----------------|-----------------|-------------|
| `jfc`           | Command-line entry points and headless tools | `Main`, `Screenshot`, `AudioRecording`, `InputScript`, `GamepadTest` |
| `jfc.cartridge` | Cartridge: ROM, SRAM, header, LoROM/HiROM mapping | `Cartridge`, `CartridgeHeader`, `HeaderLocator`, `LoRomMapper`, `HiRomMapper`, `Mirroring` |
| `jfc.cpu`       | 65816 core (inside the Ricoh 5A22) | `Cpu`, `Registers`, `StatusFlags`, `EffectiveAddress`, `CpuBus` |
| `jfc.cpu.io`    | The rest of the 5A22: NMI/IRQ, multiply/divide, joypad ports | `InterruptControl`, `ArithmeticUnit`, `JoypadPorts` |
| `jfc.dma`       | 8-channel DMA and HDMA | `DmaController`, `DmaChannel` |
| `jfc.bus`       | Memory map and per-access timing | `SnesBus` |
| `jfc.ppu`       | Picture processor | `Ppu` (registers and memories), `Renderer` (pipeline), `BackgroundLayer`, `SpriteLayer`, `Mode7Layer`, `WindowMasks`, `Tiles`, `LayerLine` |
| `jfc.apu`       | Audio unit | `Apu` (RAM, I/O, timers, sync), `Spc700`, `Dsp`, `BootRom`, `ApuPorts` |
| `jfc.system`    | The console | `Snes` (wiring and frame loop), `MasterClock`, `WorkRam` |
| `jfc.ui`        | Window, sound, frame pacing, keyboard | `EmulatorWindow`, `AudioOutput` |
| `jfc.ui.input`  | Gamepads | `Gamepads`, `WinmmJoysticks`, `GamepadMapping`, `JoystickState` |

## Timing model: "the CPU drives everything"

- Time is measured in **master cycles** (21.477 MHz NTSC). `MasterClock` tracks the beam:
  1364 master cycles per line, 262 lines per frame (312 on PAL), 4 master cycles per dot.
- The CPU cores never count cycles. **Every CPU cycle is one bus call**: `read`, `write` or
  `idle` (`CpuBus` for the 65816, `Spc700Bus` for the SPC700), made in the datasheet's order.
  The SingleStepTests suites verify that sequence. The bus assigns each cycle its cost.
- `SnesBus` charges each access 6, 8 or 12 master cycles depending on the address (FastROM
  makes banks $80-$FF 6 cycles after `$420D` bit 0). Idle cycles cost 6. Each access calls
  `MasterClock.advance()` **before** performing it.
- `MasterClock.advance()` reports progress to its `Listener` (`Snes`) as `onLineStart(line)`
  and `onBeamMoved(line, fromH, toH)`. A span never crosses a line, so "did the beam pass
  position P?" is `fromH <= P && P < toH`.
- Things that steal CPU time from inside those callbacks (HDMA) call `MasterClock.stall()`,
  which folds the cycles into the advance in progress. The DRAM refresh (40 cycles at
  h = 538 every line) is handled inside `MasterClock` the same way.
- General DMA runs synchronously inside the `$420B` write and advances the clock itself
  (8 cycles per byte plus setup).
- There's no scheduler: the CPU's pace is the system's pace (the NES-style "catch-up" design).

### Per-line events (in `Snes`)
- `onLineStart(0)`: HDMA tables restart (`DmaController.initHdma()`).
- `onLineStart(n)`: the PPU draws **picture line n-1** (beam line 0 is never shown).
- `onLineStart(vblankStart)` (225, or 240 with overscan): NMI flag/NMI, auto joypad read,
  OAM address reload, `frameComplete`, so `runFrame()` returns at the start of vblank.
- `onBeamMoved` at h = 1104 on lines 0..vblankStart-1: one line of HDMA. Its register writes
  land before the next line's render, which is exactly when hardware HDMA lands.
- `InterruptControl.onBeamMoved` fires H/V timer IRQs.

### Scanline renderer consequence
A whole picture line is drawn at the start of its beam line, from the registers at that moment.
CPU writes in the middle of a line show up on the next line. HDMA is unaffected (it runs in
hblank, before the render).

## APU synchronization (catch-up)

The APU is a separate computer with its own clock (SPC700 at 1.024 MHz; DSP sample every 32
SPC cycles = 32 kHz). `Apu.catchUp()` runs the SPC700 until
`spcCycles >= masterCycles * 1_024_000 / masterHz`. It runs:
- whenever the main CPU reads or writes `$2140-$2143` (the only way to observe the APU),
- at the end of every `Snes.runFrame()`, so each frame's audio is ready to collect.

The SPC700 side of the `Apu` is an inner class (`SpcSide implements Spc700Bus`), because
`ApuPorts.read(int)` and `Spc700Bus.read(int)` have the same signature but different meanings.
Each SPC bus cycle calls `tick()`: timers advance, and every 32nd tick the DSP makes a sample,
which goes into `Apu`'s sample buffer (drained by `drainSamples`).

## Rendering pipeline (`Renderer.renderLine`)

1. Draw each layer used on the main or sub screen into its own `LayerLine` (color index +
   priority per pixel): BGs via `BackgroundLayer` (modes 0-6) or `Mode7Layer`, then sprites via
   `SpriteLayer`. Mosaic is applied here (vertical: render the block's first line; horizontal:
   copy each block's first pixel).
2. Compute window masks (`WindowMasks`) for BG1-4, sprites and the color window.
3. Resolve the **main** (TM/TMW) and **sub** (TS/TSW) screens using the per-mode priority
   tables in `Renderer` (`PRIORITY_ORDERS`, `MODE1_BG3_FRONT_ORDER`).
4. Color math (CGWSEL/CGADSUB/COLDATA), in 15-bit color.
5. Master brightness and 15-bit to RGB via a precomputed table.

## Frame loop and threads (`EmulatorWindow`)

- **Emulation thread** (non-daemon, so it can write the final save on exit): poll gamepads, set
  joypad state, `runFrame()`, copy the frame buffer into the image under a lock, drain the
  audio and play it. `AudioOutput.play` blocks while the sound card's ~64 ms buffer is full,
  and **that's what paces the emulator**. Without a sound device it falls back to sleeping
  until each frame's deadline.
- **Swing event thread**: paints the image and turns keys into buttons through a global
  `KeyEventDispatcher`, stored in an `AtomicInteger`.
- Battery SRAM is saved about once a second when changed, and on exit (atomic temp-file move).

## Construction order (`Snes` constructor)

The CPU needs a bus, the bus needs `InterruptControl`, and `InterruptControl` needs the CPU.
So `InterruptControl` is created first and then `interrupts.connect(cpu)` once the CPU exists.
`DmaController` gets its bus through `setBus`.

## Planned direction

Later, video and input move to **LWJGL** (cross-platform gamepads, plus the chance of CRT/NTSC
shaders). Keep the boundaries small: `EmulatorWindow`/`AudioOutput` for video and sound,
`Gamepads`/`WinmmJoysticks` for input. The core (`jfc.system` and below) must not depend
on Swing.
