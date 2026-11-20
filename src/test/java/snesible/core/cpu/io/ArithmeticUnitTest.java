package snesible.core.cpu.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArithmeticUnitTest {

    private final ArithmeticUnit math = new ArithmeticUnit();

    @Test
    void multipliesUnsigned8By8() {
        math.write(0x4202, 0xFF);
        math.write(0x4203, 0xFF);

        assertEquals(0xFE01, product());
    }

    @Test
    void dividesUnsigned16By8() {
        math.write(0x4204, 0x34);
        math.write(0x4205, 0x12);
        math.write(0x4206, 0x10);

        assertEquals(0x0123, quotient());
        assertEquals(0x0004, product(), "remainder shares the product registers");
    }

    @Test
    void divisionByZeroGivesAllOnesAndKeepsTheDividend() {
        math.write(0x4204, 0x78);
        math.write(0x4205, 0x56);
        math.write(0x4206, 0x00);

        assertEquals(0xFFFF, quotient());
        assertEquals(0x5678, product());
    }

    private int product() {
        return math.read(0x4216) | (math.read(0x4217) << 8);
    }

    private int quotient() {
        return math.read(0x4214) | (math.read(0x4215) << 8);
    }
}
