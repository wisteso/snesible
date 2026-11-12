# Hardware notes and findings

Facts learned while building JFC that aren't obvious from the code alone, mostly confirmed by
the test suites or by tracing real games. Before "fixing" behavior listed here, check this file.

## 65816 (confirmed by SingleStepTests)

- **Read-modify-write in emulation mode**: the extra cycle between read and write is an
  *idle* cycle to the system. The chip drives R/W low, but with VDA/VPA low, so memory
  ignores it. Don't emulate a 6502-style dummy write.
- **PEI and the [dp] modes** never use 6502 page wrapping for their pointer reads, even in
  emulation mode with DL=0. `(dp)`, `(dp,X)`, `(dp),Y` and `dp,X` *do* wrap within the page in
  that case (`Cpu.directAddress`).
- **P register in emulation mode**: the suite's states can have bit 4 clear in emulation mode.
  Widths are derived (`isIndex8Bit() = emulation || p.index8`). PLP/REP/SEP/RTI force M and X
  to 1 in emulation mode; other instructions leave the raw bits alone.
- **Emulation-mode stack**: 6502 instructions wrap S within page 1. The 65816-only instructions
  (PEA, PEI, PER, PHD, PLD, PLB, JSL, RTL, JSR (abs,X)) use the full 16-bit S during the
  instruction and then force S back into page 1 (`pushNative`/`confineStackInEmulationMode`).
- The suite's initial states sometimes have S outside page 1 in emulation mode; the harness
  normalizes S to `$01xx`, since the expected results do.
- **WAI/STP**: the suite expects 3 idle cycles (the third is the first cycle asleep). **WDM**
  skips its operand byte with an idle (not a read) cycle.
- **MVN/MVP**: one byte per `step()`, 7 cycles each, re-fetching the opcode. PC moves back by 3
  until A wraps to $FFFF. The suite records the whole move.

## SPC700 (confirmed by SingleStepTests)

- Many internal cycles are **dummy reads** (usually of PC). The suite distinguishes those
  from "wait" cycles, so `dummyRead()` vs `idle()` matters.
- `[dp]+Y` *reads* idle before fetching the pointer; the `[dp]+Y` *store* idles after.
- `MOV A,(X)+` has an extra idle *after* the read; `MOV (X)+,A` idles instead of reading.
- SLEEP/STOP: op fetch, then repeated (dummy read, wait) pairs; the suite records three pairs.
- MOV dp,dp (FA) doesn't read the destination first; all other stores do.

## PPU

- **Background line numbering**: backgrounds (including Mode 7) use the *beam* line number,
  and the first visible line is beam line 1. So at vertical scroll 0, picture line 0 shows map
  row **1** (`BackgroundLayer`: `screenY + 1 + vScroll`). Sprites don't share this offset.
  Without it Super Metroid's HUD was one line low, and the minimap's bottom dotted row
  (picture line 30) fell onto the split line. Verified against a Snes9x screenshot: dotted rows
  at picture lines 6, 14, 22, 30; gameplay from 31.
- **When a line is drawn**: pixels start around dot 22. Register writes before that (late
  horizontal blank) affect the whole line. Super Metroid's HUD IRQ (H+V at line 31, dot 152)
  writes TM at line 31 dot ~337 in some frames and at line 32 dot 2-3 in others. Rendering at
  dot 0 made line 31 flicker between HUD and gameplay; rendering at dot 22 makes it stable.
- **Scroll registers** (`$210D-$2114`) use two latches: PPU1's (updated by every scroll write)
  and PPU2's (horizontal writes only). `hScroll = new<<8 | (ppu1Latch & ~7) | (ppu2Latch & 7)`.
  **Don't mask to 10 bits before latching**: that dropped bit 2 and made SMW's layers jitter by
  4 px (fixed, regression test `horizontalScrollKeepsAllEightBitsOfTheFirstWrite`).
- `$210D/$210E` also set the 13-bit Mode 7 scroll via the shared Mode 7 latch.
- Sprite tile fetch (34 tiles per line) proceeds from the *last* in-range sprite backward, so
  when the budget runs out, the lowest-index sprites lose tiles. (I'm fairly but not fully sure
  of this; the user was asked to double-check it.)
- OAM reloads its address at vblank start unless in forced blank.
- Color math: no halving when the sub-screen pixel is the backdrop (fixed color) in
  sub-screen mode, or when the main pixel is clipped to black. Only sprite palettes 4-7 take
  part.

## APU

- **Boot ROM**: JFC uses a clean-room replacement (`BootRom`), not Nintendo's code. It writes
  `$BB` to port 1 *before* `$AA` to port 0, so a loader that sees `$AA` always finds `$BB`
  already there (a unit test caught the race the other way round). It re-enters cleanly at
  `$FFC0` (starts with CLRP and sets SP).
- Writes to `$F0-$FF` also reach the RAM underneath; the boot ROM area is RAM under the ROM.
- Timer targets of 0 mean 256. Timer outputs are 4-bit and clear when read.

## SuperFX (GSU)

Sources: Fullsnes-style register descriptions and well-known emulator behavior, from memory;
checked by `GsuTest`/`SuperFxTest` and by Star Fox and Yoshi's Island running.

- **Pipeline/delay slot**: the byte after any instruction that changes R15 (branches, JMP,
  LOOP, IWT/IBT/MOVE to R15) still executes. Branch targets are relative to the delay slot's
  address. STOP replaces its delay-slot byte with a NOP, so the next start begins cleanly.
- **Prefixes**: TO/FROM/WITH/ALTx don't clear the prefix state; every other instruction does
  (branches don't either). TO/FROM right after WITH mean MOVE/MOVES.
- **RAM words**: low byte at the address, high byte at `address ^ 1`.
- **MERGE** sets Z when any of bits 4-7/12-15 is set (a range test, not a zero test).
- **DIV2** is ASR except that -1 becomes 0.
- **Cache**: 512 bytes in 16-byte lines, indexed by the low 9 address bits; code within
  [CBR, CBR+512) is fetched through it. The console's window $3100+n is code address CBR+n.
  Writing a line's last byte marks it valid. Clearing G from the console resets CBR to 0.
- **Plotting** writes SNES tiles directly (character-mapped screen); see `PlotUnit`.
- **Bus sharing**: while the GSU runs with RON, console ROM reads return
  `00 01 00 01 04 01 00 01 00 01 08 01 00 01 0C 01` by address bits 3-0, so the native vectors
  read COP $0104, BRK $0100, NMI $0108, IRQ $010C (work RAM). Star Fox's own header even lists
  NMI $0108 / IRQ $010C. With RAN, console RAM reads are open bus.
- **RAM size**: Yoshi's Island declares 32 KiB at $FFBD (extended header, developer ID $33).
  Star Fox has no extended header; its board has 32 KiB, which is the default.
- STOP raises the cartridge IRQ unless CFGR bit 7 is set; reading $3031 acknowledges it.

## Game-specific observations (not bugs)

- **Super Mario World**: the opening "Welcome to Dinosaur Land" message ignores buttons for
  about 12 s after it opens. Timer `$1DF5` starts at `$B0` and drops every 4 frames (code at
  `$05:B1xx`). SMW **only saves** at save prompts (castles, ghost houses, switch palaces, star
  roads) and when a new file starts, not after ordinary levels. Its SRAM layout: 3 slots of `$8F`
  bytes plus a 2-byte checksum, with backups from `$1AD`.
- **Super Metroid**: the first ~30 s are sprite text ("1994", "METROID 3") over a Mode 7 scene
  with BG1 disabled. That's the real intro; the title appears around frame 2000 (Start skips).
  It's FastROM and runs from bank `$80`.
- **A Link to the Past**: has a 512-byte copier header in the user's dump (stripped).
- The Buffalo SNES pad (USB 0583:2060) shows in winmm as "Microsoft PC-joystick driver" with
  8 buttons in the order A, B, X, Y, L, R, Select, Start, which is the default mapping.

## References used

- WDC 65C816 datasheet cycle tables; SingleStepTests 65816 and SPC700 suites (MIT).
- Fullsnes/anomie-style register descriptions (from memory); behavior then checked by tests.
