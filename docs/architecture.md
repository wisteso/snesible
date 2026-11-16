# Architecture

JFC is an educational SNES emulator. Each package mirrors a piece of real hardware, and clarity
beats speed. This file explains how the pieces fit together; the code's Javadoc explains each
piece in detail. The frontend (`jfc.ui`: windows, pacing, NTSC, shaders, sound, input) has its
own detailed guide in `frontend.md`.

## Package map

Three areas: `jfc.core` is the emulated console and knows nothing about screens or keyboards
(`CoreBoundaryTest` enforces that); `jfc.ui` presents it to a person; `jfc.tools` holds the
headless tools. `jfc.Main` only reads the command line and starts one of them.

| Package         | Hardware / role | Key classes |
|-----------------|-----------------|-------------|
| `jfc`           | Command-line entry point | `Main` |
| `jfc.tools`     | Headless tools: `--info`, `--run`, `--screenshot`, `--record-audio`, `--gamepad-test` | `CartridgeInfo`, `HeadlessRun`, `Screenshot`, `AudioRecording`, `GamepadTest`, `InputScript` |
| `jfc.core.cartridge` | Cartridge: ROM, SRAM, header, LoROM/HiROM mapping | `Cartridge`, `CartridgeHeader`, `HeaderLocator`, `LoRomMapper`, `HiRomMapper`, `Mirroring` |
| `jfc.core.cpu`       | 65816 core (inside the Ricoh 5A22) | `Cpu`, `Registers`, `StatusFlags`, `EffectiveAddress`, `CpuBus` |
| `jfc.core.cpu.io`    | The rest of the 5A22: NMI/IRQ, multiply/divide, joypad ports | `InterruptControl`, `ArithmeticUnit`, `JoypadPorts` |
| `jfc.core.dma`       | 8-channel DMA and HDMA | `DmaController`, `DmaChannel` |
| `jfc.core.bus`       | Memory map and per-access timing | `SnesBus` |
| `jfc.core.ppu`       | Picture processor | `Ppu` (registers and memories), `Renderer` (pipeline), `BackgroundLayer`, `SpriteLayer`, `Mode7Layer`, `WindowMasks`, `Tiles`, `LayerLine` |
| `jfc.core.apu`       | Audio unit | `Apu` (RAM, I/O, timers, sync), `Spc700`, `Dsp`, `BootRom`, `ApuPorts` |
| `jfc.core.coprocessor` | Chips on the cartridge board | `Coprocessor` (interface), `Coprocessors` (factory) |
| `jfc.core.coprocessor.sa1` | SA-1 | `Sa1` (board: both address maps, registers, interrupts, DMA, bit reader, catch-up), `SuperMmc` (ROM banking), `Sa1Arithmetic` |
| `jfc.core.coprocessor.superfx` | SuperFX / GSU | `SuperFx` (board: both address maps, registers, catch-up), `Gsu` (the RISC core), `PlotUnit` (PLOT/RPIX), `GsuBus` |
| `jfc.core.system`    | The console | `Snes` (wiring and frame loop), `MasterClock`, `WorkRam` |
| `jfc.ui`        | The boundary between console and user interface: runs a console in real time (frame loop, saves, input polling), plus what every window shares | `EmulationSession`, `VideoSink`, `InputSource`, `Frame`, `FrameClock`, `PacingMode`, `PlayOptions`, `Controls` |
| `jfc.ui.audio`  | Sound output with dynamic rate control | `AudioOutput`, `AudioResampler` |
| `jfc.ui.video`  | Turning PPU pixels into a picture: plain RGB, or the NTSC filter | `SnesColor`, `VideoFilter`, `VideoMode` |
| `jfc.ui.video.ntsc` | Java port of Blargg's `snes_ntsc` (**LGPL 2.1+**, kept separate) | `SnesNtsc`, `NtscSetup` |
| `jfc.ui.input`  | Gamepad button mapping, shared by every gamepad reader | `GamepadMapping`, `JoystickState` |
| `jfc.ui.input.winmm` | Windows gamepads through winmm.dll for the Swing window (an `InputSource`; F9 rescans) | `Gamepads`, `WinmmJoysticks` |
| `jfc.ui.swing`  | The Swing window (a `VideoSink`) and its keyboard; `--swing`, and the fallback | `SwingWindow`, `SwingKeyboard` |
| `jfc.ui.lwjgl`  | The OpenGL window (GLFW via LWJGL, the default): drawing (NTSC on the GPU), keyboard, gamepads, pacing to the display | `LwjglWindow`, `GlRenderer`, `GlfwKeyboard`, `GlfwGamepads`, `PacingDecision` |

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
- `onLineStart(any)`: the cartridge coprocessor, if any, catches up.
- `onBeamMoved` at **dot 22** (h = 88, `Ppu.FIRST_VISIBLE_DOT`) on beam lines 1..visible: the
  PPU draws **picture line n-1** (beam line 0 is never shown). Drawing at the first visible dot
  rather than at h = 0 means writes made in the early horizontal blank (dots 0-21) apply to
  the line, as on hardware.
- `onLineStart(vblankStart)` (225, or 240 with overscan): NMI flag/NMI, auto joypad read,
  OAM address reload, `frameComplete`, so `runFrame()` returns at the start of vblank.
- `onBeamMoved` at h = 1104 on lines 0..vblankStart-1: one line of HDMA. Its register writes
  land before the next line's render, which is exactly when hardware HDMA lands.
- `InterruptControl.onBeamMoved` fires H/V timer IRQs.

### Scanline renderer consequence
A whole picture line is drawn at its first visible dot, from the registers at that moment.
CPU writes during the visible part of a line show up on the next line. Writes in horizontal
blank (HDMA at dot ~276, and IRQ handlers finishing around dot 0-21) land correctly.

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

## Cartridge coprocessors (`jfc.core.coprocessor`)

`SnesBus` doesn't talk to `Cartridge` directly but to a `CartridgeSlot`: whatever answers in
cartridge space. For a plain game that's the `Cartridge`. For a coprocessor board it's the
`Coprocessor`, which decodes the whole cartridge space itself (its registers, the ROM, its
RAM) and uses `Cartridge` only to hold the ROM/RAM bytes (`romBytes()`, `ramBytes()`) and the
save-file logic (`markRamChanged()`). `Coprocessors.forCartridge` picks the board from the
header's `coprocessorType()`; `Snes` builds it and gives it a `Coprocessor.Host` (master
clock + the cartridge IRQ line, which `InterruptControl.setCartridgeIrq` ORs with the timer
IRQ). This keeps `jfc.core.coprocessor` free of `jfc.core.system`.

Timing is catch-up, like the APU: `catchUp()` runs the chip up to the master clock. It runs on
every console access to the board and at every line start (so the chip's IRQ arrives on time
even while the CPU sits in WAI), and at the end of each frame.

**SuperFX**: `SuperFx` has two address maps: the console's (`read`/`write`) and the GSU's
(inner class `GsuSide implements GsuBus`, same trick as `Apu.SpcSide`). `Gsu.step()` runs one
instruction and returns master cycles. While the GSU owns the ROM (SCMR RON), console ROM
reads return a fixed pattern that makes the interrupt vectors point into work RAM.

**SA-1**: `Sa1` runs a second, unmodified `jfc.core.cpu.Cpu` on its own bus (inner class
`Sa1Side implements CpuBus`, which charges each access to the SA-1's time: 2 master cycles,
4 for BW-RAM). The SA-1's own vectors are served from its CRV/CNV/CIV registers by that bus;
the console's NMI/IRQ vectors can be replaced by SNV/SIV the same way on the console side.
Releasing CCNT's reset bit calls `cpu.reset()`. Catch-up skips straight to the present while
the SA-1 is held in reset, halted, stopped, or in WAI with no interrupt pending, since only a
console access (which happens after catch-up returns) can wake it. Because the console fetches
its own code from cartridge ROM, catch-up runs about once per console instruction, which keeps
the two CPUs closely interleaved.

## Rendering pipeline (`Renderer.renderLine`)

1. Draw each layer used on the main or sub screen into its own `LayerLine` (color index +
   priority per pixel): BGs via `BackgroundLayer` (modes 0-6) or `Mode7Layer`, then sprites via
   `SpriteLayer`. Mosaic is applied here (vertical: render the block's first line; horizontal:
   copy each block's first pixel).
2. Compute window masks (`WindowMasks`) for BG1-4, sprites and the color window.
3. Resolve the **main** (TM/TMW) and **sub** (TS/TSW) screens using the per-mode priority
   tables in `Renderer` (`PRIORITY_ORDERS`, `MODE1_BG3_FRONT_ORDER`).
4. Color math (CGWSEL/CGADSUB/COLDATA), in 15-bit color.
5. Master brightness: the frame buffer stores each pixel as `brightness << 15 | bgr15`. The
   core stops there; turning that into RGB is the display's job (`jfc.ui.video.SnesColor` for
   plain RGB, `jfc.ui.video.VideoFilter` for the NTSC filter), so a filter or shader gets the
   exact SNES color even during fades.

## Frame loop, threads and pacing

The boundary between the console and any user interface is two interfaces in `jfc.ui`:
`VideoSink.present(Frame)` receives each finished picture and `InputSource.poll(int[])`
supplies buttons. `EmulationSession` owns the `Snes`; `runFrame()` runs one frame: poll every
`InputSource` and OR their buttons per player, emulate, hand the frame to the `VideoSink`, play
the frame's audio. A `Frame`'s pixel array is the PPU's own buffer, valid only during
`present()`. Battery SRAM is saved about once a second when changed, and on exit (atomic
temp-file move).

**Who calls `runFrame()`, and when:**
- **OpenGL window** (`LwjglWindow`, the default): everything on the main thread, which GLFW
  and OpenGL require. One loop: handle window events, run a frame, draw it (`GlRenderer`),
  `glfwSwapBuffers` with vsync on. It measures the display's real refresh rate by timing swaps
  (GLFW reports whole numbers, and timing also catches vsync forced off), then `PacingDecision`
  picks: **locked to vsync** (one frame every N refreshes) when the rate is within 0.5% of a
  multiple of 60.10 Hz, else a **steady clock** (`FrameClock`) at the console's rate with each
  frame shown as soon as it's drawn, which a G-Sync/FreeSync display turns into exact,
  smooth 60.10 Hz. It re-measures on an exclusive-fullscreen switch and when the window moves to another
  monitor. F11 is a borderless window slightly larger than the screen, which switches
  instantly; Alt+Enter is exclusive fullscreen, with the lowest latency (`frontend.md`
  explains why there are two).
- **Swing window**: an "emulation" thread (non-daemon, so it can write the final save) runs
  frames paced by a `FrameClock`. Swing's event thread paints the image and passes keys to
  `SwingKeyboard` (an `AtomicInteger` the emulation thread reads).

**Sound never sets the pace.** `AudioOutput` keeps the device buffer (128 ms) about half full
by resampling each frame's audio by at most 0.5% (dynamic rate control, `AudioResampler`), so
writes return at once. It used to be the other way round (blocking writes paced the
emulator), but the device frees space in ~30 ms chunks, so frames arrived up to 35 ms apart.
After a pause (window drag, display measurement, fullscreen switch) the buffer is topped up
with silence instead of creeping back. Measured with `--stats`: frame gaps 16.0-17.5 ms, the
buffer settling at ~50%, nothing dropped.

**Gamepads (`GlfwGamepads`)**: read through GLFW on the main thread; GLFW's joystick callback
reports pads being plugged in or out, so nothing is ever searched for. Pads in SDL's
controller database (bundled with GLFW) use their standard layout, mapped by position (bottom
face button = SNES B, right = A, left = Y, top = X); others, like the user's Buffalo pad, go
through `GamepadMapping` (`gamepad.properties`) as with winmm.

**Rendering (`GlRenderer`)**: sharp pixels upload the raw 19-bit PPU values into a `GL_R32UI`
texture and `snes-pixels.frag` converts them with the same integer maths as `SnesColor`
(`GlRendererTest` reads the pixels back and compares all of them). NTSC modes run on the GPU
in two passes: `snes-ntsc.frag` (a GLSL port of `snes_ntsc`'s filtering step, LGPL) renders the
602 x 448 picture into a framebuffer texture once per frame, one fragment per output pixel
summing six precomputed smears with the same packed integer maths as `SnesNtsc.blit`; then
`rgb-picture.frag` scales it to the window bilinearly. The table of smears is still built by
`SnesNtsc` in Java when the mode changes (~25 ms) and uploaded as a 128 x 8192 `GL_R32UI`
texture. `GlRendererTest` checks the GPU output equals the Java filter's for every pixel
(three setups, all burst phases, scanlines on and off). Scanlines (F3) are drawn as CRT
beams when the picture is scaled to the window (`crt-scanlines.frag`, in any picture mode).
Shaders live in
`src/main/resources/jfc/ui/lwjgl/shaders/`. LWJGL is told to use its FFM memory backend
(`Configuration.MEMORY_BACKEND = "ffm"`), which avoids the JVM's `sun.misc.Unsafe` warnings.

## NTSC filter (`jfc.ui.video.ntsc`)

A port of Blargg's `snes_ntsc` 0.2.2 that keeps the C code's packed-RGB arithmetic and float
steps, so output should match Snes9x. It precomputes, for 8192 colors (4 bits red, 5 green,
4 blue), the 14-pixel smear each produces at each of 3 burst phases and 3 positions in a
3-pixel chunk (3 SNES pixels = 2 color subcarrier cycles), then filters a line by summing six
smears per output pixel: 256 pixels become 602. Usage follows Snes9x on Windows: the burst
phase steps 0, 1, 2 per frame (`frame.number() % 3`), each line is drawn twice (602 x 448),
and the scanline option darkens every second row by 1/8. Master brightness is applied before
filtering the way Snes9x does (`round(channel * brightness / 15)`).

## Construction order (`Snes` constructor)

The CPU needs a bus, the bus needs `InterruptControl`, and `InterruptControl` needs the CPU.
So `InterruptControl` is created first and then `interrupts.connect(cpu)` once the CPU exists.
`DmaController` gets its bus through `setBus`.

## Planned direction

Milestone 10's remaining step (see `docs/m10-shader-plan.md`) is the rest of 10e: the beam
scanlines are done, while a mask and curvature are not. The core (`jfc.core`) must not depend on Swing or LWJGL (`CoreBoundaryTest`).
