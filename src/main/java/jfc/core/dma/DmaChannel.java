package jfc.core.dma;

/**
 * The registers of one of the eight DMA channels, at $43x0-$43xF (x = channel number).
 *
 * <pre>
 *   $43x0 DMAPx   control: bit 7 direction (0 = A-bus to B-bus, 1 = B to A),
 *                 bit 6 HDMA indirect, bits 4-3 A-bus step (00 +1, 10 -1, x1 fixed),
 *                 bits 2-0 transfer pattern (see {@link DmaController})
 *   $43x1 BBADx   B-bus register: the low byte of $21xx
 *   $43x2-4       A-bus address: 16-bit offset plus bank (A1TxL, A1TxH, A1Bx)
 *   $43x5-6       byte count for DMA (0 means 65536), or the indirect address for HDMA
 *   $43x7         HDMA indirect bank
 *   $43x8-9       HDMA table address
 *   $43xA         HDMA line counter
 *   $43xB, $43xF  unused, but readable and writable as one shared byte
 * </pre>
 *
 * <p>For HDMA, $43x5-$43x6 hold the current indirect data address, and $43x8-$43xA advance
 * through the table as the frame is drawn.
 */
final class DmaChannel {

    int control = 0xFF;
    int bBusRegister = 0xFF;
    int aAddress = 0xFFFF;
    int aBank = 0xFF;
    int byteCount = 0xFFFF;
    int indirectBank = 0xFF;
    int hdmaTableAddress = 0xFFFF;
    int hdmaLineCounter = 0xFF;
    int unused = 0xFF;

    /** HDMA: whether this line's entry still has data to send (see DmaController). */
    boolean hdmaDoTransfer;
    /** HDMA: the table ended (a zero line count), so the channel is idle until next frame. */
    boolean hdmaTerminated = true;

    boolean isBToA() {
        return (control & 0x80) != 0;
    }

    /** HDMA indirect mode: the table holds addresses of the data rather than the data itself. */
    boolean isHdmaIndirect() {
        return (control & 0x40) != 0;
    }

    int pattern() {
        return control & 0x07;
    }

    /** How the A-bus address moves after each byte: +1, -1, or 0 (fixed). */
    int aAddressStep() {
        if ((control & 0x08) != 0) {
            return 0;
        }
        return (control & 0x10) != 0 ? -1 : 1;
    }

    int read(int register) {
        return switch (register) {
            case 0x0 -> control;
            case 0x1 -> bBusRegister;
            case 0x2 -> aAddress & 0xFF;
            case 0x3 -> aAddress >> 8;
            case 0x4 -> aBank;
            case 0x5 -> byteCount & 0xFF;
            case 0x6 -> byteCount >> 8;
            case 0x7 -> indirectBank;
            case 0x8 -> hdmaTableAddress & 0xFF;
            case 0x9 -> hdmaTableAddress >> 8;
            case 0xA -> hdmaLineCounter;
            case 0xB, 0xF -> unused;
            default -> -1; // $43xC-$43xE aren't connected: open bus
        };
    }

    void write(int register, int value) {
        switch (register) {
            case 0x0 -> control = value;
            case 0x1 -> bBusRegister = value;
            case 0x2 -> aAddress = (aAddress & 0xFF00) | value;
            case 0x3 -> aAddress = (aAddress & 0x00FF) | (value << 8);
            case 0x4 -> aBank = value;
            case 0x5 -> byteCount = (byteCount & 0xFF00) | value;
            case 0x6 -> byteCount = (byteCount & 0x00FF) | (value << 8);
            case 0x7 -> indirectBank = value;
            case 0x8 -> hdmaTableAddress = (hdmaTableAddress & 0xFF00) | value;
            case 0x9 -> hdmaTableAddress = (hdmaTableAddress & 0x00FF) | (value << 8);
            case 0xA -> hdmaLineCounter = value;
            case 0xB, 0xF -> unused = value;
            default -> {
                // $43xC-$43xE: writes go nowhere.
            }
        }
    }
}
