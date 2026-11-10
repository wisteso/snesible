# JFC

An educational Super Nintendo / Super Famicom emulator in Java. The code is written to be read:
each package mirrors a piece of the real hardware, and clarity comes before speed.

## Build and run

Requires JDK 25 and Maven.

```bash
mvn package
java -jar target/jfc-0.1.0-SNAPSHOT.jar --info roms/*.sfc
```

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

More packages (bus, 65816 CPU, DMA, PPU, APU, UI) are added as the emulator grows.

## Roadmap

1. ~~Skeleton and cartridge loading~~
2. ~~65816 CPU core, verified against the SingleStepTests suite~~
3. Bus, DMA, interrupts, APU handshake stub
4. PPU: backgrounds and sprites
5. Controller input
6. PPU: windows, color math, HDMA, Mode 7
7. Real APU: SPC700 and DSP audio
8. Debug and state-viewer tools
