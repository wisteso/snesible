# Milestone 9 plan: visual memory debugger (ICU64-style)

Status: agreed with the user 2026-11-17. **9a (heat map) is built** (see "9a as built" at the
end); 9b-9d are planned. It replaces the
older M9 plan (disassembler, tracing, breakpoints, event viewer); see "Dropped from the
earlier plan" at the end.

## Goal

A live, visual view of what the console is doing to its memory, modelled on **ICU64**, a
visual debugger for C64 emulators. The analysis of ICU64's demo video and source is in
`docs/ICU64-analysis/README.md` (not committed, at the user's request). The parts we take:

- **9a. Heat map.** Every byte of every memory space drawn as a cell, coloured by how it was
  recently read, written or executed, with the colours fading over time.
- **9b. Inspect and edit.** Zoomed in far enough, cells show their current values. Values in
  work RAM and cartridge RAM can be edited, to change the game's logical state.
- **9c. Tile and sprite viewer.** VRAM decoded as SNES tiles, palettes, and the sprites from
  OAM.
- **9d. (Maybe) value scanner.** Search work RAM for a value, then narrow the matches as the
  value changes ("find the lives counter").

Out of scope (the user's list): external scripting, execution counters, disassemblers, an
instruction logger, CPU boost, and everything else the ICU64 blog describes for its later
versions.

## Design rules (the user's decisions)

1. **The core stays clean.** No debug hooks, listeners or `if (debug)` code in the core's
   classes. The core gets only:
   - `wrapXxx(...)` calls where a processor is given its bus (a handful of lines, below);
   - a few small **read-only getters** for state the debugger can't see otherwise.
2. **One `Observable.wrapXxx` method per processor bus**, hand-written (no reflection, no
   `java.lang.reflect.Proxy`, no bytecode libraries). The method name says which processor
   it is (`wrapCpu`, `wrapSa1`, ...), because the S-CPU and the SA-1 share the `CpuBus`
   interface and a type alone can't tell them apart. With no observer installed, each one
   returns its argument, so a normal run pays nothing per memory access.
3. **Static import** of the methods at the call sites, so they read `new Cpu(wrapCpu(bus))`.
4. **Two packages:**
   - `jfc.debug`: the model (bus wrappers, access recorder, memory spaces, PPU/DSP activity
     worked out from register values, editing, the scanner, tile decoding) and the entry
     point `DebugMain` (see "Entry point" below). The model classes are tested headlessly.
   - `jfc.ui.debug`: the raster UI that draws the model, plus its input handling.
   - The two packages may depend on each other (`DebugMain` opens the debug window, the
     window draws the model). The user accepted that: unlike the core, the debug code has
     little value apart from its UI. If a headless use appears, a `HeadlessDebugMain` can
     break the cycle then. Only the core keeps a strict boundary (`CoreBoundaryTest`).
5. **A raster UI, not Swing.** Swing next to a GLFW window doesn't work on macOS (both need
   the main thread, see `frontend.md`). The debugger instead draws into a plain `int[]` ARGB
   buffer, like the PPU's raw frame buffer, and each window only displays that buffer.
6. **Its own window.** In the OpenGL frontend it's a second GLFW window, on the main thread
   like the game window. In the Swing frontend it's a second `JFrame` showing the same
   buffer. Not drawn beside the game inside the game window.
7. **Exclusive fullscreen (Alt+Enter) hides the debug window**, and leaving it shows it again.
   Borderless fullscreen (F11) keeps it, so the game can be fullscreen on one monitor and the
   debugger open on the other.
8. **Editing is for logical state only:** work RAM and cartridge RAM. Video and sound memory
   (VRAM, CGRAM, OAM, ARAM) are view-only.
9. **Mouse:** the wheel zooms, the left button pans, the right button edits a value. Each
   button has one job.

## How memory access is observed

### Entry point: `DebugMain extends Main`

`Main` stays free of debug code. It gets one refactor: today it's a `final` class with only
static methods, and it builds the console inline (`window.play(new Snes(cart), ...)`). It
becomes an instance class with one overridable factory method:

```java
// jfc.Main
public class Main {
    public static void main(String[] args) throws Exception {
        new Main().run(args);
    }

    protected void run(String[] args) { ... }      // today's main(): options, window choice

    /** Builds the console. A subclass can return an instrumented one. */
    protected Snes createSnes(Cartridge cart) {
        return new Snes(cart);
    }
}

// jfc.debug.DebugMain
public final class DebugMain extends Main {
    public static void main(String[] args) throws Exception {
        new DebugMain().run(args);                  // same options, same window choice
    }

    @Override
    protected Snes createSnes(Cartridge cart) {
        MemoryObserver observer = new MemoryObserver();
        Observable.install(observer);
        Snes snes = new Snes(cart);                 // the wrappers are created in here
        observer.attach(snes);                      // ...and get their program counters here
        DebugWindow.open(observer);                 // jfc.ui.debug
        return snes;
    }
}
```

- So `DebugMain` duplicates nothing: option parsing, the OpenGL-to-Swing fallback and the
  gamepad setup stay in `Main`.
- Run it with `java -cp target/jfc.jar jfc.debug.DebugMain game.sfc` (the manifest's
  `Class-Path` still brings in `target/lib`), or add a `--debug` option to `Main` that only
  hands off to `DebugMain`.
- Only `DebugMain` installs the observer, and it always calls `attach`, so "installed but
  never attached" (every fetch recorded as a plain read, no executed code at all) can't
  happen by accident. This is why `attach` was chosen over the alternative of registering
  each processor at construction with nested calls, `wrapCpu(new Cpu(wrapCpuBus(bus)))`,
  which needs three more core call sites.
- **Per-frame callback:** the debug window must redraw between game frames on the thread that
  runs the game. `LwjglWindow.loop` and `EmulationSession` get a small "after each frame"
  callback that `DebugWindow.open` registers with. `createSnes` runs before the game window
  exists, so the exact order (register now, start calling once the window runs) is settled
  when that code is written. These are `jfc.ui` changes, not core changes.

### `Observable` (core) and `Observer` (debug)

```java
package jfc.core.observe;

/** Pass-through unless a debugger installed an observer before the Snes was built. */
public final class Observable {
    private static Observer observer;              // null: debugging off

    public static void install(Observer o) { observer = o; }

    public static CpuBus wrapCpu(CpuBus bus) {
        return observer == null ? bus : observer.wrapCpu(bus);
    }
    public static DmaController.Bus wrapDma(DmaController.Bus bus) { ... }
    public static CpuBus wrapSa1(CpuBus bus) { ... }
    public static Spc700Bus wrapSpc700(Spc700Bus bus) { ... }
    public static GsuBus wrapGsu(GsuBus bus) { ... }
}
```

- `Observer` is an interface in core with the same methods. `jfc.debug` implements it and
  calls `Observable.install` before constructing `Snes`.
- The observer is global state. That's acceptable: a process runs one emulator. Tests that
  install one must uninstall it afterwards (`install(null)`).

### Call sites (the only core lines that change for wrapping)

| Where | Line | What it records |
|---|---|---|
| `Snes` | `new Cpu(wrapCpu(bus))` | S-CPU reads, writes and instruction fetches |
| `Snes` | `dma.setBus(wrapDma(bus))` | DMA and HDMA transfers, tagged as DMA |
| `Apu` | `new Spc700(wrapSpc700(new SpcSide()))` | SPC700 reads, writes and instruction fetches in ARAM |
| `Sa1` | `new Cpu(wrapSa1(sa1Bus))` | SA-1 reads, writes and fetches (ROM, BW-RAM, I-RAM) |
| `SuperFx` | `new Gsu(wrapGsu(new GsuSide()), ...)` | GSU reads and writes (ROM, RAM, pixel plots) |

`SnesBus` implements both `CpuBus` and `DmaController.Bus`. Separate method names pick the
interface, so no cast is needed.

`GsuBus` becomes public (it's package-private in `jfc.core.coprocessor.superfx` today; the
user agreed). The GSU's instruction fetches aren't marked as executed: most come from its
512-byte code cache and never reach the bus, and its `R15`/`PBR` are package-private. Its
code bytes show as plain reads when the cache loads them.

### Alternatives considered and rejected (2026-11-17)

So later sessions don't reopen them:
- **Per-class hooks or listeners in core classes**, and **wrapping the memory arrays**
  (ROM, RAM, VRAM...): the user rejected both as too much debug code in the core.
- **Generic wrappers** (`java.lang.reflect.Proxy`): slow, box every argument, and the debug
  side still needs per-interface knowledge. Never the intent: one method per bus.
- **Replaceable buses** (`bus()`/`setBus()` on each processor, swapped by `DebugMain` after
  construction): more core methods, and fragile where an object keeps its own copy of the
  bus (`Gsu` passes its bus to `new PlotUnit(bus)`, so plots would silently go unrecorded).
- **A `DebugSnes` subclass**: only reaches the S-CPU and DMA buses. `Apu`, `Sa1` and
  `SuperFx` build theirs internally, and overriding wiring from the `Snes` constructor runs
  into Java's "overridable call from a constructor" pitfall.
- **Registering each processor with nested calls**, `wrapCpu(new Cpu(wrapCpuBus(bus)))`,
  instead of `attach`: works, but needs three more core call sites. `DebugMain` always
  calls `attach`, so the risk it removes doesn't arise.

### What each wrapper does

- **Read, write, idle:** passes the call on unchanged and records it. Idle cycles record
  nothing.
- **Instruction fetches** are spotted without touching `Cpu` or `Spc700`: a read whose address
  equals the processor's program counter is a fetch. For the 65816 that's
  `(registers.pbr << 16) | registers.pc` (`Cpu.fetchByte` reads, then increments `pc`); for
  the SPC700 it's its public `pc`. Operand bytes are read at the advancing `pc` too, so they
  count as executed, which is what ICU64 shows as well.
- **Getting the program counter:** in `new Cpu(wrapCpu(bus))` the wrapper is created first,
  so it can't be handed the `Cpu` it will serve (the `Cpu` holds the wrapper, not the other
  way round, and `read(address)` carries only the address). `DebugMain.createSnes` calls
  `observer.attach(snes)` right after construction (see "Entry point"). That gives the wrappers
  `snes.cpu().registers()`, `snes.apu().cpu()` and the SA-1's `cpu()`. Until then a wrapper
  can't tell fetches from data reads, so it records every read as a read. The only access
  that early is the SPC700 reading its reset vector in its constructor, which is a data read
  anyway, so nothing is mislabelled.
- **Turning an address into a memory and an offset:**
  - Work RAM: banks `$7E-$7F`, and `$0000-$1FFF` in banks `$00-$3F`/`$80-$BF` (the same
    decoding as `SnesBus.readA`).
  - Plain cartridges: `Mapper.romOffset/sramOffset`. `LoRomMapper` and `HiRomMapper` become
    public, and the debugger builds its own from `cartridge.mapMode()`, `romSize()` and
    `sramSize()` (the same two-case choice `Cartridge` makes). Fallback, if that ever drifts
    from `Cartridge`: a read-only `Cartridge.mapper()` getter.
  - SA-1: the debug side decodes the map itself. The Super MMC bank registers (`$2220-$2223`)
    and BW-RAM mapping (`$2224`) are copied from the register writes the bus wrapper sees.
  - SuperFX: its map is fixed, so the debug side decodes it directly.
- **Indirect writes** (the address lives inside a chip, not on the bus): just before passing
  the write on, the wrapper asks for the target with a read-only getter:
  - `$2118/$2119` → VRAM word address after remapping (`Ppu`, new getter);
  - `$2122` → CGRAM address (`Ppu`, new getter);
  - `$2104` → OAM address (`Ppu`, new getter);
  - `$2180` → port address (`WorkRam`, new getter).
  Reads through `$2139/$213A`, `$213B`, `$2138` and `$2180` are handled the same way.
- **Register values:** every write to `$21xx` passes through the S-CPU or DMA wrapper. Every
  write to the DSP (`$F2` selects a register, `$F3` writes it) passes through the SPC700
  wrapper. The debug side keeps its own copy of the values it needs, so no register getters
  are needed for the derived activity below.

### What the PPU and DSP read for themselves (worked out, not recorded)

The renderer indexes `Ppu.vram/cgram/oam` and the DSP indexes ARAM directly, with no method
call to wrap. Once a frame, the debug side works out from the register copies what they must
have read, and marks it as a separate access kind ("PPU fetch" / "DSP fetch") so the view can
show it in its own colour and label it as derived:

- **Backgrounds:** BG mode (`$2105`) gives each layer's bits per pixel; `$2107-$210A` give
  tilemap address and size; `$210B-$210C` give tile data address; the scroll registers give
  the visible part of each tilemap. Mark the visible tilemap entries and the tiles they name.
- **Sprites:** `$2101` gives the sprite tile addresses; the OAM entries give each on-screen
  sprite's tiles. Mark those tiles and the OAM bytes.
- **Palette:** the palette numbers in the visible tiles and sprites give the CGRAM entries.
- **Mode 7:** the whole 32 KB Mode 7 area when BG mode is 7 (refine later if useful).
- **DSP:** `DIR` gives the sample directory; each playing voice's `SRCN` selects its entry,
  which gives the sample's start. Mark from there to the block with the BRR end flag. `ESA`
  and `EDL` give the echo buffer, marked as read and written while echo writes are enabled.

What this misses: how many times something was read, and when. Mid-frame changes (HDMA, raster
splits) are only caught at one setting; if that matters, redo the calculation whenever one of
those registers is written (the wrappers see the writes). A voice's exact position inside its
sample isn't known: the whole sample is marked while it plays.

### Core changes, complete list

| Change | Kind |
|---|---|
| `jfc.core.observe`: `Observable`, `Observer` | new, small |
| 5 `wrapXxx(...)` call sites (`Snes` ×2, `Apu`, `Sa1`, `SuperFx`) | edited lines |
| `LoRomMapper`, `HiRomMapper`, `GsuBus` made public | visibility only |
| `Ppu`: VRAM write address (after remap), CGRAM address, OAM address | read-only getters |
| `WorkRam`: port address | read-only getter |
| `Sa1`: I-RAM bytes (the user agreed, 2026-11-17) | read-only getter |
| `Apu`: `ramBytes()` and `peekDspRegister(int)` (added while building 9a) | read-only getters |

Outside the core: `Main` becomes an instance class with `createSnes` (above), and
`LwjglWindow` / `EmulationSession` get the after-each-frame callback.

## The access recorder (`jfc.debug`)

- One **memory space** per memory: WRAM (128 KB), ROM, cartridge RAM, SA-1 I-RAM, VRAM
  (64 KB), CGRAM (512 B), OAM (544 B), ARAM (64 KB). Each has a name, size and the access
  kinds that apply to it.
- For each byte and each kind (read, write, execute, DMA, derived PPU/DSP fetch), it stores
  the **frame number of the last access**. The view turns "frames since" into a fading
  colour only for the cells it draws, so nothing is decayed in bulk each frame.
  - Size: 4 bytes × kinds × space size. For a 4 MB ROM with read and execute that's 32 MB;
    use 16-bit frame stamps (they wrap after ~18 minutes, far beyond the fade time) if that
    turns out too much.
- The recorder is written only on the emulation thread, inside `runFrame()`.

## The raster UI (`jfc.ui.debug`)

- **Output:** `int[]` ARGB, resized with its window. The OpenGL frontend uploads it as a
  texture and draws it with a plain pass-through shader (no NTSC, no scanlines). The Swing
  frontend copies it into a `BufferedImage`.
- **Debug window swaps without vsync** (swap interval 0), so two windows waiting for vsync
  can't halve the game's frame rate.
- **Threading:** the debugger updates its model and redraws between frames on the thread
  that calls `runFrame()` (the main thread in the OpenGL window; the emulation thread in
  Swing, with the same image hand-off the game image uses).
- **Input boundary:** a small toolkit-free event type (mouse moved, button, wheel, key, typed
  character). GLFW callbacks and Swing listeners both feed it.
- **Font:** an 8×8 bitmap font drawn for the project (or a public-domain one, with its notice).
  Integer scaling for high-DPI screens.
- **Widgets, only as needed:** toggle buttons, a hex input field, a scrollable list (9d).
- **Mouse:** the wheel zooms, anchored at the cursor; dragging with the left button pans; the
  right button edits the value under the cursor (9b). Each button has one job.
- **Headless check:** a `jfc.tools` mode (installs the observer and calls `attach` like
  `DebugMain`) such as `--debug-screenshot <rom> <frames> <out.png>`
  runs the game with the observer installed and writes the debugger view to a PNG, so every
  milestone can be verified with evidence.

## Sub-milestones (one reviewable commit each, or more)

### 9a. Heat map
1. `jfc.core.observe` and the five call sites; a test that with no observer installed each `wrapXxx`
   returns the same object.
2. `jfc.debug`: wrappers, `attach`, address decoding, recorder, memory spaces. Tests: a tiny
   hand-assembled program in WRAM shows its bytes as executed and its data as read/written;
   a DMA to VRAM is tagged DMA and lands at the right VRAM addresses.
3. The read-only getters for indirect writes, and the derived PPU/DSP activity.
4. `--debug-screenshot` drawing every space as a grid of cells (one pixel or more per byte).
5. `Main` refactor (`createSnes`), `DebugMain`, and the after-each-frame callback.
6. `jfc.ui.debug`: the raster window in both frontends, wheel zoom and drag-pan, a space
   selector, a colour legend, and the exclusive-fullscreen hiding.

### 9b. Inspect and edit
- Past a zoom threshold, cells show their values in hex. Hovering shows the space, offset,
  bus address(es) and the last access of each kind.
- Editing (right-click a cell, type hex) for WRAM and cartridge RAM only:
  - WRAM: `snes.wram().write(offset, value)`;
  - cartridge RAM (plain SRAM, SuperFX RAM, SA-1 BW-RAM are all `Cartridge.ramBytes()`):
    write the array, then `markRamChanged()`. The edit is then saved to the `.srm` file like
    a game write, so the editor shows that and offers to undo the last edit;
  - SA-1 I-RAM: through its getter.
- Edits are applied between frames, so the CPU never sees a value change mid-instruction.
- An SA-1 cartridge with no RAM in its header gets a private 2 KB BW-RAM from `Sa1`, which
  isn't reachable. No known game needs it; add a getter if one does.

### 9c. Tile and sprite viewer
- VRAM as 2/4/8 bpp tiles with a palette choice; the CGRAM palette; the OAM sprites with
  their position, size, tile, palette, priority and flips, and a preview of each.
- Uses `Ppu.peekVram/peekCgram/peekOam` (side-effect-free) and the decoding code the derived
  PPU activity already needs. The model hands out BGR15 colours; converting to RGB with
  `jfc.ui.video.SnesColor` happens where it's drawn, in `jfc.ui.debug`. Cross-highlight with the heat map (hover a tile → its VRAM
  bytes).
- How much it should differ from ICU64's C64 graphics map is decided when 9c starts.

### 9d. (Maybe) value scanner
- Start a search over WRAM (and optionally cartridge RAM) for a value or "unknown", then
  narrow by "equal to", "changed", "unchanged", "increased", "decreased". Results are a
  list; clicking one jumps the heat map there. Then 9b's editor changes the value.

## Open questions (ask when the step comes up)
- 9c: which views, and how close to ICU64's graphics memory map.
- 9d: whether to build it at all, and 8- or 16-bit values.
- Whether the debugger also needs pause / step-frame (not in the user's list so far).

## Dropped from the earlier plan

The older M9 plan (Swing debugger window, 65816 and SPC700 disassemblers, `--trace` logs,
breakpoints and watchpoints, a beam-position event viewer, DSP voice panel with mute/solo) is
superseded. The user ruled out disassemblers and instruction logging for this debugger. The
existing hooks it relied on (`Cpu.setInstructionListener`, `SnesBus.setRegisterWriteListener`)
stay in the core, unused by this plan.

## 9a as built (2026-11-17)

What differs from, or adds to, the plan above:
- **Two more core getters**, both "Debug support": `Apu.ramBytes()` (sound RAM, for the
  ARAM view and the DSP activity) and `Apu.peekDspRegister(int)` (DSP registers without side
  effects). The DSP's own ENVX values say which voices are audible, which a copy of the
  register writes couldn't, so the planned DSP register copy wasn't needed.
- **Recording** (`jfc.debug`): `MemoryObserver` wraps the buses; `attach` builds an
  `AccessRecorder` (one `MemorySpace` per memory, frame stamps per byte per `Access` kind,
  arrays allocated on first use) and the address maps: `SystemBusMap` (S-CPU and DMA, ports
  followed through the PPU/WRAM getters, PPU register writes copied into `VideoRegisters`),
  `PlainCartridgeMap` (the public mappers), `Sa1Map` (Super MMC and BW-RAM registers copied
  from both CPUs' writes, bitmap view), `SuperFxMap`, `SpcMap` (skips $F0-$FF reads and the
  boot ROM while it's mapped). Accesses before `attach` aren't recorded.
- **Access kinds and colours:** read green, write red, execute blue, DMA magenta, PPU/DSP
  (derived) grey, added like light (code = cyan, read+written data = yellow). Full brightness
  fades over 90 frames to a dim glow that stays.
- **Derived activity:** `VideoActivity` (visible tilemap entries, their tiles and palettes;
  all of OAM; on-screen sprites' tiles and palettes; Mode 7's 32 KiB; the backdrop) and
  `SoundActivity` (audible voices' directory entries and whole samples; the echo buffer).
- **UI** (`jfc.ui.debug.DebugScreen`): tabs per memory, the heat map (`HeatMapView`: rows
  of a power of two that make it roughly square, min 64; small tables 32), a status line with
  the hovered byte's value and the age of each kind of access, and a legend. Mouse wheel
  zooms around the cursor (down to fitting the whole memory, up to 96 px cells); left drag
  pans; keys 1-9 pick a tab. The right button and `keyPressed` are free for 9b.
- **Frontend glue** (outside the core): `jfc.ui.ToolScreen` (toolkit-free contents of a second
  window), `ToolRedrawPacer`, `EmulationSession.setAfterFrame`, `Main.createToolScreen`,
  `GlfwToolWindow`, `SwingToolWindow`, and `--debug` in `Main` (hands over to `DebugMain` so
  `java -jar` works). Details in `frontend.md`.
- **Checking:** `--debug-screenshot` (`jfc.tools.DebugScreenshot`) and `MemoryObserverTest`.
  Checked on Super Mario World (all spaces), Star Fox (GSU writes and DMA in cartridge RAM)
  and Super Mario RPG (SA-1 I-RAM, 4 MiB ROM), plus short window runs of both frontends.
- **First play-test (2026-11-17):** the game dropped frames in F11 fullscreen with the debugger
  maximized on the other monitor. Cause: a synchronous 14 MB `glTexImage2D` upload per frame
  (~8.4 ms) that the redraw pacer didn't count. Fixed with a pixel buffer object upload
  (~0.7 ms) and timing the whole redraw (`frontend.md`, debugger window).

**Ready for 9b:** `MemorySpace.editable()` marks WRAM and cartridge RAM (and I-RAM);
`HeatMapView` knows the cell size (`zoom()`) for drawing values; the right mouse button and
`ToolScreen.keyTyped`/`keyPressed` reach `DebugScreen` from both frontends.
