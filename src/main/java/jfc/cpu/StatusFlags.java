package jfc.cpu;

/**
 * The 65816 processor status register, P.
 *
 * <pre>
 *   bit 7  N  negative     result's top bit was set
 *   bit 6  V  overflow     signed arithmetic overflowed
 *   bit 5  M  memory/acc.  1 = accumulator and memory operations are 8-bit, 0 = 16-bit
 *   bit 4  X  index        1 = X and Y are 8-bit, 0 = 16-bit
 *   bit 3  D  decimal      ADC/SBC use binary-coded decimal
 *   bit 2  I  IRQ disable  1 = ignore the IRQ line
 *   bit 1  Z  zero         result was zero
 *   bit 0  C  carry        carry out of addition / no borrow out of subtraction / shifted-out bit
 * </pre>
 *
 * <p>M and X are what make the 65816 more than a 6502. The same opcode moves 8 or 16 bits
 * depending on them, so the meaning of every load, store and arithmetic instruction depends on
 * this register. In emulation mode both widths are fixed at 8 bits. The 6502 used bit 4 as the
 * "break" marker in pushed copies of P, and the 65816 does the same in emulation mode.
 *
 * <p>Each flag is a separate boolean, which keeps the instruction code readable
 * ({@code carry = ...}). The byte form is only needed for pushes, pulls and REP/SEP.
 */
public final class StatusFlags {

    public static final int CARRY = 0x01;
    public static final int ZERO = 0x02;
    public static final int IRQ_DISABLE = 0x04;
    public static final int DECIMAL = 0x08;
    public static final int INDEX_8BIT = 0x10;
    public static final int MEMORY_8BIT = 0x20;
    public static final int OVERFLOW = 0x40;
    public static final int NEGATIVE = 0x80;

    public boolean negative;
    public boolean overflow;
    /** M flag: the accumulator and memory operands are 8-bit (native mode only). */
    public boolean memory8;
    /** X flag: the index registers are 8-bit (native mode only). */
    public boolean index8;
    public boolean decimal;
    public boolean irqDisable;
    public boolean zero;
    public boolean carry;

    /** Packs the flags into the byte layout above. */
    public int toByte() {
        return (negative ? NEGATIVE : 0)
                | (overflow ? OVERFLOW : 0)
                | (memory8 ? MEMORY_8BIT : 0)
                | (index8 ? INDEX_8BIT : 0)
                | (decimal ? DECIMAL : 0)
                | (irqDisable ? IRQ_DISABLE : 0)
                | (zero ? ZERO : 0)
                | (carry ? CARRY : 0);
    }

    /**
     * Unpacks a byte into the flags, exactly as given. Rules such as "emulation mode forces
     * 8-bit registers" belong to the CPU, not to this class.
     */
    public void setFromByte(int value) {
        negative = (value & NEGATIVE) != 0;
        overflow = (value & OVERFLOW) != 0;
        memory8 = (value & MEMORY_8BIT) != 0;
        index8 = (value & INDEX_8BIT) != 0;
        decimal = (value & DECIMAL) != 0;
        irqDisable = (value & IRQ_DISABLE) != 0;
        zero = (value & ZERO) != 0;
        carry = (value & CARRY) != 0;
    }

    /** The conventional "NVMXDIZC" display, upper case for set flags: e.g. {@code nvMXdIzc}. */
    @Override
    public String toString() {
        String letters = "NVMXDIZC";
        int bits = toByte();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            char letter = letters.charAt(i);
            boolean set = (bits & (0x80 >> i)) != 0;
            sb.append(set ? letter : Character.toLowerCase(letter));
        }
        return sb.toString();
    }
}
