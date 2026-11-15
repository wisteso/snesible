package jfc.core.cpu;

/**
 * Where an instruction's operand lives, as computed by an addressing mode.
 *
 * <p>Knowing the first byte's address isn't quite enough. A 16-bit operand has a second byte,
 * and the 65816 finds it in one of two ways depending on the addressing mode:
 * <ul>
 *   <li><b>Within the bank</b>: immediate operands (read through PC), direct page and stack
 *       addresses wrap around inside their bank. A word at $00:FFFF has its high byte at
 *       $00:0000.</li>
 *   <li><b>Linear</b>: data reached through absolute, long and indirect modes uses the full
 *       24-bit address, so a word at $7E:FFFF has its high byte at $7F:0000.</li>
 * </ul>
 *
 * @param address         24-bit address of the operand's first (low) byte
 * @param wrapsWithinBank true if the following bytes wrap inside the same 64 KiB bank
 */
record EffectiveAddress(int address, boolean wrapsWithinBank) {

    static EffectiveAddress inBank(int bank, int offset) {
        return new EffectiveAddress((bank << 16) | (offset & 0xFFFF), true);
    }

    static EffectiveAddress linear(int address) {
        return new EffectiveAddress(address & 0xFFFFFF, false);
    }

    /** The address of the byte {@code n} positions after the first one. */
    int plus(int n) {
        if (wrapsWithinBank) {
            return (address & 0xFF0000) | ((address + n) & 0xFFFF);
        }
        return (address + n) & 0xFFFFFF;
    }
}
