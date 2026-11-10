package jfc.apu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApuHandshakeStubTest {

    private final ApuHandshakeStub apu = new ApuHandshakeStub();

    @Test
    void announcesReadinessLikeTheBootRom() {
        assertEquals(0xAA, apu.read(0));
        assertEquals(0xBB, apu.read(1));
    }

    @Test
    void echoesWhatTheCpuWroteWithinTwoReads() {
        apu.write(0, 0xCC);

        assertTrue(apu.read(0) == 0xCC || apu.read(0) == 0xCC);
    }

    @Test
    void readySignatureComesBackAfterTheCpuHasWritten() {
        apu.write(0, 0x05);
        apu.write(1, 0x00);

        boolean sawSignature = false;
        for (int i = 0; i < 2; i++) {
            int port0 = apu.read(0);
            int port1 = apu.read(1);
            sawSignature |= port0 == 0xAA && port1 == 0xBB;
        }

        assertTrue(sawSignature, "a 16-bit wait for $BBAA must succeed");
    }
}
