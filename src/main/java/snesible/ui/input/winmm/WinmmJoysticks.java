package snesible.ui.input.winmm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import snesible.ui.input.JoystickState;

/**
 * Reads gamepads through Windows' original joystick API in {@code winmm.dll}.
 *
 * <p>This is the oldest and simplest Windows joystick interface (it dates from Windows 3.1),
 * and it handles plain USB gamepads like SNES-style pads well. Xbox-style "XInput" controllers
 * only partly show up through it. The plan is to replace this class with LWJGL later.
 *
 * <p>It's called through Java's Foreign Function &amp; Memory API (final in Java 22): we look up
 * the C functions in the DLL, describe their signatures, and pass C structs as raw memory
 * segments, all without JNI or native code of our own. Running it without the JVM option
 * {@code --enable-native-access=ALL-UNNAMED} prints a warning (the jar's manifest sets it).
 *
 * <p>The two functions used:
 * <pre>
 *   MMRESULT joyGetPosEx(UINT id, JOYINFOEX* info)          current position and buttons
 *   MMRESULT joyGetDevCapsW(UINT_PTR id, JOYCAPSW* caps, UINT size)   name, ranges, features
 * </pre>
 */
public final class WinmmJoysticks {

    /** winmm supports joystick ids 0-15. */
    public static final int MAX_JOYSTICKS = 16;
    private static final int JOYERR_NOERROR = 0;

    // JOYINFOEX: thirteen 32-bit fields.
    private static final int JOYINFOEX_SIZE = 52;
    private static final int INFO_FLAGS = 4;
    private static final int INFO_X = 8;
    private static final int INFO_Y = 12;
    private static final int INFO_BUTTONS = 32;
    private static final int INFO_POV = 40;
    /** JOY_RETURNALL: fill in every field. */
    private static final int RETURN_ALL = 0xFF;
    private static final int POV_CENTERED = 0xFFFF;

    // JOYCAPSW: two 16-bit ids, a 32-character UTF-16 name, then mostly 32-bit fields.
    private static final int JOYCAPSW_SIZE = 728;
    private static final int CAPS_VENDOR_ID = 0;
    private static final int CAPS_PRODUCT_ID = 2;
    private static final int CAPS_NAME = 4;
    private static final int CAPS_NAME_BYTES = 64;
    private static final int CAPS_X_MIN = 68;
    private static final int CAPS_X_MAX = 72;
    private static final int CAPS_Y_MIN = 76;
    private static final int CAPS_Y_MAX = 80;
    private static final int CAPS_BUTTON_COUNT = 92;
    private static final int CAPS_FLAGS = 128;
    /** JOYCAPS_HASPOV: the device has a hat switch. */
    private static final int HAS_POV = 0x10;

    /** What we learn about a device once, when it's found. */
    public record DeviceInfo(int id, String name, int vendorId, int productId, int buttonCount,
                             boolean hasPov, int xMin, int xMax, int yMin, int yMax) {
    }

    private final MethodHandle joyGetPosEx;
    private final MethodHandle joyGetDevCaps;
    /** Native memory for the structs, reused on every call (one thread only). */
    private final MemorySegment info;
    private final MemorySegment caps;

    private WinmmJoysticks(MethodHandle joyGetPosEx, MethodHandle joyGetDevCaps) {
        this.joyGetPosEx = joyGetPosEx;
        this.joyGetDevCaps = joyGetDevCaps;
        Arena arena = Arena.ofAuto();
        this.info = arena.allocate(JOYINFOEX_SIZE);
        this.caps = arena.allocate(JOYCAPSW_SIZE);
    }

    /** Loads winmm.dll, or returns empty when it isn't available (i.e. not on Windows). */
    public static Optional<WinmmJoysticks> open() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return Optional.empty();
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup winmm = SymbolLookup.libraryLookup("winmm", Arena.global());
            MethodHandle getPos = linker.downcallHandle(
                    winmm.find("joyGetPosEx").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MethodHandle getCaps = linker.downcallHandle(
                    winmm.find("joyGetDevCapsW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            return Optional.of(new WinmmJoysticks(getPos, getCaps));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Lists the joysticks that are plugged in right now. */
    public List<DeviceInfo> findDevices() {
        List<DeviceInfo> devices = new ArrayList<>();
        for (int id = 0; id < MAX_JOYSTICKS; id++) {
            // A device only counts as present if it answers a position query.
            if (readPosition(id) && readCaps(id)) {
                devices.add(new DeviceInfo(
                        id,
                        readName(),
                        caps.get(ValueLayout.JAVA_SHORT, CAPS_VENDOR_ID) & 0xFFFF,
                        caps.get(ValueLayout.JAVA_SHORT, CAPS_PRODUCT_ID) & 0xFFFF,
                        caps.get(ValueLayout.JAVA_INT, CAPS_BUTTON_COUNT),
                        (caps.get(ValueLayout.JAVA_INT, CAPS_FLAGS) & HAS_POV) != 0,
                        caps.get(ValueLayout.JAVA_INT, CAPS_X_MIN),
                        caps.get(ValueLayout.JAVA_INT, CAPS_X_MAX),
                        caps.get(ValueLayout.JAVA_INT, CAPS_Y_MIN),
                        caps.get(ValueLayout.JAVA_INT, CAPS_Y_MAX)));
            }
        }
        return devices;
    }

    /** Reads a device's current state, or returns null if it has been unplugged. */
    public JoystickState poll(DeviceInfo device) {
        if (!readPosition(device.id())) {
            return null;
        }
        double x = normalize(info.get(ValueLayout.JAVA_INT, INFO_X), device.xMin(), device.xMax());
        double y = normalize(info.get(ValueLayout.JAVA_INT, INFO_Y), device.yMin(), device.yMax());
        int buttons = info.get(ValueLayout.JAVA_INT, INFO_BUTTONS);
        int rawPov = info.get(ValueLayout.JAVA_INT, INFO_POV);
        // The POV field holds hundredths of a degree. Devices without a hat still fill it in
        // (often with 0, which would mean "up"), so only trust it when the caps say there's a hat.
        int pov = device.hasPov() && rawPov != POV_CENTERED ? rawPov / 100 : -1;
        return new JoystickState(x, y, buttons, pov);
    }

    private boolean readPosition(int id) {
        info.set(ValueLayout.JAVA_INT, 0, JOYINFOEX_SIZE); // dwSize must be filled in first
        info.set(ValueLayout.JAVA_INT, INFO_FLAGS, RETURN_ALL);
        try {
            return (int) joyGetPosEx.invokeExact(id, info) == JOYERR_NOERROR;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean readCaps(int id) {
        try {
            return (int) joyGetDevCaps.invokeExact((long) id, caps, JOYCAPSW_SIZE) == JOYERR_NOERROR;
        } catch (Throwable e) {
            return false;
        }
    }

    private String readName() {
        byte[] bytes = caps.asSlice(CAPS_NAME, CAPS_NAME_BYTES).toArray(ValueLayout.JAVA_BYTE);
        String name = new String(bytes, StandardCharsets.UTF_16LE);
        int end = name.indexOf('\0');
        return (end >= 0 ? name.substring(0, end) : name).trim();
    }

    /** Maps a raw axis reading from [min, max] to [-1.0, 1.0]. */
    private static double normalize(int value, int min, int max) {
        if (max <= min) {
            return 0;
        }
        return 2.0 * (value - min) / (max - min) - 1.0;
    }
}
