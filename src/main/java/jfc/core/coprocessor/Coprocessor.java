package jfc.core.coprocessor;

import jfc.core.cartridge.CartridgeSlot;

/**
 * A processor on the cartridge board that runs alongside the console's own CPU.
 *
 * <p>Several SNES games carry an extra chip to do work the console couldn't do fast enough:
 * the SuperFX draws polygons and sprites into a bitmap, the SA-1 is a second, faster 65816.
 * Such a chip sits between the console and the cartridge memories, so a coprocessor board
 * takes over the whole cartridge address space ({@link CartridgeSlot}): it decodes ROM and RAM
 * addresses itself, adds its own registers, and may hide the ROM or RAM from the console while
 * it's using them.
 *
 * <h2>Timing: catch-up, like the APU</h2>
 * The coprocessor has its own clock but doesn't drive the emulation. It falls behind while the
 * console CPU runs, and {@link #catchUp()} runs it until it has caught up with the master clock.
 * That happens:
 * <ul>
 *   <li>whenever the console touches the board (any {@link #read}/{@link #write}), since that's
 *       the only way the console can observe the chip;</li>
 *   <li>at the start of every scanline (from {@code Snes}), so an interrupt the chip raises
 *       reaches the CPU within about 64 microseconds even if the CPU is just waiting for it.</li>
 * </ul>
 *
 * <h2>Connection to the console</h2>
 * The cartridge connector carries one interrupt line ({@code /IRQ}) back to the CPU, shared with
 * the console's own timer IRQ. That and the master clock are all a coprocessor needs from the
 * console, so it gets them through the small {@link Host} interface, which keeps this package
 * independent of {@code jfc.core.system}.
 */
public interface Coprocessor extends CartridgeSlot {

    /** What the console provides to a coprocessor. */
    interface Host {
        /** Master cycles elapsed since power-on: the time the coprocessor must catch up to. */
        long masterCycles();

        /** Drives the cartridge's IRQ line to the CPU (true = asserted). */
        void setIrqLine(boolean asserted);
    }

    /** Called once, before the first reset, by the console that owns this board. */
    void connect(Host host);

    /** The reset button: the chip stops and its registers return to their power-on state. */
    void reset();

    /** Runs the chip until it has caught up with the master clock. */
    void catchUp();
}
