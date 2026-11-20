package snesible.core.cpu.io;

/**
 * The 5A22's hardware multiplier and divider. The 65816 has no multiply or divide instructions,
 * so Nintendo put a small math unit beside it.
 *
 * <pre>
 *   $4202 WRMPYA  w  multiplicand (8-bit)
 *   $4203 WRMPYB  w  multiplier (8-bit), writing it starts an unsigned 8x8 multiply
 *   $4204 WRDIVL  w  dividend low byte
 *   $4205 WRDIVH  w  dividend high byte
 *   $4206 WRDIVB  w  divisor (8-bit), writing it starts an unsigned 16/8 divide
 *   $4214 RDDIVL  r  quotient low     $4215 RDDIVH  r  quotient high
 *   $4216 RDMPYL  r  product or remainder, low   $4217 RDMPYH  r  ... high
 * </pre>
 *
 * <p>The two operations share result registers because the hardware reuses one shift-and-add
 * circuit for both. After a multiply, the quotient register is left holding the multiplier,
 * the way the shifting leaves it.
 *
 * <p><i>Simplification:</i> on hardware the multiply takes 8 CPU cycles and the divide 16, and
 * reading early returns a partial result. Here the result is ready immediately. Correct games
 * wait out the delay anyway.
 */
public final class ArithmeticUnit {

    private int multiplicand = 0xFF;
    private int dividend = 0xFFFF;
    private int quotient;
    private int productOrRemainder;

    /** Writes $4202-$4206, given as the full address. */
    public void write(int address, int value) {
        switch (address) {
            case 0x4202 -> multiplicand = value;
            case 0x4203 -> {
                productOrRemainder = multiplicand * value;
                quotient = value;
            }
            case 0x4204 -> dividend = (dividend & 0xFF00) | value;
            case 0x4205 -> dividend = (dividend & 0x00FF) | (value << 8);
            case 0x4206 -> divide(value);
            default -> throw new IllegalArgumentException("Not an arithmetic register: " + address);
        }
    }

    /** Reads $4214-$4217, given as the full address. */
    public int read(int address) {
        return switch (address) {
            case 0x4214 -> quotient & 0xFF;
            case 0x4215 -> quotient >> 8;
            case 0x4216 -> productOrRemainder & 0xFF;
            case 0x4217 -> productOrRemainder >> 8;
            default -> throw new IllegalArgumentException("Not an arithmetic register: " + address);
        };
    }

    /**
     * Dividing by zero doesn't trap. The shift-and-subtract circuit just never subtracts,
     * leaving a quotient of $FFFF and the whole dividend as the remainder.
     */
    private void divide(int divisor) {
        if (divisor == 0) {
            quotient = 0xFFFF;
            productOrRemainder = dividend;
        } else {
            quotient = dividend / divisor;
            productOrRemainder = dividend % divisor;
        }
    }
}
