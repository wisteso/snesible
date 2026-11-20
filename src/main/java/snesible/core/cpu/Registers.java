package snesible.core.cpu;

/**
 * The programmer-visible registers of the 65816 (the Ricoh 5A22 in the SNES is a 65816 core).
 *
 * <pre>
 *   A    16-bit accumulator. With M=1 only the low byte takes part in operations; the high
 *        byte (often called "B") keeps its value and can be reached with XBA. Instructions that
 *        always use all 16 bits call the full register "C" (TCD, TCS, TSC, TDC).
 *   X, Y 16-bit index registers. With X=1 they're 8-bit and their high bytes are held at zero.
 *   S    16-bit stack pointer. In emulation mode it's confined to page $01, like the 6502's.
 *   D    16-bit direct page register: the base address of the "zero page", which the 65816 can
 *        move anywhere in bank $00.
 *   DBR  8-bit data bank: the bank for absolute addressing of data.
 *   PBR  8-bit program bank: the bank code runs from. PC never carries into it.
 *   PC   16-bit program counter within the program bank.
 *   E    emulation flag. Not part of P; it's only reachable by swapping with carry (XCE).
 *   P    status flags, see {@link StatusFlags}.
 * </pre>
 *
 * <p>The fields are public so tests and debugging tools can read and set the whole CPU state
 * directly. The {@link Cpu} itself maintains the invariants, such as clearing the index high
 * bytes when they become 8-bit.
 */
public final class Registers {

    public int a;
    public int x;
    public int y;
    public int sp;
    public int d;
    public int dbr;
    public int pbr;
    public int pc;
    public boolean emulation;
    public final StatusFlags p = new StatusFlags();

    @Override
    public String toString() {
        return String.format("A=%04X X=%04X Y=%04X S=%04X D=%04X DB=%02X PC=%02X:%04X P=%s E=%d",
                a, x, y, sp, d, dbr, pbr, pc, p, emulation ? 1 : 0);
    }
}
