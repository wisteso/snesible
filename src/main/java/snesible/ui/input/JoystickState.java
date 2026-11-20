package snesible.ui.input;

/**
 * A snapshot of one joystick or gamepad, independent of the API that read it.
 *
 * @param x       horizontal stick or D-pad axis, -1.0 (left) to 1.0 (right)
 * @param y       vertical axis, -1.0 (up) to 1.0 (down)
 * @param buttons bit n set = button n held (button numbers as the device reports them)
 * @param pov     hat switch direction in degrees clockwise from up, or -1 when centered or absent
 */
public record JoystickState(double x, double y, int buttons, int pov) {
}
