package snesible.core.cartridge;

/**
 * The destination code stored in the cartridge header. It matters to the emulator for one reason:
 * it tells us whether the game expects an NTSC console (60 Hz, 262 scanlines per frame) or a PAL
 * console (50 Hz, 312 scanlines per frame).
 */
public enum Region {
    JAPAN(0x00, "Japan", false),
    USA(0x01, "USA", false),
    EUROPE(0x02, "Europe", true),
    SCANDINAVIA(0x03, "Scandinavia", true),
    FINLAND(0x04, "Finland", true),
    DENMARK(0x05, "Denmark", true),
    FRANCE(0x06, "France", true),
    NETHERLANDS(0x07, "Netherlands", true),
    SPAIN(0x08, "Spain", true),
    GERMANY(0x09, "Germany", true),
    ITALY(0x0A, "Italy", true),
    CHINA(0x0B, "China", true),
    INDONESIA(0x0C, "Indonesia", true),
    KOREA(0x0D, "South Korea", false),
    INTERNATIONAL(0x0E, "International", false),
    CANADA(0x0F, "Canada", false),
    BRAZIL(0x10, "Brazil", false),
    AUSTRALIA(0x11, "Australia", true),
    UNKNOWN(-1, "Unknown", false);

    private final int code;
    private final String displayName;
    private final boolean pal;

    Region(int code, String displayName, boolean pal) {
        this.code = code;
        this.displayName = displayName;
        this.pal = pal;
    }

    public static Region fromCode(int code) {
        for (Region region : values()) {
            if (region.code == code) {
                return region;
            }
        }
        return UNKNOWN;
    }

    /** True for 50 Hz PAL consoles, false for 60 Hz NTSC consoles. */
    public boolean isPal() {
        return pal;
    }

    @Override
    public String toString() {
        return displayName + (pal ? " (PAL)" : " (NTSC)");
    }
}
