# Development guide

## Toolchain (on the user's Windows machine)

- **JDK 25**: `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`. The JDK on PATH is
  21, so always set `JAVA_HOME` explicitly.
- **Maven 3.9.6**: `C:\dev\tools\apache-maven-3.9.6\bin\mvn` (not on PATH). It prints
  harmless jansi native-access warnings on JDK 25; filter lines starting with `WARNING`.
- Git Bash and PowerShell are both available. No Python.

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"
/c/dev/tools/apache-maven-3.9.6/bin/mvn -B package            # build + all default tests
/c/dev/tools/apache-maven-3.9.6/bin/mvn -B test -Dtest=RendererTest
/c/dev/tools/apache-maven-3.9.6/bin/mvn -B test -Dtest=CpuSingleStepTest -Dsnesible.cpuTests=all
```

Surefire summaries hide dynamic-test names; read `target/surefire-reports/<class>.txt` for
failure details.

## Test data and ROMs (all git-ignored)

- `roms/`: the user's own dumps. The docs never name a game: they use the labels below, by
  hardware and genre (a legal precaution; the user's rule). `roms/KEY.md`, also git-ignored,
  maps each label to its file. The ROMs: the LoROM platformer, the action-adventure (has a
  copier header), the FastROM action game, the beat-'em-up, two SuperFX games (the GSU-1 game:
  copier header, no extended header; the GSU-2 game: 2 MiB, battery-backed GSU RAM), and the
  SA-1 game (4 MiB, 32 KiB battery-backed BW-RAM). All LoROM, USA.
  `.srm` save files are written next to the ROMs.
- `testdata/65816` (512 files) and `testdata/spc700` (256 files): the SingleStepTests suites,
  stored as gzip (`*.json.gz`, about 470 MB). Fetch with
  `powershell -ExecutionPolicy Bypass -File scripts/download-cpu-tests.ps1`. Note: curl config
  files need forward slashes in paths (backslashes are escapes).
- `gamepad.properties` (optional, per machine): SNES button name to device button number.

## Test suites

| Test class | What it covers |
|------------|----------------|
| `CpuSingleStepTest` | 65816: registers, memory, per-cycle bus activity (1,000 per file by default) |
| `Spc700SingleStepTest` | SPC700: all 256,000 tests (about 5 s) |
| `CpuInterruptTest` | reset, NMI, IRQ, WAI wake-up |
| `CartridgeTest`, `MirroringTest`, `RealRomTest` | loading, mapping, SRAM saves, real ROM checksums, every ROM's coprocessor supported |
| `GsuTest` | SuperFX core: hand-assembled programs (delay slot, prefixes, RAM byte order, multiplies, PLOT/RPIX) |
| `SuperFxTest` | SuperFX board: memory map, registers, bus sharing, catch-up, STOP IRQ, cache window |
| `Sa1Test` | SA-1 board: Super MMC, BW-RAM and bitmap view, I-RAM sharing, messages/IRQs, vectors, math, DMA, bit reader (tiny SA-1 programs) |
| `SnesTest` | memory map, access timing, frame length, NMI, V-IRQ, DMA, HDMA incl. mid-frame enable (hand-assembled programs) |
| `PpuPortsTest`, `RendererTest`, `EffectsTest` | VRAM/CGRAM/OAM ports, layers, sprites, priorities, color math, windows, Mode 7, mosaic, offset-per-tile |
| `ApuTest`, `DspTest` | boot ROM upload protocol, timers, voice playback, ENDX, key off |
| `GlRendererTest` | the OpenGL path in a hidden window: sharp pixels match `SnesColor` and the NTSC shader matches the Java filter, for every pixel; beam scanlines behave (skipped without OpenGL 3.3) |
| `GlfwGamepadsTest` | standard-layout gamepads map by position; triggers, stick, hat angles |
| `PacingDecisionTest` | which pacing the OpenGL window picks for 60, 59.94, 120, 144 Hz, PAL, broken vsync |
| `AudioResamplerTest` | dynamic rate control: exact at ratio 1, the right length at 1.005, no clicks between chunks |
| `CoreBoundaryTest` | `snesible.core` imports no Swing, AWT, LWJGL, `snesible.ui`, `snesible.tools` or `snesible.debug` |
| `ObservableTest` | with no observer installed, every `Observable.wrapXxx` returns its argument |
| `MemoryObserverTest` | the memory debugger's recording: fetch vs read, DMA through the VRAM port, APU RAM under the registers, watching changes nothing |
| `VideoFilterTest` | sharp-pixel conversion; NTSC filter: size, flat colors, composite vs S-Video artifacts, burst phase per line and frame, scanlines, brightness |
| `ArithmeticUnitTest`, `JoypadPortsTest`, `GamepadMappingTest` | small units |

The total is about 932 tests, taking about 10 s.

## Headless tools (essential for checking work without seeing or hearing it)

```bash
J="$JAVA_HOME/bin/java"
"$J" -cp target/classes snesible.Main --info roms/*                        # header dump
"$J" -cp target/classes snesible.Main --run "roms/X.sfc" 600 START@320       # where the CPU spends time, BG mode
"$J" -cp target/classes snesible.Main --screenshot "roms/X.sfc" 700 out.png START@320 A@600
"$J" -cp target/classes snesible.Main --screenshot "roms/X.sfc" 700 out.png --video composite  # NTSC filter
"$J" -cp target/classes snesible.Main --record-audio "roms/X.sfc" 1800 out.wav START@1300
"$J" -jar target/snesible-0.1.0-SNAPSHOT.jar --gamepad-test                  # as the OpenGL window reads pads (GLFW); needs the user to press buttons
"$J" -jar target/snesible-0.1.0-SNAPSHOT.jar --gamepad-test --swing          # as the Swing window reads them (winmm)
"$J" -jar target/snesible-0.1.0-SNAPSHOT.jar "roms/X.sfc" --stats            # the OpenGL window, timing each second
"$J" -jar target/snesible-0.1.0-SNAPSHOT.jar "roms/X.sfc" --swing            # the Swing window
"$J" -jar target/snesible-0.1.0-SNAPSHOT.jar --debug "roms/X.sfc"            # with the memory debugger window (F12)
"$J" -cp target/classes snesible.Main --debug-screenshot "roms/X.sfc" 600 out.png --space VRAM START@200
                                                                        # the debugger's heat map as a PNG
```

- `--debug-screenshot` runs the game with the memory debugger attached and saves what its
  window would show for one memory (`--space` WRAM, ROM, Cart RAM, I-RAM, VRAM, CGRAM, OAM,
  ARAM; `--size 1024x800`). It also prints the warmed-up drawing time.
- Read screenshot PNGs with the Read tool to check rendering. NTSC screenshots are 602 x 448;
  crop and enlarge a region (a tiny scratch Java program) to see the artifacts.
- To capture the real window, `CopyFromScreen` returns a blank white client area (Java2D
  draws through Direct3D). Use `PrintWindow(hwnd, hdc, 2)` from a DPI-aware PowerShell
  (`SetProcessDPIAware`) instead; it works for the OpenGL window too, fullscreen included,
  but can block that window's drawing for seconds (one capture stalled a frame 6.8 s), so
  don't capture during timing measurements. The action-adventure's title flashes white
  around frame 900.
- To press keys in the window from PowerShell, `SendKeys` doesn't reach it; tap Alt with
  `keybd_event` (Windows then allows `SetForegroundWindow`), then send the key with
  `keybd_event`.
- `--stats` prints, each second: frames run, the shortest and longest gap between frames, the
  audio buffer level, the rate-control adjustment and any dropped audio. Expect 61 fps, gaps
  near 16.6 ms and a buffer near 50% once the JIT has warmed up (the first two seconds have
  gaps up to ~33 ms).
- `java -cp target/classes` has no LWJGL (it lives in `target/lib`); the headless tools don't
  need it, and playing falls back to the Swing window. Use `java -jar` for the OpenGL window.
- Scripted presses hold for 6 frames. Useful script for the LoROM platformer: `START@320 START@450 START@600`
  reaches the opening message; it can be dismissed only after about frame 1450 (see
  hardware-notes).
- For audio, a scratch analyzer that prints RMS per 0.5 s and draws a spectrogram worked
  well (harmonic horizontal lines mean music; flat or noisy means broken).
- Launch the window briefly from PowerShell (`Start-Process`, wait about 5 s, `Stop-Process`)
  to check it starts without stderr output and detects the gamepad.

### Debugging techniques that worked
- **Throwaway diagnostics in the scratchpad**: compile a class *in the same package*
  (e.g. `package snesible.core.ppu;`) against `target/classes` to read package-private state:
  `javac -cp target/classes -d <scratch>/out Diag.java`, then
  `java -cp "C:/dev/projects/snesible/target/classes;<scratch>/out" snesible.core.ppu.Diag` (absolute
  Windows-style classpath with `;`).
- `Cpu.setInstructionListener` gives per-instruction hooks (PC histograms, per-line sampling).
- **Window stutter**: wrap the `VideoSink` in a scratch harness that runs the real
  `EmulationSession` and logs gaps between `present()` calls over 25 ms; time suspects
  (input poll, audio write) the same way. GC logs (`-Xlog:gc`) ruled out garbage collection;
  the cause was winmm stalling ~160 ms on queries of empty joystick slots, which an automatic
  rescan every 2 s triggered (now on-demand with F9; see `Gamepads`).
- Watch game RAM variables (e.g. the LoROM platformer's `$0100` game mode) to tell game logic apart from
  emulator bugs.
- Find code in a ROM by searching for instruction bytes (`od -An -tx1 -v | grep -ob`): byte
  offset = (match position - 1) / 3, then convert to a LoROM address.

## Conventions (from the user)

- **Heavy documentation**: Javadoc explaining the hardware, plus inline `//` comments wherever
  logic isn't obvious (bit tricks, timing quirks, wraparound). The user explicitly asked for
  this to continue.
- Readability first, speed second (it currently runs about 10x real time, so there's headroom).
- No dependencies beyond the JDK for the emulator itself (Gson is test-scope only). LWJGL
  3.4.3 (GLFW, OpenGL) is the one runtime dependency, used only by `snesible.ui.lwjgl`; Maven copies
  it to `target/lib` and the jar's manifest lists it, so `java -jar` works.
- Commit at the end of each milestone (the user has approved this pattern), with a descriptive
  body and the `Co-Authored-By` trailer. Don't commit ROMs, test data or saves.
- Don't embed copyrighted console code (hence the clean-room APU boot ROM).
- The user (a C# NES emulator author) reviews code and tests by playing; they appreciate
  verification evidence (traces, screenshots) over claims.
