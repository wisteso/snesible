package snesible.core.cartridge;

/**
 * Works out a ROM's board layout by finding its internal header.
 *
 * <p>Nothing in a ROM file says outright whether it's LoROM or HiROM. We try each candidate
 * header position and award points for every sign of a real header. The best score wins.
 */
final class HeaderLocator {

    private HeaderLocator() {
    }

    static MapMode detectMapMode(byte[] rom) {
        MapMode best = null;
        int bestScore = -1;
        for (MapMode candidate : MapMode.values()) {
            int score = score(rom, candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (bestScore <= 0) {
            throw new IllegalArgumentException("No SNES header found; is this a SNES ROM?");
        }
        return best;
    }

    /** Scores how much the bytes at this layout's header position look like a real header. */
    static int score(byte[] rom, MapMode candidate) {
        int offset = candidate.headerFileOffset();
        if (offset + CartridgeHeader.LENGTH > rom.length) {
            return -1;
        }
        CartridgeHeader header = CartridgeHeader.parse(rom, offset);
        int score = 0;
        if (header.hasConsistentChecksumPair()) {
            score += 4;
        }
        if (candidate.acceptsMapModeByte(header.mapModeByte())) {
            score += 2;
        }
        // Game code always lives in ROM, which starts at $8000 in bank $00.
        if (header.vectors().emulationReset() >= 0x8000) {
            score += 2;
        }
        if (isPrintableTitle(rom, offset)) {
            score += 1;
        }
        return score;
    }

    private static boolean isPrintableTitle(byte[] rom, int offset) {
        for (int i = 0; i < 21; i++) {
            int c = rom[offset + i] & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
