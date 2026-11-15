package jfc.ui;

/**
 * The keys every window understands, in one place so the Swing and OpenGL windows agree and the
 * help text stays true.
 *
 * <p>Controller 1: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
 * Shift or Backspace = Select. Window keys: F2 = next picture mode, F3 = NTSC scanlines,
 * F9 = look for newly plugged-in gamepads (Swing window), F11 = fullscreen (OpenGL window),
 * Escape = quit.
 */
public final class Controls {

    /** Printed when a game starts, so the key mapping is easy to find. */
    public static final String HELP = """
            Controls:
              D-pad         Arrow keys
              A / B         X / Z
              X / Y         S / A
              L / R         Q / W
              Start         Enter
              Select        Shift or Backspace
              Picture mode  F2 (sharp pixels, NTSC composite, S-Video, RGB, ...)
              Scanlines     F3 (with an NTSC mode)
              Find gamepads F9 (Swing window; the OpenGL window finds them itself)
              Fullscreen    F11 (OpenGL window)
              Quit          Escape""";

    private Controls() {
    }
}
