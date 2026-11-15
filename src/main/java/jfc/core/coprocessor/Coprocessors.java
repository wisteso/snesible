package jfc.core.coprocessor;

import jfc.core.cartridge.Cartridge;
import jfc.core.coprocessor.sa1.Sa1;
import jfc.core.coprocessor.superfx.SuperFx;

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
            case SA1 -> new Sa1(cartridge);
            case NONE, DSP, OTHER -> null;
        };
    }

    /** True when {@link #forCartridge} has an implementation for this cartridge's chip. */
    public static boolean isSupported(Cartridge cartridge) {
        return switch (cartridge.header().coprocessorType()) {
            case NONE, SUPER_FX, SA1 -> true;
            case DSP, OTHER -> false;
        };
    }
}
