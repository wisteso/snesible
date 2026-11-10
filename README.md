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
Shift/Backspace = Select, Escape = quit.

Other modes:

```bash
java -jar target/jfc-0.1.0-SNAPSHOT.jar --info roms/*.sfc
java -jar target/jfc-0.1.0-SNAPSHOT.jar --run "roms/Super Mario World.sfc" 300
java -jar target/jfc-0.1.0-SNAPSHOT.jar --screenshot "roms/Super Mario World.sfc" 700 title.png START@320 START@450
```

`--info` prints cartridge headers. `--run` runs a game without video and reports where the CPU
spends its time. `--screenshot` saves a frame as a PNG. Both headless modes accept scripted
button presses (`BUTTON@frame`).

Put your own ROM dumps in `roms/` (git-ignored). Tests in `RealRomTest` run against them when
present and are skipped otherwise.

## CPU tests

The 65816 core is verified against the [SingleStepTests](https://github.com/SingleStepTests/65816)
suite: 5.12 million single-instruction tests, each checking registers, memory and the exact
cycle-by-cycle bus activity. Download it once (about 455 MB, git-ignored):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/download-cpu-tests.ps1
```

`mvn test` then runs the first 1,000 tests of every opcode (about 3 seconds). To run all of them
(about 30 seconds):

```bash
mvn test -Dtest=CpuSingleStepTest -Djfc.cpuTests=all
```

## Layout

| Package         | Hardware                                                              |
|-----------------|-----------------------------------------------------------------------|
| `jfc.cartridge` | Game cartridge: ROM/SRAM, header, LoROM/HiROM address mapping          |
| `jfc.cpu`       | 65816 CPU core (the heart of the Ricoh 5A22)                          |
| `jfc.cpu.io`    | The rest of the 5A22: NMI/IRQ timers, multiply/divide, joypad ports    |
| `jfc.dma`       | The 5A22's 8-channel DMA controller                                   |
| `jfc.bus`       | Memory map and per-access timing (A-bus and B-bus)                    |
| `jfc.ppu`       | Picture processor: memory ports, backgrounds, sprites, compositing    |
| `jfc.apu`       | Audio unit ports; a handshake stub until Milestone 7                   |
| `jfc.system`    | The console: master clock, work RAM, and wiring it all together       |
| `jfc.ui`        | Swing window, frame pacing and keyboard input                        |

## Roadmap

1. ~~Skeleton and cartridge loading~~
2. ~~65816 CPU core, verified against the SingleStepTests suite~~
3. ~~Bus, DMA, interrupts, APU handshake stub~~
4. ~~PPU: backgrounds and sprites, plus a window with keyboard input~~
5. Battery saves (SRAM), input polish
6. PPU: windows, color math, HDMA, Mode 7
7. Real APU: SPC700 and DSP audio
8. Debug and state-viewer tools
