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

## Layout

| Package         | Hardware                                                              |
|-----------------|-----------------------------------------------------------------------|
| `jfc.cartridge` | Game cartridge: ROM/SRAM, header, LoROM/HiROM address mapping          |

More packages (bus, 65816 CPU, DMA, PPU, APU, UI) are added as the emulator grows.

## Roadmap

1. ~~Skeleton and cartridge loading~~
2. 65816 CPU core, verified against the SingleStepTests suite
3. Bus, DMA, interrupts, APU handshake stub
4. PPU: backgrounds and sprites
5. Controller input
6. PPU: windows, color math, HDMA, Mode 7
7. Real APU: SPC700 and DSP audio
8. Debug and state-viewer tools
