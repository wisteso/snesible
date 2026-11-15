package jfc.core.cpu.io;

import jfc.core.cpu.Cpu;
import jfc.core.ppu.Ppu;
import jfc.core.system.MasterClock;

/**
 * The 5A22's interrupt hardware: vertical-blank NMI and the H/V timer IRQ.
 *
 * <p>The 65816 core only has NMI and IRQ input pins; this logic beside it decides when to pull
 * them, based on the beam position.
 * <ul>
 *   <li><b>NMI</b> fires at the start of vertical blank, if enabled. It's how nearly every game
 *       paces itself: the main loop does a frame's work, then waits; the NMI handler updates
 *       video memory while the PPU isn't drawing.</li>
 *   <li><b>IRQ</b> fires when the beam reaches a programmed position. It's used for mid-frame
 *       effects such as switching scroll for a status bar.</li>
 * </ul>
 *
 * <pre>
 *   $4200 NMITIMEN  w  bit 7 NMI enable, bits 5-4 IRQ mode (0 off, 1 H, 2 V, 3 H and V),
 *                      bit 0 automatic joypad read
 *   $4207/$4208     w  HTIME, 9-bit dot position for the H-IRQ
 *   $4209/$420A     w  VTIME, 9-bit line for the V-IRQ
 *   $4210 RDNMI     r  bit 7: NMI occurred (cleared by reading); bits 3-0 CPU version
 *   $4211 TIMEUP    r  bit 7: IRQ occurred (reading clears it and releases the IRQ line)
 *   $4212 HVBJOY    r  bit 7 in vblank, bit 6 in hblank, bit 0 auto joypad read in progress
 * </pre>
 *
 * <p>The read methods take the current open-bus value: bits these registers don't drive
 * keep whatever was last on the data bus.
 */
public final class InterruptControl {

    /** H-blank begins at about dot 274 and ends just after dot 0 of the next line. */
    private static final int HBLANK_START_H = 274 * MasterClock.CYCLES_PER_DOT;
    private static final int HBLANK_END_H = 1 * MasterClock.CYCLES_PER_DOT;
    /** The automatic joypad read takes about three scanlines (4224 master cycles). */
    private static final int AUTO_JOYPAD_LINES = 3;
    private static final int CPU_VERSION = 2;

    private Cpu cpu;
    private final MasterClock clock;
    private final Ppu ppu;

    private boolean nmiEnabled;
    private int irqMode;
    private boolean autoJoypadEnabled;
    private int hTime = 0x1FF;
    private int vTime = 0x1FF;

    private boolean inVblank;
    private boolean nmiFlag;
    private boolean irqFlag;
    /**
     * The cartridge connector's own IRQ line (driven by a coprocessor such as the SuperFX). It's
     * wired-OR with the timer IRQ: the CPU sees an interrupt while either one is asserted.
     */
    private boolean cartridgeIrq;

    public InterruptControl(MasterClock clock, Ppu ppu) {
        this.clock = clock;
        this.ppu = ppu;
    }

    /** Attaches the CPU whose NMI and IRQ inputs this logic drives. */
    public void connect(Cpu cpu) {
        this.cpu = cpu;
    }

    public boolean isAutoJoypadEnabled() {
        return autoJoypadEnabled;
    }

    /** $4200 NMITIMEN. */
    public void writeNmitimen(int value) {
        boolean wasNmiEnabled = nmiEnabled;
        nmiEnabled = (value & 0x80) != 0;
        irqMode = (value >> 4) & 3;
        autoJoypadEnabled = (value & 0x01) != 0;
        // Enabling NMI in the middle of vertical blank, before the flag has been read, fires
        // the NMI straight away: the NMI line is the AND of the flag and the enable bit, and
        // the CPU reacts to its rising edge.
        if (!wasNmiEnabled && nmiEnabled && nmiFlag) {
            cpu.triggerNmi();
        }
        if (irqMode == 0) {
            setIrqFlag(false);
        }
    }

    /** $4207-$420A: HTIME low/high, VTIME low/high. {@code register} is 0-3. */
    public void writeTimer(int register, int value) {
        switch (register) {
            case 0 -> hTime = (hTime & 0x100) | value;
            case 1 -> hTime = (hTime & 0x0FF) | ((value & 1) << 8);
            case 2 -> vTime = (vTime & 0x100) | value;
            case 3 -> vTime = (vTime & 0x0FF) | ((value & 1) << 8);
            default -> throw new IllegalArgumentException("Timer register " + register);
        }
    }

    /** $4210 RDNMI. */
    public int readRdnmi(int openBus) {
        int value = (nmiFlag ? 0x80 : 0) | (openBus & 0x70) | CPU_VERSION;
        nmiFlag = false;
        return value;
    }

    /** $4211 TIMEUP. */
    public int readTimeup(int openBus) {
        int value = (irqFlag ? 0x80 : 0) | (openBus & 0x7F);
        setIrqFlag(false);
        return value;
    }

    /** $4212 HVBJOY. */
    public int readHvbjoy(int openBus) {
        int h = clock.h();
        boolean inHblank = h >= HBLANK_START_H || h < HBLANK_END_H;
        int linesIntoVblank = clock.v() - ppu.vblankStartLine();
        boolean joypadBusy = autoJoypadEnabled && linesIntoVblank >= 0 && linesIntoVblank < AUTO_JOYPAD_LINES;
        return (inVblank ? 0x80 : 0) | (inHblank ? 0x40 : 0) | (openBus & 0x3E) | (joypadBusy ? 0x01 : 0);
    }

    /** Vertical blank begins and ends at fixed lines, raising NMI on the way in. */
    public void onLineStart(int line) {
        if (line == ppu.vblankStartLine()) {
            inVblank = true;
            nmiFlag = true;
            if (nmiEnabled) {
                cpu.triggerNmi();
            }
        } else if (line == 0) {
            inVblank = false;
            nmiFlag = false;
        }
    }

    /**
     * Fires the timer IRQ when the beam passes the programmed position:
     * <ul>
     *   <li>mode 1 (H): at dot HTIME on every line</li>
     *   <li>mode 2 (V): at the start of line VTIME</li>
     *   <li>mode 3 (H and V): at dot HTIME on line VTIME</li>
     * </ul>
     * <i>Simplification:</i> hardware fires a few cycles after the exact dot; we fire on it.
     */
    public void onBeamMoved(int line, int fromH, int toH) {
        if (irqMode == 0) {
            return;
        }
        boolean lineMatches = irqMode == 1 || line == vTime;
        int targetH = irqMode == 2 ? 0 : hTime * MasterClock.CYCLES_PER_DOT;
        if (lineMatches && fromH <= targetH && targetH < toH) {
            setIrqFlag(true);
        }
    }

    /** The IRQ line stays asserted until software acknowledges it by reading $4211. */
    private void setIrqFlag(boolean raised) {
        irqFlag = raised;
        cpu.setIrqLine(irqFlag || cartridgeIrq);
    }

    /**
     * Drives the cartridge's IRQ line. The coprocessor that raises it also decides when it's
     * acknowledged (the SuperFX, for example, when its status register is read), so TIMEUP
     * ($4211) doesn't clear it.
     */
    public void setCartridgeIrq(boolean asserted) {
        cartridgeIrq = asserted;
        cpu.setIrqLine(irqFlag || cartridgeIrq);
    }
}
