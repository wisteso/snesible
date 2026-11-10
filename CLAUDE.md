# JFC: educational SNES emulator in Java

Read these before working on the project:

- `docs/status.md`: milestones done, what the user has confirmed, known gaps, what's next.
  **Start here.**
- `docs/architecture.md`: package map, timing model, APU sync, render pipeline, threads.
- `docs/hardware-notes.md`: verified hardware quirks and game-specific findings. Check it
  before "fixing" behavior that looks odd.
- `docs/development.md`: toolchain paths, build and test commands, headless tools,
  debugging techniques, user conventions.

## Rules that apply every session

- **Build with JDK 25 explicitly** (the PATH java is 21):
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"` then
  `/c/dev/tools/apache-maven-3.9.6/bin/mvn -B package`.
- **Heavy documentation**: Javadoc explaining the hardware, plus `//` comments on any
  non-obvious logic. The user asked for this explicitly.
- Readability first, speed second. No runtime dependencies beyond the JDK (Gson is test-only).
- Keep the core (`jfc.system` and below) free of Swing; UI and input sit behind small
  boundaries for the planned LWJGL move.
- Verify with evidence: run the test suites, and use the headless `--run`, `--screenshot` and
  `--record-audio` modes (read PNGs with the Read tool). Don't claim a fix without checking it.
- Commit at the end of each milestone with the `Co-Authored-By` trailer. Never commit `roms/`,
  `testdata/`, `.srm` files or `gamepad.properties` (all git-ignored).
- Don't embed copyrighted console code (the APU boot ROM is a clean-room replacement).
- When a milestone or major change lands, update `docs/status.md` (and the other docs if
  their facts changed).
