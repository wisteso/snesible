package snesible.ui.lwjgl;

import static org.lwjgl.glfw.GLFW.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import snesible.ui.input.JoystickState;
import org.lwjgl.glfw.GLFWGamepadState;

/**
 * Direct access to the joysticks GLFW sees, without a window: for {@code --gamepad-test}, which
 * shows what the OpenGL window will read from each pad, and for {@link GlfwGamepads}, which
 * shares {@link #rawState}.
 *
 * <p>GLFW must be used from the main thread. {@link #open()} starts GLFW and {@link #close()}
 * stops it again, so don't use this while an {@link LwjglWindow} is open.
 */
public final class GlfwJoysticks implements AutoCloseable {

    private final GLFWGamepadState gamepadState = GLFWGamepadState.create();

    private GlfwJoysticks() {
    }

    /** Starts GLFW, or returns empty if it can't start. */
    public static Optional<GlfwJoysticks> open() {
        LwjglWindow.useFfmMemoryAccess();
        return glfwInit() ? Optional.of(new GlfwJoysticks()) : Optional.empty();
    }

    /** Lets GLFW notice pads being plugged in or out. */
    public void pollEvents() {
        glfwPollEvents();
    }

    /** The ids of the joysticks connected now. */
    public List<Integer> connected() {
        List<Integer> ids = new ArrayList<>();
        for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_LAST; jid++) {
            if (glfwJoystickPresent(jid)) {
                ids.add(jid);
            }
        }
        return ids;
    }

    /** One line about a joystick: its name, controls, and how the OpenGL window reads it. */
    public String describe(int jid) {
        FloatBuffer axes = glfwGetJoystickAxes(jid);
        ByteBuffer buttons = glfwGetJoystickButtons(jid);
        ByteBuffer hats = glfwGetJoystickHats(jid);
        String how = isGamepad(jid)
                ? "known gamepad \"" + glfwGetGamepadName(jid) + "\": standard layout, by position"
                : "not in GLFW's gamepad database: raw buttons through gamepad.properties";
        return String.format("Joystick %d: %s  (%d buttons, %d axes, %d hats; GUID %s)%n    %s",
                jid, glfwGetJoystickName(jid).trim(),
                buttons == null ? 0 : buttons.limit(), axes == null ? 0 : axes.limit(),
                hats == null ? 0 : hats.limit(), glfwGetJoystickGUID(jid), how);
    }

    /**
     * True if the pad is in SDL's controller database (bundled with GLFW). The OpenGL window
     * then maps its buttons by position and ignores {@code gamepad.properties}.
     */
    public boolean isGamepad(int jid) {
        return glfwJoystickIsGamepad(jid);
    }

    /** A known gamepad's SNES buttons, mapped by position as the OpenGL window does. */
    public int standardLayoutButtons(int jid) {
        return glfwGetGamepadState(jid, gamepadState) ? GlfwGamepads.fromStandardLayout(gamepadState) : 0;
    }

    /**
     * The joystick's raw state: its first two axes, its buttons as bits (button n = bit n), and
     * its first hat as an angle. Null if it's been unplugged.
     */
    public static JoystickState rawState(int jid) {
        if (!glfwJoystickPresent(jid)) {
            return null;
        }
        FloatBuffer axes = glfwGetJoystickAxes(jid);
        ByteBuffer buttons = glfwGetJoystickButtons(jid);
        ByteBuffer hats = glfwGetJoystickHats(jid);
        double x = axes != null && axes.limit() > 0 ? axes.get(0) : 0;
        double y = axes != null && axes.limit() > 1 ? axes.get(1) : 0;
        int bits = 0;
        for (int i = 0; buttons != null && i < buttons.limit() && i < 32; i++) {
            if (buttons.get(i) == GLFW_PRESS) {
                bits |= 1 << i;
            }
        }
        int pov = hats != null && hats.limit() > 0 ? GlfwGamepads.hatDegrees(hats.get(0)) : -1;
        return new JoystickState(x, y, bits, pov);
    }

    /** Stops GLFW. */
    @Override
    public void close() {
        glfwTerminate();
    }
}
