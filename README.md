# Snesible

An educational Super Nintendo / Super Famicom emulator in Java. The code is written to be read:
each package mirrors a piece of the real hardware, and clarity comes before speed.

(The project was called JFC until 2026-11-20; older commit messages use that name.)

## Build and run

Requires JDK 25 and Maven.

```bash
mvn package
java -jar target/snesible-0.1.0-SNAPSHOT.jar "roms/game.sfc"
```

Keys: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
Shift/Backspace = Select, Escape = quit. F11 = fullscreen (instant), Alt+Enter = exclusive
fullscreen (lowest latency; the screen blanks for a few seconds while the monitor adjusts).
F2 cycles the picture mode (sharp pixels, or an NTSC TV filter for an RF, composite, S-Video,
RGB or black-and-white connection) and F3 toggles scanlines (drawn like a CRT's beams in the
OpenGL window). `--video composite` (or `rf`, `svideo`, `rgb`, `monochrome`) and `--scanlines` pick
them at startup.

F10 cycles the sound: **Hardware** (exactly what the console's DSP outputs, the default) and the
**Quality** presets that sound better than the console can, in the style of the SNESamp music
player: 8- or 16-point sinc (the 16-point also in a minimum-phase version, which doesn't ring
before sharp attacks) or cubic interpolation, anti-aliasing for samples played above their
recorded pitch, floating-point mixing with a soft limiter, and de-click. `--audio sinc8` (or
`sinc16`, `minphase`, `cubic`, `hardware`) picks one at startup. `--stereo-width <percent>` (0 = mono, up to 200) and
`--surround-fix` (undoes games' inverted-phase "surround", which sounds hollow on headphones;
Quality presets) adjust the stereo. The game runs exactly the same whichever is chosen.

The window uses OpenGL (through LWJGL) and paces itself to the display: locked to vsync on a
60 or 120 Hz display, or a steady 60.10 Hz clock otherwise, which G-Sync/FreeSync displays show
perfectly smoothly. `--swing` uses the simpler Swing window instead (also the automatic
fallback without OpenGL 3.3); `--pacing vsync|clock` overrides the choice, and `--stats` prints timing each second.
In borderless fullscreen (F11), G-Sync/FreeSync works only if the graphics driver allows it
for windows; exclusive fullscreen (Alt+Enter) doesn't need that, and `--refresh <hz>` asks
for a refresh rate there.

Gamepads: the first pad plays as controller 1 alongside the keyboard, a second as controller
2. In the OpenGL window they work on any platform and can be plugged in or out at any time;
pads GLFW knows (Xbox, PlayStation, Switch Pro, ...) use their standard layout, by position.
The Swing window reads them through Windows' winmm only, finds them at startup, and looks
for newly plugged-in ones when you press F9 (each search stalls the game briefly). If the face buttons are mixed up, run `--gamepad-test` (add `--swing`
for the Swing window), press each button to see its number, and put the numbers in a `gamepad.properties` file in the working
directory. The test also prints the pad's USB vendor and product (`USB 2dc8:6009`): a section
with those numbers maps that model of pad only, so one file can hold every pad you use.
Lines before the first section are for all other pads.

```properties
# Pads without a section of their own
A=0
B=1
X=2
Y=3
L=4
R=5
SELECT=6
START=7

[2dc8:6009]
# A pad with paddles: several device buttons can press one SNES button
A=0
B=1
X=3
Y=4
L=5,6,8
R=2,7,9
SELECT=10
START=11

[2dc8:6009 windows]
# Added on top of [2dc8:6009], on Windows only (also: mac, linux)
SELECT=12
```

Systems can number one pad's buttons differently, which is what the `windows`, `mac` and
`linux` sections are for.

Games with battery-backed saves keep them in a `.srm` file next to the ROM.

Other modes:

```bash
java -jar target/snesible-0.1.0-SNAPSHOT.jar --info roms/*.sfc
java -jar target/snesible-0.1.0-SNAPSHOT.jar --run "roms/game.sfc" 300
java -jar target/snesible-0.1.0-SNAPSHOT.jar --screenshot "roms/game.sfc" 700 title.png START@320 START@450
java -jar target/snesible-0.1.0-SNAPSHOT.jar --record-audio "roms/game.sfc" 1800 game.wav
```

`--info` prints cartridge headers. `--run` runs a game without video and reports where the CPU
spends its time. `--screenshot` saves a frame as a PNG (add `--video composite` for the NTSC
filter), and `--record-audio` saves the sound as a WAV file (it takes the same `--audio`, `--stereo-width`
and `--surround-fix` options). The headless modes accept scripted button presses (`BUTTON@frame`).

The APU's boot ROM isn't Nintendo's: it's a clean-room replacement speaking the same upload
protocol (see `snesible.core.apu.BootRom`).

Put your own ROM dumps in `roms/` (git-ignored). Tests in `RealRomTest` run against them when
present and are skipped otherwise.

## CPU tests

Both CPUs are verified against the SingleStepTests suites
([65816](https://github.com/SingleStepTests/65816): 5.12 million tests;
[SPC700](https://github.com/SingleStepTests/spc700): 256,000 tests). Each test checks registers,
memory and the exact cycle-by-cycle bus activity of one instruction. Download them once (about
470 MB, git-ignored):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/download-cpu-tests.ps1
```

`mvn test` then runs all the SPC700 tests and the first 1,000 tests of every 65816 opcode (about
10 seconds). To run all of the 65816 tests (about 30 seconds more):

```bash
mvn test -Dtest=CpuSingleStepTest -Dsnesible.cpuTests=all
```

## Layout

`snesible.core` is the emulated console, `snesible.ui` everything that presents it to a person, and
`snesible.tools` the headless tools; `snesible.Main` starts one of them.

| Package         | Hardware                                                              |
|-----------------|-----------------------------------------------------------------------|
| `snesible.core.cartridge` | Game cartridge: ROM/SRAM, header, LoROM/HiROM address mapping          |
| `snesible.core.cpu`       | 65816 CPU core (the heart of the Ricoh 5A22)                          |
| `snesible.core.cpu.io`    | The rest of the 5A22: NMI/IRQ timers, multiply/divide, joypad ports    |
| `snesible.core.dma`       | The 5A22's 8-channel DMA controller                                   |
| `snesible.core.bus`       | Memory map and per-access timing (A-bus and B-bus)                    |
| `snesible.core.ppu`       | Picture processor: backgrounds, sprites, Mode 7, windows, color math  |
| `snesible.core.apu`       | Audio unit: SPC700 CPU, DSP, timers, boot ROM                         |
| `snesible.core.apu.enhanced` | Enhanced audio presets: interpolation, anti-aliasing, mixing    |
| `snesible.core.system`    | The console: master clock, work RAM, and wiring it all together       |
| `snesible.core.coprocessor` | Cartridge chips: SuperFX (GSU) and SA-1                        |
| `snesible.ui`        | Runs the console in real time: frame pacing, saves, input polling     |
| `snesible.ui.audio`  | Sound output                                                          |
| `snesible.ui.video`  | Turns the PPU's pixels into a picture: plain RGB or the NTSC filter   |
| `snesible.ui.video.ntsc` | Java port of Blargg's snes_ntsc NTSC filter (LGPL 2.1+)          |
| `snesible.ui.input`  | Gamepads (winmm.dll through Java's Foreign Function API)              |
| `snesible.ui.swing`  | Swing window and keyboard input                                       |
| `snesible.ui.lwjgl`  | OpenGL window (LWJGL): drawing, keyboard, pacing to the display        |
| `snesible.tools`     | Headless tools: header info, screenshots, audio recording, run reports |

## Roadmap

1. ~~Skeleton and cartridge loading~~
2. ~~65816 CPU core, verified against the SingleStepTests suite~~
3. ~~Bus, DMA, interrupts, APU handshake stub~~
4. ~~PPU: backgrounds and sprites, plus a window with keyboard input~~
5. ~~Battery saves (SRAM), gamepad input~~
6. ~~PPU: windows, color math, HDMA, Mode 7, mosaic~~
7. ~~Real APU: SPC700 and DSP audio~~
8. Debug and state-viewer tools

Milestone 10 (done): an NTSC TV filter (in Java for Swing and screenshots, as a shader
in the OpenGL window), an OpenGL window with display-synchronized pacing, and cross-platform
gamepads through GLFW, and CRT-style scanlines. A shadow mask may follow someday.

## Licensing note

The `snesible.ui.video.ntsc` package is a port of Shay Green's (Blargg's) `snes_ntsc` library and is
licensed under the GNU Lesser General Public License 2.1 or later, as the original is. So is
the OpenGL shader ported from it, `src/main/resources/snesible/ui/lwjgl/shaders/snes-ntsc.frag`.
The license's full text is in [LICENSE-LGPL-2.1.txt](LICENSE-LGPL-2.1.txt); everything else is
under the MIT license in [LICENSE](LICENSE). The built jar carries both texts in its `META-INF`.
