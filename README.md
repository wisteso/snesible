# JFC

An educational Super Nintendo / Super Famicom emulator in Java. The code is written to be read:
each package mirrors a piece of the real hardware, and clarity comes before speed.

## Build and run

Requires JDK 25 and Maven.

```bash
mvn package
java -jar target/jfc-0.1.0-SNAPSHOT.jar "roms/Super Mario World.sfc"
```

Keys: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
Shift/Backspace = Select, Escape = quit. F2 cycles the picture mode (sharp pixels, or an NTSC
TV filter for a composite, S-Video, RGB, black-and-white or RF connection) and F3 toggles NTSC
scanlines. `--video composite` (or `svideo`, `rgb`, `monochrome`, `rf`) and `--scanlines` pick
them at startup.

Gamepads (Windows only for now): the first pad plays as controller 1 alongside the keyboard,
a second as controller 2. Pads are found at startup; after plugging one in later, press F9 to
look for it (JFC doesn't look automatically, because each search stalls the game briefly). If the face buttons are mixed up, run `--gamepad-test`, press each
button to see its number, and put the numbers in a `gamepad.properties` file in the working
directory:

```properties
A=0
B=1
X=2
Y=3
L=4
R=5
SELECT=6
START=7
```

Games with battery-backed saves keep them in a `.srm` file next to the ROM.

Other modes:

```bash
java -jar target/jfc-0.1.0-SNAPSHOT.jar --info roms/*.sfc
java -jar target/jfc-0.1.0-SNAPSHOT.jar --run "roms/Super Mario World.sfc" 300
java -jar target/jfc-0.1.0-SNAPSHOT.jar --screenshot "roms/Super Mario World.sfc" 700 title.png START@320 START@450
java -jar target/jfc-0.1.0-SNAPSHOT.jar --record-audio "roms/A Link to the Past.smc" 1800 zelda.wav
```

`--info` prints cartridge headers. `--run` runs a game without video and reports where the CPU
spends its time. `--screenshot` saves a frame as a PNG (add `--video composite` for the NTSC
filter), and `--record-audio` saves the sound as a WAV file. The headless modes accept scripted button presses (`BUTTON@frame`).

The APU's boot ROM isn't Nintendo's: it's a clean-room replacement speaking the same upload
protocol (see `jfc.core.apu.BootRom`).

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
mvn test -Dtest=CpuSingleStepTest -Djfc.cpuTests=all
```

## Layout

`jfc.core` is the emulated console, `jfc.ui` everything that presents it to a person, and
`jfc.tools` the headless tools; `jfc.Main` starts one of them.

| Package         | Hardware                                                              |
|-----------------|-----------------------------------------------------------------------|
| `jfc.core.cartridge` | Game cartridge: ROM/SRAM, header, LoROM/HiROM address mapping          |
| `jfc.core.cpu`       | 65816 CPU core (the heart of the Ricoh 5A22)                          |
| `jfc.core.cpu.io`    | The rest of the 5A22: NMI/IRQ timers, multiply/divide, joypad ports    |
| `jfc.core.dma`       | The 5A22's 8-channel DMA controller                                   |
| `jfc.core.bus`       | Memory map and per-access timing (A-bus and B-bus)                    |
| `jfc.core.ppu`       | Picture processor: backgrounds, sprites, Mode 7, windows, color math  |
| `jfc.core.apu`       | Audio unit: SPC700 CPU, DSP, timers, boot ROM                         |
| `jfc.core.system`    | The console: master clock, work RAM, and wiring it all together       |
| `jfc.core.coprocessor` | Cartridge chips: SuperFX (GSU) and SA-1                        |
| `jfc.ui`        | Runs the console in real time: frame pacing, saves, input polling     |
| `jfc.ui.audio`  | Sound output                                                          |
| `jfc.ui.video`  | Turns the PPU's pixels into a picture: plain RGB or the NTSC filter   |
| `jfc.ui.video.ntsc` | Java port of Blargg's snes_ntsc NTSC filter (LGPL 2.1+)          |
| `jfc.ui.input`  | Gamepads (winmm.dll through Java's Foreign Function API)              |
| `jfc.ui.swing`  | Swing window and keyboard input                                       |
| `jfc.tools`     | Headless tools: header info, screenshots, audio recording, run reports |

## Roadmap

1. ~~Skeleton and cartridge loading~~
2. ~~65816 CPU core, verified against the SingleStepTests suite~~
3. ~~Bus, DMA, interrupts, APU handshake stub~~
4. ~~PPU: backgrounds and sprites, plus a window with keyboard input~~
5. ~~Battery saves (SRAM), gamepad input~~
6. ~~PPU: windows, color math, HDMA, Mode 7, mosaic~~
7. ~~Real APU: SPC700 and DSP audio~~
8. Debug and state-viewer tools

In progress: an NTSC TV filter (done, in Java) and a move of video and input to LWJGL, for
cross-platform gamepads and CRT shaders.

## Licensing note

The `jfc.ui.video.ntsc` package is a port of Shay Green's (Blargg's) `snes_ntsc` library and is
licensed under the GNU Lesser General Public License 2.1 or later, as the original is.
