package jfc.core.coprocessor.sa1;

/**
 * The SA-1's arithmetic unit: multiply, divide, and multiply-accumulate, with results ready
 * immediately (the console's own math unit at $4202 needs several cycles).
 *
 * <pre>
 *   $2250 MCNT  w  bit 1 cumulative sum mode, bit 0 divide (else multiply)
 *   $2251/2 MA  w  multiplicand / dividend, signed 16-bit
 *   $2253/4 MB  w  multiplier / divisor; writing $2254 performs the operation
 *   $2306-A MR  r  result, 40 bits
 *   $230B   OF  r  bit 7: the cumulative sum overflowed 40 bits
 * </pre>
 * Results:
 * <ul>
 *   <li>multiply: MA * MB, both signed, 32-bit signed product;</li>
 *   <li>divide: signed MA by unsigned MB; quotient in MR bits 0-15, remainder (always
 *       positive) in bits 16-31; dividing by zero gives 0;</li>
 *   <li>cumulative sum: MR += MA * MB, kept to 40 bits (sums of products, e.g. for matrix
 *       math).</li>
 * </ul>
 * MB is cleared after each operation, and MA after a division, as on the chip.
 */
final class Sa1Arithmetic {

    private static final long FORTY_BITS = (1L << 40) - 1;

    private boolean divide;
    private boolean cumulative;
    private int ma;
    private int mb;
    private long result;
    private boolean overflow;

    void reset() {
        divide = cumulative = overflow = false;
        ma = mb = 0;
        result = 0;
    }

    void writeControl(int value) {
        divide = (value & 0x01) != 0;
        cumulative = (value & 0x02) != 0;
        if (cumulative) {
            result = 0; // starting a sum clears the accumulator
        }
    }

    void writeMaLow(int value) {
        ma = (ma & 0xFF00) | value;
    }

    void writeMaHigh(int value) {
        ma = (value << 8) | (ma & 0x00FF);
    }

    void writeMbLow(int value) {
        mb = (mb & 0xFF00) | value;
    }

    /** Writing MB's high byte starts the operation. */
    void writeMbHigh(int value) {
        mb = (value << 8) | (mb & 0x00FF);
        if (cumulative) {
            long sum = result + (long) (short) ma * (short) mb;
            overflow = sum > FORTY_BITS || sum < 0;
            result = sum & FORTY_BITS;
        } else if (!divide) {
            result = ((short) ma * (short) mb) & 0xFFFFFFFFL;
        } else {
            if (mb == 0) {
                result = 0;
            } else {
                int dividend = (short) ma;
                // Math.floorMod keeps the remainder positive; the quotient then rounds down.
                int remainder = Math.floorMod(dividend, mb);
                int quotient = (dividend - remainder) / mb;
                result = ((long) remainder << 16 | (quotient & 0xFFFF)) & 0xFFFFFFFFL;
            }
            ma = 0;
        }
        mb = 0;
    }

    /** $2306-$230A: byte {@code index} (0-4) of the result. */
    int readResult(int index) {
        return (int) (result >> (8 * index)) & 0xFF;
    }

    /** $230B. */
    int readOverflow() {
        return overflow ? 0x80 : 0x00;
    }
}
