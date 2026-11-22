package snesible.ui.input;

import java.util.Locale;

/**
 * A USB device's vendor and product numbers: which maker and which model of pad it is. Every
 * copy of the same model has the same pair, on every computer and operating system, so it's
 * what {@code gamepad.properties} uses to give each model of pad its own button mapping.
 *
 * <p>Written as four hex digits each, {@code vendor:product}, the way USB tools and device
 * managers show them (8BitDo's vendor number is {@code 2dc8}, Nintendo's {@code 057e}).
 *
 * @param vendor  the maker's number, assigned by the USB Implementers Forum (0-0xFFFF)
 * @param product the maker's number for this model (0-0xFFFF)
 */
public record UsbId(int vendor, int product) {

    /** As {@code gamepad.properties} writes it: {@code "2dc8:6009"}, lowercase hex. */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%04x:%04x", vendor, product);
    }

    /**
     * Parses {@code "2dc8:6009"} (either case), or returns null if the text isn't in that form.
     */
    public static UsbId parse(String text) {
        String[] parts = text.trim().split(":");
        if (parts.length != 2 || parts[0].length() != 4 || parts[1].length() != 4) {
            return null;
        }
        try {
            return new UsbId(Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1], 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The vendor and product inside a joystick GUID as GLFW reports it, or null if this GUID
     * doesn't carry them.
     *
     * <p>GLFW uses SDL's GUID format: 16 bytes written as 32 hex digits. For a USB or Bluetooth
     * pad they are, in little-endian 16-bit words: the bus type, a CRC (zero in GLFW), the
     * vendor, zero, the product, zero, the version, and a driver byte pair. For example
     * {@code 03000000 c82d0000 09600000 00020000} is bus 3 (USB), vendor {@code 2dc8}, product
     * {@code 6009}. The words are little-endian, so the digits of each come out swapped:
     * {@code "c82d"} is {@code 0x2dc8}.
     *
     * <p>Some GUIDs are built another way when the system doesn't give the numbers (Windows'
     * XInput pads get {@code "78696e707574..."}, "xinput" in ASCII). Those have something other
     * than zero in the padding words, and give null here.
     */
    public static UsbId fromSdlGuid(String guid) {
        if (guid == null || guid.length() != 32) {
            return null;
        }
        try {
            // Words 3 and 5 (hex digits 12-15 and 20-23) are zero in the vendor/product form.
            if (word(guid, 3) != 0 || word(guid, 5) != 0) {
                return null;
            }
            int vendor = word(guid, 2);
            int product = word(guid, 4);
            return vendor == 0 && product == 0 ? null : new UsbId(vendor, product);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The {@code index}th little-endian 16-bit word of a hex GUID. */
    private static int word(String guid, int index) {
        int low = Integer.parseInt(guid.substring(index * 4, index * 4 + 2), 16);
        int high = Integer.parseInt(guid.substring(index * 4 + 2, index * 4 + 4), 16);
        return high << 8 | low;
    }
}
