package jfc.ui.lwjgl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.glfw.GLFW.*;

import jfc.core.cpu.io.JoypadPorts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWGamepadState;

/** How GLFW gamepad states become SNES buttons (no pad or window needed). */
class GlfwGamepadsTest {

    @BeforeAll
    static void useFfm() {
        LwjglWindow.useFfmMemoryAccess();
    }

    @Test
    void faceButtonsMapByPositionLikeTheSnesDiamond() {
        assertEquals(JoypadPorts.BUTTON_B, pressed(GLFW_GAMEPAD_BUTTON_A)); // bottom
        assertEquals(JoypadPorts.BUTTON_A, pressed(GLFW_GAMEPAD_BUTTON_B)); // right
        assertEquals(JoypadPorts.BUTTON_Y, pressed(GLFW_GAMEPAD_BUTTON_X)); // left
        assertEquals(JoypadPorts.BUTTON_X, pressed(GLFW_GAMEPAD_BUTTON_Y)); // top
        assertEquals(JoypadPorts.BUTTON_SELECT, pressed(GLFW_GAMEPAD_BUTTON_BACK));
        assertEquals(JoypadPorts.BUTTON_START, pressed(GLFW_GAMEPAD_BUTTON_START));
        assertEquals(JoypadPorts.BUTTON_L, pressed(GLFW_GAMEPAD_BUTTON_LEFT_BUMPER));
        assertEquals(JoypadPorts.BUTTON_UP, pressed(GLFW_GAMEPAD_BUTTON_DPAD_UP));
    }

    @Test
    void triggersAndTheLeftStickWorkToo() {
        GLFWGamepadState state = idle();
        state.axes().put(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, 1f);
        state.axes().put(GLFW_GAMEPAD_AXIS_LEFT_X, -0.9f);
        state.axes().put(GLFW_GAMEPAD_AXIS_LEFT_Y, 0.9f); // positive is down
        assertEquals(JoypadPorts.BUTTON_R | JoypadPorts.BUTTON_LEFT | JoypadPorts.BUTTON_DOWN,
                GlfwGamepads.fromStandardLayout(state));
    }

    @Test
    void anIdlePadPressesNothing() {
        assertEquals(0, GlfwGamepads.fromStandardLayout(idle()));
    }

    @Test
    void hatsBecomeAnglesClockwiseFromUp() {
        assertEquals(0, GlfwGamepads.hatDegrees(GLFW_HAT_UP));
        assertEquals(135, GlfwGamepads.hatDegrees(GLFW_HAT_RIGHT_DOWN));
        assertEquals(270, GlfwGamepads.hatDegrees(GLFW_HAT_LEFT));
        assertEquals(-1, GlfwGamepads.hatDegrees(GLFW_HAT_CENTERED));
    }

    private static int pressed(int button) {
        GLFWGamepadState state = idle();
        state.buttons().put(button, (byte) GLFW_PRESS);
        return GlfwGamepads.fromStandardLayout(state);
    }

    /** Nothing pressed, sticks centered, triggers released (-1). */
    private static GLFWGamepadState idle() {
        GLFWGamepadState state = GLFWGamepadState.create();
        state.axes().put(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER, -1f);
        state.axes().put(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER, -1f);
        return state;
    }
}
