package jfc.core.cpu.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JoypadPortsTest {

    private final JoypadPorts ports = new JoypadPorts();

    @Test
    void autoReadDeliversButtonsInHardwareBitOrder() {
        ports.setPressed(0, JoypadPorts.BUTTON_B | JoypadPorts.BUTTON_A);

        ports.autoRead();

        assertEquals(0x80, ports.readAutoResult(0), "JOY1L bit 7 = A");
        assertEquals(0x80, ports.readAutoResult(1), "JOY1H bit 7 = B");
    }

    @Test
    void serialReadsReturnButtonsMsbFirstThenOnes() {
        ports.setPressed(0, JoypadPorts.BUTTON_B);
        ports.writeLatch(1);
        ports.writeLatch(0);

        assertEquals(1, ports.readSerial(0), "first bit is B");
        for (int i = 1; i < 16; i++) {
            assertEquals(0, ports.readSerial(0));
        }
        assertEquals(1, ports.readSerial(0), "after 16 bits a standard pad returns 1");
    }

    @Test
    void oppositeDirectionsCancelOut() {
        ports.setPressed(0, JoypadPorts.BUTTON_LEFT | JoypadPorts.BUTTON_RIGHT | JoypadPorts.BUTTON_UP);

        ports.autoRead();

        assertEquals(JoypadPorts.BUTTON_UP >> 8, ports.readAutoResult(1));
    }
}
