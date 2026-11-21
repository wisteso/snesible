package snesible.core.cartridge;

/**
 * The extra chip, if any, that a cartridge board carries next to its ROM, as named by the
 * header's cartridge type byte. Only the ones that matter to this emulator get their own name.
 */
public enum CoprocessorType {
    /** A plain board: ROM, and maybe SRAM. */
    NONE,
    /** NEC DSP-1/2/3/4 math chips (3D projection and flight math). Not emulated. */
    DSP,
    /** The SuperFX / GSU RISC graphics processor (polygons, scaled and rotated sprites). */
    SUPER_FX,
    /** The SA-1, a faster 65816 with its own memory. */
    SA1,
    /** Anything else (S-DD1, OBC1, S-RTC, custom chips). Not emulated. */
    OTHER
}
