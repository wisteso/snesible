package snesible.core.cartridge;

/**
 * Folds an out-of-range offset back into a memory the way cartridge address decoding does.
 *
 * <p>Memory chips come in power-of-two sizes, and the board ignores address lines it doesn't
 * need. Reading past the end of a 512 KiB ROM therefore just wraps around to the start again.
 *
 * <p>Sizes that aren't a power of two are built from several chips. A 3 MiB game, for example,
 * is a 2 MiB chip followed by a 1 MiB chip, decoded as if it were a 4 MiB space. Offsets in
 * 3-4 MiB repeat the 1 MiB chip, not the start of the ROM. The loop below handles this by peeling
 * off the largest power-of-two chunk at a time.
 */
public final class Mirroring {

    private Mirroring() {
    }

    /**
     * Maps {@code offset} into a memory of {@code size} bytes.
     *
     * @param offset any non-negative offset, typically decoded from a CPU address
     * @param size   the real size of the memory; must be positive
     * @return an offset in {@code [0, size)}
     */
    public static int mirror(int offset, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Cannot mirror into a memory of size " + size);
        }
        int base = 0;
        int chunk = Integer.highestOneBit(Math.max(offset, 1));
        while (offset >= size) {
            // Find the highest address line in use and drop it.
            while ((offset & chunk) == 0) {
                chunk >>>= 1;
            }
            offset -= chunk;
            // If the memory is bigger than that chunk, the chunk is a full chip we've stepped
            // past, so continue decoding inside whatever chips come after it.
            if (size > chunk) {
                size -= chunk;
                base += chunk;
            }
            chunk >>>= 1;
        }
        return base + offset;
    }
}
