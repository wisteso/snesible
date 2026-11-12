package jfc.coprocessor;

import jfc.cartridge.Cartridge;
import jfc.coprocessor.superfx.SuperFx;

/** Picks the coprocessor board a cartridge needs, from its header. */
public final class Coprocessors {

    private Coprocessors() {
    }

    /**
     * Builds the coprocessor for a cartridge.
     *
     * @return the board, or null when the cartridge is a plain one, or when its chip isn't
     *         emulated (such a game will usually crash or hang, as it would without the chip)
     */
    public static Coprocessor forCartridge(Cartridge cartridge) {
        return switch (cartridge.header().coprocessorType()) {
            case SUPER_FX -> new SuperFx(cartridge);
            // The SA-1 would slot in here: a second jfc.cpu.Cpu on its own bus.
            case NONE, DSP, SA1, OTHER -> null;
        };
    }

    /** True when {@link #forCartridge} has an implementation for this cartridge's chip. */
    public static boolean isSupported(Cartridge cartridge) {
        return switch (cartridge.header().coprocessorType()) {
            case NONE, SUPER_FX -> true;
            case DSP, SA1, OTHER -> false;
        };
    }
}
