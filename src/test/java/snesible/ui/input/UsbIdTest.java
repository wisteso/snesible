package snesible.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UsbIdTest {

    @Test
    void readsVendorAndProductFromAnSdlGuid() {
        // GUIDs GLFW reported on macOS: a pad with paddles in its D-input mode, and the same
        // pad in its Switch mode (Nintendo's numbers).
        assertEquals(new UsbId(0x2dc8, 0x6009), UsbId.fromSdlGuid("03000000c82d00000960000000020000"));
        assertEquals(new UsbId(0x057e, 0x2009), UsbId.fromSdlGuid("030000007e0500000920000000020000"));
    }

    @Test
    void guidsWithoutTheNumbersGiveNull() {
        assertNull(UsbId.fromSdlGuid("78696e70757401000000000000000000")); // Windows XInput: "xinput"
        assertNull(UsbId.fromSdlGuid("short"));
        assertNull(UsbId.fromSdlGuid(null));
    }

    @Test
    void parsesAndWritesFourHexDigitsEach() {
        assertEquals(new UsbId(0x2dc8, 0x6009), UsbId.parse("2DC8:6009"));
        assertEquals("0583:2060", new UsbId(0x0583, 0x2060).toString());
        assertNull(UsbId.parse("2dc8"));
        assertNull(UsbId.parse("2dc8:60g9"));
    }
}
