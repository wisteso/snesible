package snesible.core.cpu.io;

/**
 * The two controller ports and the automatic joypad reader.
 *
 * <p>A SNES controller is a 16-bit shift register. Software pulses the latch line ($4016 bit 0)
 * to snapshot the buttons, then reads them one bit per read from $4016 (port 1) or $4017
 * (port 2), the same way NES controllers work. After the 16 bits, a standard controller keeps
 * returning 1.
 *
 * <p>Since doing that by hand every frame is tedious, the 5A22 can do it automatically at the
 * start of vertical blank (NMITIMEN bit 0) and leave the results in $4218-$421F, which is
 * what most games use.
 *
 * <p>Bits are returned (and stored in the auto-read registers) in this order, MSB first:
 * <pre>
 *   15 B   14 Y   13 Select   12 Start   11 Up   10 Down   9 Left   8 Right
 *    7 A    6 X    5 L         4 R        3-0  controller ID (0000 for a standard pad)
 * </pre>
 */
public final class JoypadPorts {

    public static final int BUTTON_B = 0x8000;
    public static final int BUTTON_Y = 0x4000;
    public static final int BUTTON_SELECT = 0x2000;
    public static final int BUTTON_START = 0x1000;
    public static final int BUTTON_UP = 0x0800;
    public static final int BUTTON_DOWN = 0x0400;
    public static final int BUTTON_LEFT = 0x0200;
    public static final int BUTTON_RIGHT = 0x0100;
    public static final int BUTTON_A = 0x0080;
    public static final int BUTTON_X = 0x0040;
    public static final int BUTTON_L = 0x0020;
    public static final int BUTTON_R = 0x0010;

    /** The buttons currently held on each controller, set by the user interface. */
    private final int[] pressed = new int[2];
    /** The shift registers inside each controller. */
    private final int[] shiftRegister = new int[2];
    private boolean latch;
    /** $4218-$421F: JOY1-JOY4 results of the automatic read (ports 3 and 4 need a multitap). */
    private final int[] autoReadResults = new int[4];

    /**
     * Updates the buttons held on controller {@code port} (0 or 1).
     *
     * <p>A real D-pad is a rocker: it physically can't press left and right (or up and down) at
     * once. A keyboard can, and games were never tested against it. Some glitch badly
     * (speedrunners exploit it in A Link to the Past), so opposite directions held together
     * cancel out here.
     */
    public void setPressed(int port, int buttons) {
        if ((buttons & (BUTTON_LEFT | BUTTON_RIGHT)) == (BUTTON_LEFT | BUTTON_RIGHT)) {
            buttons &= ~(BUTTON_LEFT | BUTTON_RIGHT);
        }
        if ((buttons & (BUTTON_UP | BUTTON_DOWN)) == (BUTTON_UP | BUTTON_DOWN)) {
            buttons &= ~(BUTTON_UP | BUTTON_DOWN);
        }
        pressed[port] = buttons & 0xFFF0;
    }

    /** $4016 write: bit 0 drives the latch line of both controllers. */
    public void writeLatch(int value) {
        latch = (value & 1) != 0;
        if (latch) {
            reloadShiftRegisters();
        }
    }

    /** $4016 / $4017 read: the next serial bit from a port, in bit 0. */
    public int readSerial(int port) {
        if (latch) {
            // While the latch is held, the controller keeps reloading and always shows bit 15.
            reloadShiftRegisters();
        }
        int bit = (shiftRegister[port] >> 15) & 1;
        // Shift in 1s behind the data, which is what a standard controller returns after 16 reads.
        shiftRegister[port] = ((shiftRegister[port] << 1) | 1) & 0xFFFF;
        return bit;
    }

    /** Performs the automatic read: latch, then clock 16 bits out of each port. */
    public void autoRead() {
        reloadShiftRegisters();
        for (int port = 0; port < 2; port++) {
            autoReadResults[port] = shiftRegister[port];
            shiftRegister[port] = 0xFFFF;
        }
    }

    /** $4218-$421F: {@code register} 0-7 is JOY1L, JOY1H, JOY2L, ... */
    public int readAutoResult(int register) {
        int value = autoReadResults[register / 2];
        return (register & 1) == 0 ? value & 0xFF : value >> 8;
    }

    private void reloadShiftRegisters() {
        shiftRegister[0] = pressed[0];
        shiftRegister[1] = pressed[1];
    }
}
