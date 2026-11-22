package snesible.ui;

/**
 * Which operating system we're on, for the few places the frontend behaves differently: macOS
 * needs GLFW on the process's first thread and has no use for the borderless-fullscreen trick,
 * the Swing window's gamepads go through Windows' winmm, and {@code gamepad.properties} can
 * have sections for one system only.
 *
 * <p>The console itself ({@code snesible.core}) never needs this: it runs the same everywhere.
 */
public final class Platform {

    private static final String OS_NAME = System.getProperty("os.name", "");

    private Platform() {
    }

    /** True on macOS ("Mac OS X" in Java's {@code os.name}, on Intel and Apple silicon alike). */
    public static boolean isMac() {
        return OS_NAME.startsWith("Mac");
    }

    /** True on Windows ("Windows 11", "Windows 10", ...). */
    public static boolean isWindows() {
        return OS_NAME.startsWith("Windows");
    }

    /** True on Linux. */
    public static boolean isLinux() {
        return OS_NAME.startsWith("Linux");
    }

    /**
     * A short name for this system, as {@code gamepad.properties} sections use it:
     * {@code "windows"}, {@code "mac"}, {@code "linux"}, or {@code "other"}.
     */
    public static String shortName() {
        return isWindows() ? "windows" : isMac() ? "mac" : isLinux() ? "linux" : "other";
    }
}
