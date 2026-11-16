# Frontend guide (`jfc.ui`, `jfc.tools`): how the Milestone 10 code works

Written for future sessions, so they can work on the windows, video, sound and input code without
re-reading all of it. `architecture.md` has the overview; this file holds the details,
reasons, gotchas and known limits. `m10-shader-plan.md` is the original plan and the user's
decisions; where it and the code disagree, the code (and this file) win. See "Where the plan
and the code differ" at the end.

Branch history: all of this was built on `m10-video` (worktree `.claude/worktrees/m10-video`),
branched from `main` at `850cca2`, and merged into `main` as a fast-forward on 2026-11-15
after the user's play-tests.

## 1. What changed for someone coming from `main`

If you last worked on `main` before Milestone 10, these are the changes that affect you:

1. **Packages moved.** All emulation code is now under `jfc.core` (`jfc.cpu` became
   `jfc.core.cpu`, `jfc.ppu` became `jfc.core.ppu`, and so on). The windows, sound and input
   are under `jfc.ui`, and the headless tools are under `jfc.tools`. Only `Main` is left in
   `jfc`. The move changed only `package` lines and imports, no logic. Any new file you add
   on `main` in an old package has to be moved by hand when merging.
2. **The PPU frame buffer no longer holds RGB.** Each `int` is
   `brightness << 15 | bgr15` (19 bits: master brightness 0-15, then 15-bit BGR as in CGRAM).
   This was the only logic change to emulation code (step 5 of `Renderer`). To get RGB, call
   `jfc.ui.video.SnesColor.toRgb(pixel)`. Anything that reads `ppu().frameBuffer()` (tests,
   tools, the Milestone 9 debugger) has to convert. Why: a filter or shader needs the exact
   SNES color even during fades, and a 24-bit value below full brightness has lost it.
3. **`jfc.core` may not import** `java.awt`, `javax.swing`, `javax.imageio`, `org.lwjgl`,
   `jfc.ui` or `jfc.tools`. `CoreBoundaryTest` scans the sources and fails the build if it
   does.
4. **The OpenGL window is now the default**, and the Swing window is behind `--swing`. LWJGL
   is the only runtime dependency, and only `jfc.ui.lwjgl` uses it. Run the game with
   `java -jar target/jfc-0.1.0-SNAPSHOT.jar`: the manifest puts the jars in `target/lib` on
   the class path. `java -cp target/classes` has no LWJGL. The headless tools work that way,
   and playing falls back to Swing.
5. **The sound card no longer sets the pace.** Frames are paced by the display or a steady
   clock, and the audio is resampled by up to 0.5% to keep the device buffer half full
   (dynamic rate control). Don't reintroduce blocking audio writes as the timing source.
6. **Gamepads:** the Swing window still reads them through winmm and only looks for new ones
   when the user presses F9. The OpenGL window reads them through GLFW, which notices
   hot-plugging by itself.

## 2. The boundary

```
jfc.core.system.Snes                      (the console; knows nothing about screens)
      ^  runFrame(), joypads().setPressed(), apu().drainSamples(),
      |  ppu().frameBuffer()/visibleLines(), cartridge() for saves
jfc.ui.EmulationSession                   (owns the Snes: input -> frame -> video -> audio -> saves)
      |                         |
 InputSource.poll(int[])   VideoSink.present(Frame)
```

- `EmulationSession.runFrame()` does one frame, in this order:
  1. Poll every `InputSource` and OR the buttons together per player, so the keyboard and
     gamepad 1 both drive controller 1.
  2. Run `snes.runFrame()`.
  3. Call `present(Frame)`.
  4. Every 60 frames, write SRAM if it changed.
  5. Drain the frame's audio and play it.
  6. Record the timing used for the fps title and `--stats`.
- Two ways to drive it:
  - `start()` runs its own non-daemon thread with a `FrameClock`. The Swing window uses
    this. The thread is non-daemon so the JVM waits for the final save.
  - A window that syncs to the display calls `powerOn()`, then `runFrame()` from its own
    loop, then `powerOff()`. The OpenGL window does this.
- **`Frame.pixels()` is the PPU's own buffer, not a copy.** It's valid only during
  `present()`, because the next frame is drawn into the same array. Convert or upload it
  inside `present()`. Never keep a reference.
- `Frame.number()` counts from 1 after reset. The NTSC filter uses `number % 3` as the
  frame's burst phase, so frame 1 gets phase 1, as in Snes9x.
- **Frame width is hard-coded to 256** in `EmulationSession.runFrame` and `Screenshot`. When
  hi-res modes (5/6, pseudo-hi-res) land, the PPU must report its width. Section 8 lists the
  other places that will need changes.
- `VideoSink`'s Javadoc says `present` runs "on the emulation thread". In the OpenGL window
  that's the main thread, which is also the GL thread.

## 3. Threads

| Window | Threads | Who calls `runFrame` | Input hand-off |
|--------|---------|----------------------|----------------|
| OpenGL (`LwjglWindow`) | **one**: the main thread runs GLFW events, emulation, GL drawing and swaps | `LwjglWindow.loop` | none needed: GLFW callbacks fire inside `glfwPollEvents()` on the same thread |
| Swing (`SwingWindow`) | `emulation` thread plus Swing's event thread | `EmulationSession.start()` | `SwingKeyboard` uses an `AtomicInteger`; the image is guarded by `imageLock`; mode and scanlines are `volatile` |

GLFW must be used on the main thread: macOS requires it, and the GL context belongs to its
creator. Never call GLFW or GL from another thread. Emulating a frame takes a few ms, so a
single thread is enough.

## 4. The OpenGL window (`jfc.ui.lwjgl`)

### Startup (`Main.play` → `LwjglWindow.open` → `play`)
- `open()`:
  - Calls `useFfmMemoryAccess()` (`Configuration.MEMORY_BACKEND = "ffm"`). This must come
    before LWJGL touches native memory, or JDK 25 prints `sun.misc.Unsafe` "terminally
    deprecated" warnings. Tests call it too.
  - Starts GLFW and creates a hidden 768x672 window with an OpenGL 3.3 core,
    forward-compatible context. It then centers the window, turns vsync on
    (`glfwSwapInterval(1)`), builds the `GlRenderer` and shows the window.
  - If anything fails, it throws. `Main` catches `LinkageError` (LWJGL isn't on the class
    path) and `RuntimeException` (no GL 3.3), prints the reason and falls back to Swing.
- `play()`:
  1. Builds `GlfwGamepads` and the `EmulationSession` with inputs `[keyboard, gamepads]`.
  2. Measures the display and chooses the pacing *before* `powerOn()`, so the measuring
     pause doesn't starve a freshly opened sound buffer.
  3. Runs the loop.
  4. When the window closes, calls `powerOff()` to write the save, then cleans up in
     `finally`.

### The loop and pacing
- `PacingMode` has three values:
  - `AUTO` (default) chooses one of the two real modes below.
  - `VSYNC`: the loop runs once per refresh, because each `glfwSwapBuffers` waits for one. It
    runs a frame every `refreshesPerFrame` refreshes and redraws the same picture in between.
    The game then runs at refresh / N (60.00 instead of 60.10, which nobody notices). The
    audio's rate control absorbs the difference.
  - `CLOCK`: `FrameClock.waitForNextFrame()` at 60.10 (NTSC) or 50.01 (PAL), then poll
    events, run, draw and swap. Vsync stays on, so there's no tearing. On a G-Sync/FreeSync
    display the refresh follows the frames, which is smooth. On a fixed 144 Hz display,
    frames alternate between 2 and 3 refreshes, a slight judder.
- `PacingDecision.choose(measured, fps, requested)` is a pure function, tested by
  `PacingDecisionTest`:
  - With `AUTO`, it locks only if the measured rate is within **0.5%** of a whole multiple of
    the console's fps.
  - With a forced `--pacing vsync`, it allows **1%** but never a wrong game speed. At 144 Hz
    that would mean 72 fps, so it refuses, prints a message and uses the clock.
  - A measured rate outside 20-500 Hz means "vsync isn't working" (forced off in the driver),
    and it uses the clock.
- **Why measure instead of asking GLFW:** GLFW reports whole numbers (a 59.94 Hz display
  says 59) and can't tell whether the driver forces vsync off. `measureRefreshRate()` does 60
  swaps, each followed by `glFinish()`, ignores the first 20 and returns the median.
  Without the skip and the `glFinish`, the first launch once measured nonsense: right after
  a window appears, swaps return without waiting.
- The window measures again on these events:
  - Entering or leaving exclusive fullscreen (Alt+Enter). Borderless (F11) doesn't change
    the refresh rate, so it skips the measurement.
  - Moving to another monitor: the position callback compares `currentMonitor()`, which is
    the monitor under the window's center.
  - Up to 3 times, 2 s apart, while vsync looks broken.

  Each measurement **pauses the game for about 60 refreshes** (~0.4 s at 144 Hz). The audio
  buffer then drops below 10% and is topped up with silence, by design.
- While the window is minimized it always uses the clock and doesn't draw: a minimized
  window's swaps may not wait.
- The console prints the decision ("Display: 143.9 Hz. Pacing: steady clock ...") and the
  title shows `NN fps (vsync|clock)`.

### Fullscreen (F11 and Alt+Enter)
Two kinds, each on its own key (the user's choice, 2026-11-15): **F11 = borderless**,
**Alt+Enter = exclusive**. In fullscreen, either key goes back to the window and restores its
saved position and size. `toggleFullscreen(Fullscreen kind)` tracks the state in the
`Fullscreen` enum (OFF, BORDERLESS, EXCLUSIVE). Alt+Enter is checked before keys reach
`GlfwKeyboard`, because Enter is Start; Enter's release still reaches it, which is harmless.
(For a while this was a `--fullscreen borderless|exclusive` option; the keys replaced it.)
- **Borderless (F11): instant.** It's an undecorated window **1 px larger than the
  monitor on every side** (`BORDERLESS_OVERHANG`). `GlRenderer.draw` gets that margin, so the
  picture stays inside the visible screen; the overhang is black and off-screen. Why the
  extra pixel:
  - A window that covers the monitor *exactly* gets promoted by Windows to "independent
    flip", the same presentation path as exclusive fullscreen. That change makes the G-Sync
    monitor re-sync, which is the ~4 s blank. A window that doesn't fit exactly stays with
    the desktop compositor (DWM), so nothing changes for the monitor. Measured: no swap
    over 9 ms when entering or leaving.
  - It has to be *larger*, not smaller. Windows hides the taskbar only when the foreground
    window covers the whole monitor. With a window 1 px short, the taskbar stayed in front;
    with 1 px extra it was hidden. Both were checked with screen captures, and the user saw
    the taskbar in one test.
  - It isn't "always on top" (`GLFW_FLOATING`). It doesn't need to be, and Alt-Tab works
    normally. When another window has focus, the taskbar shows again, as Windows always does.
  - The cost: frames go through the compositor, so G-Sync/FreeSync engages only if the
    driver allows it for windows (NVIDIA: "Enable G-SYNC for windowed and full screen
    mode"). Otherwise the steady clock shows the slight 2-3 refresh judder at 144 Hz.
  - The compositor probably also adds up to one refresh of latency (~7 ms at 144 Hz). This
    hasn't been measured; that would take a camera or a light sensor.
- **Exclusive (Alt+Enter): lowest latency.** GLFW's real fullscreen at the desktop
  resolution of the window's current monitor, at `--refresh <hz>` if given, else the desktop
  rate. Variable refresh works with default driver settings.
  - **Every switch blanks the screen for about 4 s** on the user's G-Sync monitor. The
    `glfwSetWindowMonitor` call takes 10-20 ms. After it, swaps stall now and then for up
    to ~2.3 s while the monitor re-syncs; the visible blank lasts longer than the stalls.
    A borderless window that *exactly* covers the screen pauses just as long (see above).
    This isn't a JFC bug.
  - **The NVIDIA driver ignored `--refresh 120`** and stayed at 144 Hz (probably "Preferred
    refresh rate: Highest available"). So the locked-vsync path has never run on the user's
    real hardware. Only `PacingDecisionTest` covers it.

### Keys (`handleKey`, `GlfwKeyboard`)
- Window keys:
  - F2: next `VideoMode`.
  - F3: scanlines (the beam shader, any picture mode).
  - F11: borderless fullscreen.
  - Alt+Enter: exclusive fullscreen.
  - Esc: quit.
  - F9: only prints that the window finds gamepads by itself.
- Everything else goes to `GlfwKeyboard`. Its key map matches `SwingKeyboard` and
  `Controls.HELP`. Keep all three in sync when changing keys.
- GLFW names keys by their US-layout position, so the controller keys keep their places on
  other keyboard layouts. Auto-repeat (`GLFW_REPEAT`) is ignored.
- Losing focus releases every held button, because the key-up events would never arrive.
  (The Swing window doesn't do this, so a key held while it loses focus can stick until it's
  pressed again.)

### Gamepads (`GlfwGamepads`)
- At startup it adds every joystick already present, then listens for the joystick callback
  (connect/disconnect). The callback fires during `glfwPollEvents()`, on the main thread.
  Gotcha: `glfwSetJoystickCallback` returns the *previous* callback, not the one you pass in.
  So the callback is created with `GLFWJoystickCallback.create`, kept in a field (it must stay
  alive while GLFW may call it), and freed in `close()`. An earlier version stored the return
  value, which is null, and threw a NullPointerException on every quit, after the save was
  written.
  There's no polling for devices, so none of winmm's ~160 ms stalls.
- **Two paths per pad:**
  - `glfwJoystickIsGamepad(jid)` is true when the pad is in SDL's controller database, which
    is built into GLFW. Then `glfwGetGamepadState` is mapped **by position**:
    - Face buttons: bottom = SNES B, right = A, left = Y, top = X.
    - Back/Start = Select/Start; bumpers = L/R.
    - The triggers also work as L/R once past 0.5. They rest at -1.
    - D-pad, or the left stick past 0.5.

    **`gamepad.properties` is ignored for these pads.**
  - Otherwise the raw path: buttons as bits, axes 0/1 as X/Y and hat 0 as degrees (via
    `hatDegrees`) go into `JoystickState`, then `GamepadMapping.toSnesButtons`. That's the
    same mapping class and file the winmm reader uses.
- **The user's Buffalo SNES pad (USB 0583:2060)** shows up in GLFW as
  "USB,2-axis 8-button gamepad": 8 buttons, 2 axes, no hats, not in the database. It takes
  the raw path with the default mapping (device buttons 0-7 = A, B, X, Y, L, R, Select,
  Start). **The user confirmed the buttons are right in the OpenGL window** (2026-11-15), so
  GLFW numbers them the same way winmm does. Its GLFW GUID is
  `03000000830500006020000000000000`; the vendor and product ids appear in it byte-swapped.
- **Players shift when a pad is unplugged.** Players are a list in plug-in order (at most
  2). If pad 1 is unplugged, pad 2 becomes controller 1. The winmm reader does the same. A
  third pad is reported and ignored.

### Rendering (`GlRenderer`, shaders in `src/main/resources/jfc/ui/lwjgl/shaders/`)
- Every frame, the raw 19-bit pixels go unchanged into `rawTexture` (`GL_R32UI`) with
  `glTexSubImage2D`. Integer textures can't be filtered, so it's `GL_NEAREST` and the
  shaders read it with `texelFetch`.
- **Sharp mode:** one pass, `snes-pixels.frag`, straight to the window. It converts with the
  same integer maths as `SnesColor` (`(c<<3)|(c>>2)`, then `*brightness/15`, rounded down),
  so it shows the same colors as Swing and the PNGs. `GlRendererTest` checks every pixel.
- **NTSC modes:** two passes.
  1. `snes-ntsc.frag` renders into `ntscPictureTexture` (RGBA8, attached to
     `ntscFramebuffer`) at 602x448 (`outWidth(256)` × line-doubled height). It runs once
     per *emulated* frame, inside `present`/`upload`, not once per refresh.
  2. `rgb-picture.frag` draws that texture into the window with bilinear filtering. This one
     runs on every draw, including the repeat draws in vsync mode.
- **Scanlines (F3):** the last step, the draw to the window, uses `crt-scanlines.frag`
  instead (see the next section). Its input is an RGB picture with one or two rows per SNES
  line: the NTSC texture (2 rows per line), or in sharp mode `sharpPictureTexture`. That's a
  256-wide RGB copy of the frame made by `snes-pixels.frag` into `sharpFramebuffer`
  (`flipY = 0`), only when scanlines are on, and once per uploaded frame
  (`sharpPictureStale`). The window passes `scanlines = false` to `upload`, so the NTSC
  shader's own Snes9x-style scanlines are off there. That option stays for the Java/GPU
  parity tests and matches what Swing and `--screenshot --scanlines` draw.
- **The NTSC table** (`SnesNtsc.table()`, 8192 rows × 128 columns of packed RGB) is built in
  Java and uploaded only when the mode changes (`ntscTableMode != mode`). It takes about
  25 ms, so a mode switch costs one late frame. Changing scanlines doesn't rebuild it.
- `fullscreen.vert` draws one oversized triangle from `gl_VertexID`, with no vertex data.
  Core profile still requires a bound VAO, hence `emptyVertexArray`. The fragment shaders
  work out positions from `gl_FragCoord` and a `viewport` uniform.
- **Row order (gotcha):** textures are uploaded top line first, so texture row 0 is the
  picture's top line, which is the *bottom* row in GL's convention. That's why the present
  shaders use `position.y = 1.0 - position.y`. `snes-ntsc.frag` treats `gl_FragCoord.y` as
  the top-down row number, so the FBO is stored top line at row 0 too. As a result,
  `readNtscPicture()` (`glReadPixels` from row 0) returns rows top-down with no flip. The
  sharp RGB copy is stored the same way (`snes-pixels.frag` with `flipY = 0`; drawn to the
  window it uses `flipY = 1`). Keep this consistent when adding a pass.
- Letterboxing: `draw()` computes the largest rectangle with the picture's shape that fits
  the window, centers it, and clears the rest to black. Sharp mode keeps 8:7 (square
  pixels). NTSC keeps 602:448, which is close to 4:3.
- `glGetUniformLocation` is called on every draw. This is cheap enough, and caching the
  locations would be an easy optimisation if ever needed.
- `draw(framebuffer, width, height, margin, scanlines)` takes the target framebuffer: 0 for
  the window, an offscreen one in tests.

### The scanline shader (`crt-scanlines.frag`, 10e's first part)
- **Model:** each SNES line is a horizontal beam with a Gaussian profile across it. Each
  window pixel adds the light of the two nearest lines' beams at its distance from their
  centers. The adding is done in linear light: decode with gamma 2.2, add, clamp, encode.
  Lines outside the picture are black.
- **Brightness-dependent width:** the beam's sigma goes from `DARK_WIDTH = 0.24` to
  `BRIGHT_WIDTH = 0.40` line heights, following the square root of the linear brightness.
  It's computed per color channel, so a saturated color's strong channel spreads wider (one
  beam per electron gun). As a result, bright areas nearly close the gaps (two beams meet at
  ~90% of their peak) while dark areas show clear lines (~27%).
- **Fixed lines (240p):** the SNES normally sends 262-line fields with no half-line offset, so
  every frame's lines land on the same rows of the tube. The gaps never move. The user asked
  whether alternating even and odd rows would be more authentic. It would imitate 480i,
  which the SNES only produces in its interlace mode ($2133 bit 0, rarely used, not
  emulated). If that mode is ever emulated, shift the beams by half a line on alternate
  fields for those games only.
- **Horizontal:** NTSC pictures are sampled with linear filtering (smooth). Sharp pixels
  snap to texel centers (`smoothX = 0`), so they stay crisp sideways. Vertically, each
  sample sits at a row center, so lines never blend into each other.
- **Tuning:** the constants are at the top of the shader. Checked by eye on Super Mario World
  at 1440 px (6.4 px per line) and covered by `GlRendererTest`: a gray line's gap is
  dimmer than its center, white nearly closes the gap, black stays black, a single line
  lights only nearby rows, frames don't move the lines, and NTSC pictures get the same
  structure. At non-integer scales (1440 / 224 = 6.43 px per line) the profile is evaluated
  per pixel, so there are no hard moiré bands. Some lines do fall slightly differently on
  the pixel grid.
- **Still to do from the 10e plan:** a shadow/slot/aperture mask (optional; marginal at
  1440p, since a TV's ~650 slot columns give only 3-4 screen pixels per RGB triad), and
  maybe a brightness boost to offset the darkening. The user said no tuning is needed ("the
  new scanlines look better than the old ones") and **doesn't want screen curvature**. Snes9x-style scanlines remain in Swing and
  screenshots.

### The NTSC shader in detail (`snes-ntsc.frag`, LGPL)
A GLSL port of the filtering step of `SnesNtsc.blit`. Its output is **identical to the Java
filter, pixel for pixel**. `GlRendererTest` checks every pixel for composite, S-Video and RF,
with scanlines on and off, over frames 1-3 (all burst phases).
- One fragment per output pixel `x`:
  - Chunk `n = x / 7`, position `j = x % 7`, `base = 3n`.
  - Six input pixels reach the fragment:
    - `k0 = base+1`
    - `k1 = j<2 ? base-1 : base+2`
    - `k2 = j<4 ? base : base+3`
    - `kx0 = base-2`
    - `kx1 = j<2 ? base-4 : base-1`
    - `kx2 = j<4 ? base-3 : base`

    These reproduce which pixels the C blitter's rolling `kernel0-2`/`kernelx0-2` registers
    hold at output pixel `j`. The table columns are `j`, `(j+12)%7+14`, `(j+10)%7+28`,
    `(j+7)%14`, `(j+5)%7+21` and `(j+3)%7+35`, plus `burstColumn = ((framePhase+line)%3)*42`.
    Derive these again from `SnesNtsc.blit` before changing any of them.
- `tableRow(i)` applies master brightness the Snes9x way (`(2·c·b + 15) / 30`). It then
  reduces the color to the 13-bit index (`r>>1`, `g`, `b>>1`: 4-5-4 bits). Pixels with
  `i < 0` or `i > chunkCount*3` are black, which is how the C blitter pads each line.
- Sums are unsigned 32-bit packed RGB (`r<<21 | g<<11 | b<<1` with bias). The branch-free
  clamp is copied from `SnesNtsc.clamp`.
- Scanlines darken odd output rows by 1/8 (`rgb - (rgb>>3 & 0x1F1F1F)`), as Snes9x does.

## 5. The Swing window (`jfc.ui.swing`) and winmm gamepads

- `SwingWindow.play()` builds the window and the session, then `session.start()`. Its
  `present()` runs on the emulation thread:
  1. If the mode or scanlines changed, it builds a new `VideoFilter`. With an NTSC mode
     that's a new `SnesNtsc` table, about 25 ms.
  2. It runs the filter **on the CPU** into a `BufferedImage`, under `imageLock`.
  3. It calls `repaint()`.
- Swing's event thread paints the image scaled to fit. It uses nearest-neighbour for sharp
  pixels and **bilinear when the picture is wider than 256**, which assumes "wider than 256
  = NTSC". Hi-res will break that assumption.
- Keys are read through a `KeyEventDispatcher`, so they work whichever component has focus.
- No vsync, so frames can tear or be shown twice. This is the fallback window. The user
  chose to keep it (`--swing`).
- Gamepads: `jfc.ui.input.winmm.Gamepads` plus `WinmmJoysticks` (FFM calls into
  `winmm.dll`). Windows only.
  - It looks for pads at startup and again only on F9 (`requestRescan()`). The search runs
    at the start of the next `poll`.
  - **Why only on request:** asking winmm about an *empty* slot stalls about 160 ms, and
    winmm serialises calls, so the per-frame read of the active pad waits too. An automatic
    rescan every 2 s froze the game every 2 s. A background thread didn't help, because of
    the same lock. The user decided on demand only, as Snes9x does, because many SNES games
    are latency-sensitive. Don't add automatic rescanning back.
- `--gamepad-test --swing` reads pads through winmm, as this window does (see below).

### `--gamepad-test` (`jfc.tools.GamepadTest`)
- It lists the pads and prints every change of the first pad's raw state (button numbers,
  axes, hat) next to the SNES buttons it maps to. Use it to write `gamepad.properties`.
- **It reads pads the way the chosen window does**, because the two readers could number
  buttons differently:
  - By default it reads through GLFW, like the OpenGL window, via `jfc.ui.lwjgl.GlfwJoysticks`
    (public: the tool sits outside `jfc.ui.lwjgl`, the only package allowed to use LWJGL).
    For a pad in GLFW's database it says the pad is mapped by position and that
    `gamepad.properties` doesn't apply, and it shows the positional SNES buttons.
  - `--gamepad-test --swing` reads through winmm, like the Swing window.
- `GlfwJoysticks` starts and stops GLFW itself, so it can't run while an `LwjglWindow` is
  open. `GlfwGamepads` shares its `rawState`.
- Run it with `java -jar`. With `-cp target/classes` there's no LWJGL; it says so and
  suggests `--swing`.

## 6. Video conversion (`jfc.ui.video`) and the NTSC port (`jfc.ui.video.ntsc`)

- `SnesColor`: plain RGB from a packed pixel through a 2^19-entry lookup table (2 MiB).
  Channels are widened 5→8 bits by copying the top bits, then `*brightness/15`, rounded
  down. Used by Swing, PNG screenshots and (mirrored in GLSL) the sharp shader.
- `VideoMode`: `SHARP`, `COMPOSITE`, `SVIDEO`, `RGB`, `MONOCHROME` and `RF`. Each has an
  `NtscSetup` (null for sharp). F2 cycles through them in this order. `parse()` accepts
  `svideo` or `s-video`.
- `VideoFilter`: what Swing and `--screenshot` use. Sharp output is 256x224. NTSC output is
  602x448: each line is drawn twice, always, as in Snes9x. It applies brightness through
  `NtscInput.ENTRY_FOR_PIXEL`, a 2^19 table built lazily on first NTSC use.
- `SnesNtsc`: a port of `snes_ntsc` 0.2.2 that keeps the C code's `float` steps and
  packed-integer tricks, so the output matches Snes9x. **The package is LGPL 2.1+** (header
  on each file), so don't put other code in it. The hi-res (512-pixel) blitter is **not
  ported**.
- `NtscSetup` presets follow Snes9x's options. RF is composite with `mergeFields = false`:
  visible dot crawl.
- **Faithful quirks; don't "fix" these:**
  - Full white comes out `F6FCF6`, not `FFFFFF`. The table keeps only 4 bits of red and
    blue (13-bit index), exactly as the original does.
  - S-Video adds a slight tint (≤10 per channel) to flat colors. The original does this too.
  - The frame phase cycles 0, 1, 2 per frame, as in Snes9x. Real hardware is probably a
    2-frame cycle, but the user chose Snes9x's behaviour (plan decision 3).
  - Master brightness is applied before the filter, in 5-bit steps, as Snes9x's
    `mul_brightness` does. So a fade in an NTSC mode differs slightly from a fade in sharp
    mode.
  - The Java filter's scanlines (Swing, `--screenshot --scanlines`) are Snes9x's simple
    "darken every second row". The OpenGL window draws beams instead (`crt-scanlines.frag`,
    section 4).

## 7. Sound (`jfc.ui.audio`)

- `AudioOutput`: Java Sound, 32 kHz, 16-bit stereo, with a **128 ms device buffer**
  (4096 frames). It writes half a buffer of silence at open, so it starts at the target
  level.
- Each frame, `play()` works as follows:
  1. It measures the fill level (`1 - available/capacity`) and smooths it (10% per frame,
     because the device reports in coarse steps).
  2. It sets `ratio = 1 + 0.005 · clamp((0.5 - fill) · 2)`, then resamples with
     `AudioResampler`. That's linear interpolation, carrying the previous sample across
     calls so chunks join without clicks. Its output runs one sample behind.
  3. It writes **only what fits**. Anything more is dropped and counted in `droppedBytes`.
     It never blocks.
- If the fill level drops below 10% (after a pause such as a window drag, a measurement or
  a fullscreen switch), it tops the buffer up with silence to 50% at once instead of
  creeping back at 0.5%.
- No sound device: the session runs silently and prints a line.
- `--stats` prints, once a second: fps, the shortest and longest frame gap, the average
  buffer fill, the rate adjustment and dropped bytes. Healthy numbers on the user's machine:
  61 fps, gaps 16.0-17.5 ms windowed (16.3-17.0 fullscreen), buffer ~50%, 0 dropped. The
  first ~2 s show gaps up to ~33 ms while the JIT warms up.

## 8. Known limits, open items and future work

**Not yet verified by the user** (they played a few games with it on 2026-11-15, "everything seems fine", but these weren't checked specifically):
- Whether G-Sync works in windowed mode. That needs NVIDIA's "Enable G-SYNC for windowed and
  full screen mode". Fullscreen is the reliable case.
- The locked-vsync path on real hardware (the driver kept 144 Hz).

**Known limits**
- Borderless fullscreen (F11) gets G-Sync only with the driver's windowed G-Sync setting,
  and probably adds about one refresh of latency. Exclusive fullscreen (Alt+Enter) blanks for
  ~4 s per switch (the display, not JFC).
- Each refresh measurement pauses the game for ~0.4 s (at startup, exclusive-fullscreen
  switches, monitor change).
- Swing: no vsync, and a key held while the window loses focus can stick.
- Only 2 players. Players shift down when pad 1 is unplugged.
- Only the build machine's LWJGL natives are bundled. A Maven profile picks Windows, Linux,
  macOS x64 or macOS arm64 at build time, so a jar built on Windows runs the OpenGL window
  only on Windows.
- **macOS is untested and likely needs `-XstartOnFirstThread`** for GLFW. Nothing handles
  that yet. Linux is untested too.
- `GlRendererTest` needs a real OpenGL 3.3 context (hidden window). It's skipped where none
  exists, such as a CI server, so a shader regression there would go unnoticed.

**When hi-res / interlace land in the PPU** (gaps listed in `status.md`):
- `EmulationSession` and `Screenshot` must pass the real width instead of `256`.
- NTSC: port `snes_ntsc_blit_hires` to Java and add a hi-res variant of the shader (the
  table supports it, since its packed-RGB headroom allows twice the contributions).
  `chunkCount` is already a uniform.
- Sharp mode would draw 512x224 at a 2.3:1 shape. Both windows' letterboxing needs a pixel
  aspect (8:7 for the *display*, not the buffer).
- Swing's "width > 256 means bilinear" check would turn on for hi-res sharp pixels.
- Interlace doubles the height (448/478), and NTSC line doubling would have to stop.

**Milestone 9 (debugger) interaction:** the plan keeps the debugger in Swing. Swing windows
and a GLFW window in one process work on Windows and Linux. On macOS they fight over the main
thread, so there the debugger would need the Swing video window. Viewers must take their
snapshots between frames on the thread that runs `runFrame()`, which is the **main thread**
in the OpenGL window. Package: the M10 layout lists it as `jfc.ui.debug`, while
`status.md`'s older M9 plan says `jfc.debug`. Either keeps it out of `jfc.core`, so ask the
user which one when M9 starts.

**Optional future features** (the user's list, 2026-11-15; none is planned work):
- A CRT shadow/slot/aperture mask. It's marginal at 1440p, where a TV's ~650 slot columns
  give only 3-4 screen pixels per RGB triad, and it costs brightness.
- 4:3 pixels in sharp mode. A TV stretched the 256-pixel lines to 4:3 (an 8:7 pixel aspect),
  but sharp mode shows square pixels. The NTSC modes are already about 4:3 (602 x 448).
- Adjustable NTSC settings (Blargg's hue, saturation, sharpness, artifacts, fringing, bleed
  ...); only the fixed presets exist now.

Declined by the user: screen curvature. Done and confirmed: beam scanlines (no tuning
wanted), both fullscreen keys, and the Buffalo pad through GLFW.

**10e (CRT pass), partly done:** beam-profile scanlines replaced Snes9x-style scanlines in
the OpenGL window (`crt-scanlines.frag`, section 4). Not done yet: a mask and any
brightness compensation. Curvature was declined by the user. They would go into the same shader or a pass after it. Keep the
top-down row convention described in section 4.

## 9. Testing and checking this code

- **Unit tests:**

  | Test | What it checks |
  |------|----------------|
  | `VideoFilterTest` | NTSC sizes, flat colors, artifacts, phases, scanlines, brightness |
  | `GlRendererTest` | GPU vs Java, exact; beam scanlines: gaps, bloom, black, fixed lines, NTSC |
  | `PacingDecisionTest` | 60/59.94/120/144 Hz, PAL, broken vsync, refused forced vsync |
  | `AudioResamplerTest` | the resampler |
  | `GlfwGamepadsTest` | standard layout, triggers, stick, hats; uses `GLFWGamepadState.create()`, so no pad or window needed |
  | `GamepadMappingTest` | the button mapping |
  | `CoreBoundaryTest` | `jfc.core` imports |

  Surefire runs with `--enable-native-access=ALL-UNNAMED` (pom `argLine`). The whole suite
  is about 927 tests and takes about 10 s.
- **Headless:**

  ```
  --screenshot <rom> <frames> out.png --video composite [--scanlines]
  ```

  Output is 602x448, with the burst phase taken from `frames % 3`.
- **The real window from scripts** (see `development.md`, "Headless tools"):
  - Launch with `java -jar ... --stats` and read the console output.
  - Capture with `PrintWindow(hwnd, hdc, 2)` from DPI-aware PowerShell. `CopyFromScreen`
    returns white. Capturing can stall the GL window for seconds (one capture caused a
    6.8 s frame), so never capture while measuring timing.
  - Press keys by tapping Alt with `keybd_event`, then `SetForegroundWindow`, then
    `keybd_event` for the key.
  - Zelda's title flashes white around frame 900. A white capture there is real.
- **Lag hunting:** wrap the `VideoSink` in a scratch harness running the real
  `EmulationSession` and log gaps over 25 ms. GC logs (`-Xlog:gc`) ruled out garbage
  collection during the winmm investigation. Per-frame allocation is near zero: the session,
  filter and audio reuse their arrays. Only a few tiny objects are made each frame (the
  `Frame` record and `drawAndSwap`'s two `int[1]`), which is harmless.

## 10. Where the plan and the code differ

`m10-shader-plan.md` was written before the code. These parts of it are out of date:
- **NTSC encode/decode passes:** the plan describes separate GPU "encode/decode" passes
  (~683-wide signal texture). Instead, the shader is a port of `snes_ntsc`'s table-driven
  blit: one pass, plus a present pass. That gives exact parity with the Java filter and
  Snes9x.
- **"Triple-buffer handoff" and "LWJGL window runs emulation on its own thread":** neither
  exists. The OpenGL window does everything on the main thread, and Swing hands frames over
  under a lock.
- **`FramePacer`:** never created. Pacing lives in `LwjglWindow` and `PacingDecision`.
- **"Swing paced by audio":** no longer true. Both windows use `FrameClock` or vsync, with
  dynamic rate control for sound.
- **`--gamepad-test` moving to GLFW:** it reads both ways instead: GLFW by default, winmm
  with `--swing`.
- **Shader location:** `src/main/resources/jfc/ui/lwjgl/shaders/`, not
  `src/main/resources/shaders/`.
- **CPU NTSC in the OpenGL window:** the plan offered it. The user saw no use for it, so
  NTSC there runs on the GPU only.
