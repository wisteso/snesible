package snesible.core.apu;

import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * The Sony SPC700, the 8-bit CPU inside the SNES's audio unit.
 *
 * <p>It runs at 1.024 MHz with its own 64 KiB of RAM, and its job is to run a game's sound
 * driver: read commands from the main CPU through four ports, play music by programming the
 * DSP, and time everything with three hardware timers.
 *
 * <h2>Registers</h2>
 * <pre>
 *   A, X, Y  8-bit; Y and A pair up as the 16-bit "YA" for word operations, MUL and DIV
 *   SP       8-bit stack pointer, always in page $01
 *   PC       16-bit program counter
 *   PSW      flags: N V P B H I Z C
 *            P = direct page select ($00xx or $01xx), H = half-carry (for BCD), B = break
 * </pre>
 * The instruction set looks a lot like the 6502's, with different mnemonics (MOV for loads
 * and stores) and some extras: bit operations on memory, 16-bit word math, multiply and divide,
 * and memory-to-memory operations like {@code ADC dp,dp}.
 *
 * <p>The structure mirrors {@link snesible.core.cpu.Cpu}: one opcode table, a handful of reusable
 * instruction templates (they're shared by many opcodes because the encodings are regular), and
 * the ALU operations. Each template performs the same bus cycles, in the same order, as the
 * real chip, and the SingleStepTests SPC700 suite checks that.
 */
public final class Spc700 {

    private final Spc700Bus bus;

    // Registers. Public for tests and debugging tools.
    public int a;
    public int x;
    public int y;
    public int sp;
    public int pc;

    // PSW flags.
    public boolean negative;
    public boolean overflow;
    public boolean directPage;
    public boolean breakFlag;
    public boolean halfCarry;
    public boolean interruptEnable;
    public boolean zero;
    public boolean carry;

    /** SLEEP and STOP halt the CPU. Nothing in the SNES ever wakes it again. */
    private boolean halted;

    public Spc700(Spc700Bus bus) {
        this.bus = bus;
    }

    /** Starts from the reset vector at $FFFE, which points into the boot ROM. */
    public void reset() {
        a = 0;
        x = 0;
        y = 0;
        sp = 0xEF;
        setPsw(0x02);
        halted = false;
        pc = bus.read(0xFFFE) | (bus.read(0xFFFF) << 8);
    }

    public boolean isHalted() {
        return halted;
    }

    /** Packs the flags: N V P B H I Z C, bit 7 to bit 0. */
    public int psw() {
        return (negative ? 0x80 : 0) | (overflow ? 0x40 : 0) | (directPage ? 0x20 : 0)
                | (breakFlag ? 0x10 : 0) | (halfCarry ? 0x08 : 0) | (interruptEnable ? 0x04 : 0)
                | (zero ? 0x02 : 0) | (carry ? 0x01 : 0);
    }

    public void setPsw(int value) {
        negative = (value & 0x80) != 0;
        overflow = (value & 0x40) != 0;
        directPage = (value & 0x20) != 0;
        breakFlag = (value & 0x10) != 0;
        halfCarry = (value & 0x08) != 0;
        interruptEnable = (value & 0x04) != 0;
        zero = (value & 0x02) != 0;
        carry = (value & 0x01) != 0;
    }

    /** Runs one instruction (or, when halted, one pair of idle cycles). */
    public void step() {
        if (halted) {
            bus.read(pc);
            bus.idle();
            return;
        }
        execute(fetch());
    }

    // =============================================================================================
    // The opcode table
    //
    // Notation: dp = direct page, !abs = absolute, (X) = memory at X in the direct page,
    // [dp+X] = pointer in direct page indexed first, [dp]+Y = pointer indexed afterwards,
    // mem.bit = a 13-bit address with a 3-bit bit number on top.
    // =============================================================================================

    private void execute(int opcode) {
        switch (opcode) {
            // ---- 8-bit arithmetic and logic with A: the eight addressing modes of each ------
            case 0x08 -> a = immediateRead(this::or, a);              // OR A,#imm
            case 0x06 -> a = indirectXRead(this::or, a);              // OR A,(X)
            case 0x04 -> a = directRead(this::or, a);                 // OR A,dp
            case 0x14 -> a = directIndexedRead(this::or, a, x);       // OR A,dp+X
            case 0x05 -> a = absoluteRead(this::or, a);               // OR A,!abs
            case 0x15 -> a = absoluteIndexedRead(this::or, a, x);     // OR A,!abs+X
            case 0x16 -> a = absoluteIndexedRead(this::or, a, y);     // OR A,!abs+Y
            case 0x07 -> a = indexedIndirectRead(this::or, a);        // OR A,[dp+X]
            case 0x17 -> a = indirectIndexedRead(this::or, a);        // OR A,[dp]+Y

            case 0x28 -> a = immediateRead(this::and, a);             // AND A,#imm
            case 0x26 -> a = indirectXRead(this::and, a);             // AND A,(X)
            case 0x24 -> a = directRead(this::and, a);                // AND A,dp
            case 0x34 -> a = directIndexedRead(this::and, a, x);      // AND A,dp+X
            case 0x25 -> a = absoluteRead(this::and, a);              // AND A,!abs
            case 0x35 -> a = absoluteIndexedRead(this::and, a, x);    // AND A,!abs+X
            case 0x36 -> a = absoluteIndexedRead(this::and, a, y);    // AND A,!abs+Y
            case 0x27 -> a = indexedIndirectRead(this::and, a);       // AND A,[dp+X]
            case 0x37 -> a = indirectIndexedRead(this::and, a);       // AND A,[dp]+Y

            case 0x48 -> a = immediateRead(this::eor, a);             // EOR A,#imm
            case 0x46 -> a = indirectXRead(this::eor, a);             // EOR A,(X)
            case 0x44 -> a = directRead(this::eor, a);                // EOR A,dp
            case 0x54 -> a = directIndexedRead(this::eor, a, x);      // EOR A,dp+X
            case 0x45 -> a = absoluteRead(this::eor, a);              // EOR A,!abs
            case 0x55 -> a = absoluteIndexedRead(this::eor, a, x);    // EOR A,!abs+X
            case 0x56 -> a = absoluteIndexedRead(this::eor, a, y);    // EOR A,!abs+Y
            case 0x47 -> a = indexedIndirectRead(this::eor, a);       // EOR A,[dp+X]
            case 0x57 -> a = indirectIndexedRead(this::eor, a);       // EOR A,[dp]+Y

            case 0x68 -> a = immediateRead(this::cmp, a);             // CMP A,#imm
            case 0x66 -> a = indirectXRead(this::cmp, a);             // CMP A,(X)
            case 0x64 -> a = directRead(this::cmp, a);                // CMP A,dp
            case 0x74 -> a = directIndexedRead(this::cmp, a, x);      // CMP A,dp+X
            case 0x65 -> a = absoluteRead(this::cmp, a);              // CMP A,!abs
            case 0x75 -> a = absoluteIndexedRead(this::cmp, a, x);    // CMP A,!abs+X
            case 0x76 -> a = absoluteIndexedRead(this::cmp, a, y);    // CMP A,!abs+Y
            case 0x67 -> a = indexedIndirectRead(this::cmp, a);       // CMP A,[dp+X]
            case 0x77 -> a = indirectIndexedRead(this::cmp, a);       // CMP A,[dp]+Y

            case 0x88 -> a = immediateRead(this::adc, a);             // ADC A,#imm
            case 0x86 -> a = indirectXRead(this::adc, a);             // ADC A,(X)
            case 0x84 -> a = directRead(this::adc, a);                // ADC A,dp
            case 0x94 -> a = directIndexedRead(this::adc, a, x);      // ADC A,dp+X
            case 0x85 -> a = absoluteRead(this::adc, a);              // ADC A,!abs
            case 0x95 -> a = absoluteIndexedRead(this::adc, a, x);    // ADC A,!abs+X
            case 0x96 -> a = absoluteIndexedRead(this::adc, a, y);    // ADC A,!abs+Y
            case 0x87 -> a = indexedIndirectRead(this::adc, a);       // ADC A,[dp+X]
            case 0x97 -> a = indirectIndexedRead(this::adc, a);       // ADC A,[dp]+Y

            case 0xA8 -> a = immediateRead(this::sbc, a);             // SBC A,#imm
            case 0xA6 -> a = indirectXRead(this::sbc, a);             // SBC A,(X)
            case 0xA4 -> a = directRead(this::sbc, a);                // SBC A,dp
            case 0xB4 -> a = directIndexedRead(this::sbc, a, x);      // SBC A,dp+X
            case 0xA5 -> a = absoluteRead(this::sbc, a);              // SBC A,!abs
            case 0xB5 -> a = absoluteIndexedRead(this::sbc, a, x);    // SBC A,!abs+X
            case 0xB6 -> a = absoluteIndexedRead(this::sbc, a, y);    // SBC A,!abs+Y
            case 0xA7 -> a = indexedIndirectRead(this::sbc, a);       // SBC A,[dp+X]
            case 0xB7 -> a = indirectIndexedRead(this::sbc, a);       // SBC A,[dp]+Y

            // ---- Memory-to-memory: dp,dp  (X),(Y)  dp,#imm --------------------------------
            case 0x09 -> directDirect(this::or, false);               // OR dp,dp
            case 0x19 -> indirectXIndirectY(this::or, false);         // OR (X),(Y)
            case 0x18 -> directImmediate(this::or, false);            // OR dp,#imm
            case 0x29 -> directDirect(this::and, false);              // AND dp,dp
            case 0x39 -> indirectXIndirectY(this::and, false);        // AND (X),(Y)
            case 0x38 -> directImmediate(this::and, false);           // AND dp,#imm
            case 0x49 -> directDirect(this::eor, false);              // EOR dp,dp
            case 0x59 -> indirectXIndirectY(this::eor, false);        // EOR (X),(Y)
            case 0x58 -> directImmediate(this::eor, false);           // EOR dp,#imm
            case 0x69 -> directDirect(this::cmp, true);               // CMP dp,dp
            case 0x79 -> indirectXIndirectY(this::cmp, true);         // CMP (X),(Y)
            case 0x78 -> directImmediate(this::cmp, true);            // CMP dp,#imm
            case 0x89 -> directDirect(this::adc, false);              // ADC dp,dp
            case 0x99 -> indirectXIndirectY(this::adc, false);        // ADC (X),(Y)
            case 0x98 -> directImmediate(this::adc, false);           // ADC dp,#imm
            case 0xA9 -> directDirect(this::sbc, false);              // SBC dp,dp
            case 0xB9 -> indirectXIndirectY(this::sbc, false);        // SBC (X),(Y)
            case 0xB8 -> directImmediate(this::sbc, false);           // SBC dp,#imm

            // ---- Compares with X and Y -----------------------------------------------------
            case 0xC8 -> x = immediateRead(this::cmp, x);             // CMP X,#imm
            case 0x3E -> x = directRead(this::cmp, x);                // CMP X,dp
            case 0x1E -> x = absoluteRead(this::cmp, x);              // CMP X,!abs
            case 0xAD -> y = immediateRead(this::cmp, y);             // CMP Y,#imm
            case 0x7E -> y = directRead(this::cmp, y);                // CMP Y,dp
            case 0x5E -> y = absoluteRead(this::cmp, y);              // CMP Y,!abs

            // ---- Loads (MOV reg,mem) -------------------------------------------------------
            case 0xE8 -> a = immediateRead(this::move, a);            // MOV A,#imm
            case 0xE6 -> a = indirectXRead(this::move, a);            // MOV A,(X)
            case 0xBF -> loadAPostIncrement();                        // MOV A,(X)+
            case 0xE4 -> a = directRead(this::move, a);               // MOV A,dp
            case 0xF4 -> a = directIndexedRead(this::move, a, x);     // MOV A,dp+X
            case 0xE5 -> a = absoluteRead(this::move, a);             // MOV A,!abs
            case 0xF5 -> a = absoluteIndexedRead(this::move, a, x);   // MOV A,!abs+X
            case 0xF6 -> a = absoluteIndexedRead(this::move, a, y);   // MOV A,!abs+Y
            case 0xE7 -> a = indexedIndirectRead(this::move, a);      // MOV A,[dp+X]
            case 0xF7 -> a = indirectIndexedRead(this::move, a);      // MOV A,[dp]+Y
            case 0xCD -> x = immediateRead(this::move, x);            // MOV X,#imm
            case 0xF8 -> x = directRead(this::move, x);               // MOV X,dp
            case 0xF9 -> x = directIndexedRead(this::move, x, y);     // MOV X,dp+Y
            case 0xE9 -> x = absoluteRead(this::move, x);             // MOV X,!abs
            case 0x8D -> y = immediateRead(this::move, y);            // MOV Y,#imm
            case 0xEB -> y = directRead(this::move, y);               // MOV Y,dp
            case 0xFB -> y = directIndexedRead(this::move, y, x);     // MOV Y,dp+X
            case 0xEC -> y = absoluteRead(this::move, y);             // MOV Y,!abs

            // ---- Stores (MOV mem,reg) -------------------------------------------------------
            case 0xC6 -> indirectXWrite(a);                           // MOV (X),A
            case 0xAF -> storeAPostIncrement();                       // MOV (X)+,A
            case 0xC4 -> directWrite(a);                              // MOV dp,A
            case 0xD4 -> directIndexedWrite(a, x);                    // MOV dp+X,A
            case 0xC5 -> absoluteWrite(a);                            // MOV !abs,A
            case 0xD5 -> absoluteIndexedWrite(a, x);                  // MOV !abs+X,A
            case 0xD6 -> absoluteIndexedWrite(a, y);                  // MOV !abs+Y,A
            case 0xC7 -> indexedIndirectWrite(a);                     // MOV [dp+X],A
            case 0xD7 -> indirectIndexedWrite(a);                     // MOV [dp]+Y,A
            case 0xD8 -> directWrite(x);                              // MOV dp,X
            case 0xD9 -> directIndexedWrite(x, y);                    // MOV dp+Y,X
            case 0xC9 -> absoluteWrite(x);                            // MOV !abs,X
            case 0xCB -> directWrite(y);                              // MOV dp,Y
            case 0xDB -> directIndexedWrite(y, x);                    // MOV dp+X,Y
            case 0xCC -> absoluteWrite(y);                            // MOV !abs,Y
            case 0xFA -> moveDirectToDirect();                        // MOV dp,dp
            case 0x8F -> moveImmediateToDirect();                     // MOV dp,#imm

            // ---- Register transfers ---------------------------------------------------------
            case 0x7D -> a = transfer(x);                             // MOV A,X
            case 0xDD -> a = transfer(y);                             // MOV A,Y
            case 0x5D -> x = transfer(a);                             // MOV X,A
            case 0xFD -> y = transfer(a);                             // MOV Y,A
            case 0x9D -> x = transfer(sp);                            // MOV X,SP
            case 0xBD -> {                                            // MOV SP,X (no flags)
                dummyRead();
                sp = x;
            }

            // ---- Shifts, rotates, increments --------------------------------------------------
            case 0x1C -> a = impliedModify(this::asl, a);             // ASL A
            case 0x0B -> directModify(this::asl);                     // ASL dp
            case 0x1B -> directIndexedModify(this::asl);              // ASL dp+X
            case 0x0C -> absoluteModify(this::asl);                   // ASL !abs
            case 0x5C -> a = impliedModify(this::lsr, a);             // LSR A
            case 0x4B -> directModify(this::lsr);                     // LSR dp
            case 0x5B -> directIndexedModify(this::lsr);              // LSR dp+X
            case 0x4C -> absoluteModify(this::lsr);                   // LSR !abs
            case 0x3C -> a = impliedModify(this::rol, a);             // ROL A
            case 0x2B -> directModify(this::rol);                     // ROL dp
            case 0x3B -> directIndexedModify(this::rol);              // ROL dp+X
            case 0x2C -> absoluteModify(this::rol);                   // ROL !abs
            case 0x7C -> a = impliedModify(this::ror, a);             // ROR A
            case 0x6B -> directModify(this::ror);                     // ROR dp
            case 0x7B -> directIndexedModify(this::ror);              // ROR dp+X
            case 0x6C -> absoluteModify(this::ror);                   // ROR !abs
            case 0xBC -> a = impliedModify(this::inc, a);             // INC A
            case 0x3D -> x = impliedModify(this::inc, x);             // INC X
            case 0xFC -> y = impliedModify(this::inc, y);             // INC Y
            case 0xAB -> directModify(this::inc);                     // INC dp
            case 0xBB -> directIndexedModify(this::inc);              // INC dp+X
            case 0xAC -> absoluteModify(this::inc);                   // INC !abs
            case 0x9C -> a = impliedModify(this::dec, a);             // DEC A
            case 0x1D -> x = impliedModify(this::dec, x);             // DEC X
            case 0xDC -> y = impliedModify(this::dec, y);             // DEC Y
            case 0x8B -> directModify(this::dec);                     // DEC dp
            case 0x9B -> directIndexedModify(this::dec);              // DEC dp+X
            case 0x8C -> absoluteModify(this::dec);                   // DEC !abs
            case 0x9F -> exchangeNibbles();                           // XCN A

            // ---- 16-bit word operations on YA --------------------------------------------------
            case 0xBA -> wordRead(this::loadWord, true);              // MOVW YA,dp
            case 0xDA -> storeWord();                                 // MOVW dp,YA
            case 0x3A -> modifyWord(+1);                              // INCW dp
            case 0x1A -> modifyWord(-1);                              // DECW dp
            case 0x7A -> wordRead(this::addWord, true);               // ADDW YA,dp
            case 0x9A -> wordRead(this::subtractWord, true);          // SUBW YA,dp
            case 0x5A -> wordRead(this::compareWord, false);          // CMPW YA,dp

            // ---- Multiply, divide, decimal adjust ----------------------------------------------
            case 0xCF -> multiply();                                  // MUL YA
            case 0x9E -> divide();                                    // DIV YA,X
            case 0xDF -> decimalAdjustAdd();                          // DAA A
            case 0xBE -> decimalAdjustSubtract();                     // DAS A

            // ---- Branches -----------------------------------------------------------------------
            case 0x2F -> branchIf(true);                              // BRA
            case 0xF0 -> branchIf(zero);                              // BEQ
            case 0xD0 -> branchIf(!zero);                             // BNE
            case 0xB0 -> branchIf(carry);                             // BCS
            case 0x90 -> branchIf(!carry);                            // BCC
            case 0x70 -> branchIf(overflow);                          // BVS
            case 0x50 -> branchIf(!overflow);                         // BVC
            case 0x30 -> branchIf(negative);                          // BMI
            case 0x10 -> branchIf(!negative);                         // BPL
            case 0x2E -> compareBranchDirect();                       // CBNE dp,rel
            case 0xDE -> compareBranchDirectIndexed();                // CBNE dp+X,rel
            case 0x6E -> decrementBranchDirect();                     // DBNZ dp,rel
            case 0xFE -> decrementBranchY();                          // DBNZ Y,rel

            // ---- Jumps, calls and returns -------------------------------------------------------
            case 0x5F -> pc = fetchWord();                            // JMP !abs
            case 0x1F -> jumpIndexedIndirect();                       // JMP [!abs+X]
            case 0x3F -> call();                                      // CALL !abs
            case 0x4F -> callPage();                                  // PCALL up
            case 0x6F -> returnFromSubroutine();                      // RET
            case 0x7F -> returnFromInterrupt();                       // RETI
            case 0x0F -> softwareBreak();                             // BRK

            // ---- Stack --------------------------------------------------------------------------
            case 0x2D -> pushRegister(a);                             // PUSH A
            case 0x4D -> pushRegister(x);                             // PUSH X
            case 0x6D -> pushRegister(y);                             // PUSH Y
            case 0x0D -> pushRegister(psw());                         // PUSH PSW
            case 0xAE -> a = pullRegister();                          // POP A
            case 0xCE -> x = pullRegister();                          // POP X
            case 0xEE -> y = pullRegister();                          // POP Y
            case 0x8E -> setPsw(pullRegister());                      // POP PSW

            // ---- Flags --------------------------------------------------------------------------
            case 0x60 -> { dummyRead(); carry = false; }              // CLRC
            case 0x80 -> { dummyRead(); carry = true; }               // SETC
            case 0xED -> { dummyRead(); idle(); carry = !carry; }     // NOTC
            case 0xE0 -> { dummyRead(); overflow = false; halfCarry = false; } // CLRV
            case 0x20 -> { dummyRead(); directPage = false; }         // CLRP
            case 0x40 -> { dummyRead(); directPage = true; }          // SETP
            case 0xA0 -> { dummyRead(); idle(); interruptEnable = true; }  // EI
            case 0xC0 -> { dummyRead(); idle(); interruptEnable = false; } // DI

            // ---- Halting and doing nothing ------------------------------------------------------
            case 0x00 -> dummyRead();                                 // NOP
            case 0xEF, 0xFF -> halt();                                // SLEEP, STOP

            default -> executeBitOrTableOperation(opcode);
        }
    }

    /**
     * The opcodes whose low bits encode a number: a bit (0-7) for SET1/CLR1/BBS/BBC, a table
     * entry (0-15) for TCALL. Plus the "mem.bit" carry operations.
     */
    private void executeBitOrTableOperation(int opcode) {
        int column = opcode & 0x0F;
        int row = opcode >> 4;
        switch (column) {
            case 0x01 -> tableCall(row);                              // TCALL n
            case 0x02 -> setDirectBit(row >> 1, (row & 1) == 0);      // SET1 / CLR1 dp.bit
            case 0x03 -> branchOnBit(row >> 1, (row & 1) == 0);       // BBS / BBC dp.bit,rel
            case 0x0A -> carryBitOperation(opcode);                   // OR1 AND1 EOR1 MOV1 NOT1
            case 0x0E -> testAndModifyBits(opcode == 0x0E);           // TSET1 / TCLR1 !abs
            default -> throw new IllegalStateException(String.format("Unhandled opcode %02X", opcode));
        }
    }

    // =============================================================================================
    // Instruction templates: reads into a register
    // =============================================================================================

    private int immediateRead(IntBinaryOperator op, int target) {
        return op.applyAsInt(target, fetch());
    }

    private int indirectXRead(IntBinaryOperator op, int target) {
        dummyRead();
        return op.applyAsInt(target, load(x));
    }

    private int directRead(IntBinaryOperator op, int target) {
        int address = fetch();
        return op.applyAsInt(target, load(address));
    }

    private int directIndexedRead(IntBinaryOperator op, int target, int index) {
        int address = fetch();
        idle();
        return op.applyAsInt(target, load(address + index));
    }

    private int absoluteRead(IntBinaryOperator op, int target) {
        int address = fetchWord();
        return op.applyAsInt(target, read(address));
    }

    private int absoluteIndexedRead(IntBinaryOperator op, int target, int index) {
        int address = fetchWord();
        idle();
        return op.applyAsInt(target, read(address + index));
    }

    /** [dp+X]: X selects a 16-bit pointer stored in the direct page. */
    private int indexedIndirectRead(IntBinaryOperator op, int target) {
        int pointer = fetch();
        idle();
        int address = load(pointer + x) | (load(pointer + x + 1) << 8);
        return op.applyAsInt(target, read(address));
    }

    /**
     * [dp]+Y: a 16-bit pointer stored in the direct page, plus Y. Note the idle cycle comes
     * before the pointer is read here, but after it in the store version.
     */
    private int indirectIndexedRead(IntBinaryOperator op, int target) {
        int pointer = fetch();
        idle();
        int address = load(pointer) | (load(pointer + 1) << 8);
        return op.applyAsInt(target, read(address + y));
    }

    // =============================================================================================
    // Instruction templates: stores. The SPC700 reads a location before writing it.
    // =============================================================================================

    private void indirectXWrite(int value) {
        dummyRead();
        load(x);
        store(x, value);
    }

    private void directWrite(int value) {
        int address = fetch();
        load(address);
        store(address, value);
    }

    private void directIndexedWrite(int value, int index) {
        int address = fetch();
        idle();
        load(address + index);
        store(address + index, value);
    }

    private void absoluteWrite(int value) {
        int address = fetchWord();
        read(address);
        write(address, value);
    }

    private void absoluteIndexedWrite(int value, int index) {
        int address = fetchWord();
        idle();
        read(address + index);
        write(address + index, value);
    }

    private void indexedIndirectWrite(int value) {
        int pointer = fetch();
        idle();
        int address = load(pointer + x) | (load(pointer + x + 1) << 8);
        read(address);
        write(address, value);
    }

    private void indirectIndexedWrite(int value) {
        int pointer = fetch();
        int address = load(pointer) | (load(pointer + 1) << 8);
        idle();
        read(address + y);
        write(address + y, value);
    }

    /** MOV dp,dp: unusually, no read of the destination first. */
    private void moveDirectToDirect() {
        int source = fetch();
        int value = load(source);
        int destination = fetch();
        store(destination, value);
    }

    private void moveImmediateToDirect() {
        int value = fetch();
        int address = fetch();
        load(address);
        store(address, value);
    }

    /** MOV A,(X)+: load and advance X, a handy way to walk a buffer in the direct page. */
    private void loadAPostIncrement() {
        dummyRead();
        a = load(x);
        x = (x + 1) & 0xFF;
        idle();
        setNZ(a);
    }

    private void storeAPostIncrement() {
        dummyRead();
        idle();
        store(x, a);
        x = (x + 1) & 0xFF;
    }

    private int transfer(int value) {
        dummyRead();
        setNZ(value);
        return value;
    }

    // =============================================================================================
    // Instruction templates: memory-to-memory and read-modify-write
    // =============================================================================================

    /**
     * dp,dp operations: {@code OP dst,src} computes dst = dst OP src. Compares spend a final
     * idle cycle instead of writing the result.
     */
    private void directDirect(IntBinaryOperator op, boolean compare) {
        int sourceAddress = fetch();
        int source = load(sourceAddress);
        int destinationAddress = fetch();
        int destination = load(destinationAddress);
        int result = op.applyAsInt(destination, source);
        if (compare) {
            idle();
        } else {
            store(destinationAddress, result);
        }
    }

    private void indirectXIndirectY(IntBinaryOperator op, boolean compare) {
        dummyRead();
        int source = load(y);
        int destination = load(x);
        int result = op.applyAsInt(destination, source);
        if (compare) {
            idle();
        } else {
            store(x, result);
        }
    }

    /** dp,#imm: note the immediate comes first in the machine code, then the address. */
    private void directImmediate(IntBinaryOperator op, boolean compare) {
        int immediate = fetch();
        int address = fetch();
        int result = op.applyAsInt(load(address), immediate);
        if (compare) {
            idle();
        } else {
            store(address, result);
        }
    }

    private int impliedModify(IntUnaryOperator op, int target) {
        dummyRead();
        return op.applyAsInt(target);
    }

    private void directModify(IntUnaryOperator op) {
        int address = fetch();
        store(address, op.applyAsInt(load(address)));
    }

    private void directIndexedModify(IntUnaryOperator op) {
        int address = fetch();
        idle();
        store(address + x, op.applyAsInt(load(address + x)));
    }

    private void absoluteModify(IntUnaryOperator op) {
        int address = fetchWord();
        write(address, op.applyAsInt(read(address)));
    }

    // =============================================================================================
    // Bit operations
    // =============================================================================================

    /** SET1/CLR1 dp.bit: set or clear one bit of a direct page byte. */
    private void setDirectBit(int bit, boolean set) {
        int address = fetch();
        int value = load(address);
        store(address, set ? value | (1 << bit) : value & ~(1 << bit));
    }

    /** BBS/BBC dp.bit,rel: branch if a bit of a direct page byte is set (or clear). */
    private void branchOnBit(int bit, boolean branchIfSet) {
        int address = fetch();
        int value = load(address);
        idle();
        int displacement = fetch();
        if (((value >> bit) & 1) == (branchIfSet ? 1 : 0)) {
            takeBranch(displacement);
        }
    }

    /**
     * The "mem.bit" carry operations. The 16-bit operand packs a 13-bit address (so only the
     * first 8 KiB is reachable) with a 3-bit bit number in the top bits.
     */
    private void carryBitOperation(int opcode) {
        int operand = fetchWord();
        int bit = operand >> 13;
        int address = operand & 0x1FFF;
        int value = read(address);
        boolean memoryBit = ((value >> bit) & 1) != 0;
        switch (opcode) {
            case 0x0A -> { idle(); carry |= memoryBit; }      // OR1 C,mem.bit
            case 0x2A -> { idle(); carry |= !memoryBit; }     // OR1 C,/mem.bit
            case 0x4A -> carry &= memoryBit;                  // AND1 C,mem.bit
            case 0x6A -> carry &= !memoryBit;                 // AND1 C,/mem.bit
            case 0x8A -> { idle(); carry ^= memoryBit; }      // EOR1 C,mem.bit
            case 0xAA -> carry = memoryBit;                   // MOV1 C,mem.bit
            case 0xCA -> {                                    // MOV1 mem.bit,C
                idle();
                write(address, carry ? value | (1 << bit) : value & ~(1 << bit));
            }
            case 0xEA -> write(address, value ^ (1 << bit));  // NOT1 mem.bit
            default -> throw new IllegalStateException("Not a carry bit opcode: " + opcode);
        }
    }

    /**
     * TSET1/TCLR1 !abs: set or clear the bits of A in memory. N and Z are set as if comparing A
     * with the old value, which lets a driver test and claim a flag in one instruction.
     */
    private void testAndModifyBits(boolean set) {
        int address = fetchWord();
        int value = read(address);
        setNZ((a - value) & 0xFF);
        read(address);
        write(address, set ? value | a : value & ~a);
    }

    // =============================================================================================
    // Words, multiply, divide, decimal
    // =============================================================================================

    /** Reads a 16-bit value from the direct page and combines it into YA. */
    private void wordRead(IntBinaryOperator op, boolean extraCycle) {
        int address = fetch();
        int low = load(address);
        if (extraCycle) {
            idle();
        }
        int high = load(address + 1);
        int result = op.applyAsInt(ya(), (high << 8) | low);
        a = result & 0xFF;
        y = result >> 8;
    }

    private void storeWord() {
        int address = fetch();
        load(address);
        store(address, a);
        store(address + 1, y);
    }

    /** INCW/DECW: the carry from the low byte reaches the high byte between the two writes. */
    private void modifyWord(int delta) {
        int address = fetch();
        int value = (load(address) + delta) & 0xFFFF;
        store(address, value);
        value = (value + (load(address + 1) << 8)) & 0xFFFF;
        store(address + 1, value >> 8);
        zero = value == 0;
        negative = (value & 0x8000) != 0;
    }

    private int ya() {
        return (y << 8) | a;
    }

    /** MUL YA: Y * A, unsigned, into YA. N and Z reflect only Y (the high byte). */
    private void multiply() {
        dummyRead();
        for (int i = 0; i < 7; i++) {
            idle();
        }
        int product = y * a;
        a = product & 0xFF;
        y = product >> 8;
        setNZ(y);
    }

    /**
     * DIV YA,X: YA / X, quotient into A and remainder into Y. The hardware divider produces a
     * 9-bit quotient (V holds the ninth bit). When the true quotient doesn't fit in 9 bits it
     * produces strange values, which the formula in the else-branch reproduces.
     */
    private void divide() {
        dummyRead();
        for (int i = 0; i < 10; i++) {
            idle();
        }
        int dividend = ya();
        // H and V come from comparing the dividend's high byte (Y) with the divisor before
        // dividing: V means the quotient won't fit in 8 bits (Y >= X implies YA/X >= 256).
        halfCarry = (y & 0x0F) >= (x & 0x0F);
        overflow = y >= x;
        if (y < (x << 1)) {
            // Quotient below 512: it fits the divider's 9 bits, so the result is the true one
            // (A gets the low 8 bits; V already holds the ninth).
            a = (dividend / x) & 0xFF;
            y = (dividend % x) & 0xFF;
        } else {
            // Quotient too big for 9 bits: the shift-and-subtract circuit goes off the rails.
            // This closed form of what it ends up computing is from hardware testing (it also
            // handles X = 0 without dividing by zero, since then 256 - X is 256).
            a = (255 - (dividend - (x << 9)) / (256 - x)) & 0xFF;
            y = (x + (dividend - (x << 9)) % (256 - x)) & 0xFF;
        }
        setNZ(a);
    }

    /** DAA: adjust A after a binary addition of two BCD numbers. */
    private void decimalAdjustAdd() {
        dummyRead();
        idle();
        if (carry || a > 0x99) {
            a = (a + 0x60) & 0xFF;
            carry = true;
        }
        if (halfCarry || (a & 0x0F) > 0x09) {
            a = (a + 0x06) & 0xFF;
        }
        setNZ(a);
    }

    /** DAS: adjust A after a binary subtraction of two BCD numbers. */
    private void decimalAdjustSubtract() {
        dummyRead();
        idle();
        if (!carry || a > 0x99) {
            a = (a - 0x60) & 0xFF;
            carry = false;
        }
        if (!halfCarry || (a & 0x0F) > 0x09) {
            a = (a - 0x06) & 0xFF;
        }
        setNZ(a);
    }

    private void exchangeNibbles() {
        dummyRead();
        idle();
        idle();
        idle();
        a = ((a >> 4) | (a << 4)) & 0xFF;
        setNZ(a);
    }

    // =============================================================================================
    // Branches, jumps, calls
    // =============================================================================================

    private void branchIf(boolean condition) {
        int displacement = fetch();
        if (condition) {
            takeBranch(displacement);
        }
    }

    /** A taken branch costs two extra cycles. */
    private void takeBranch(int displacement) {
        idle();
        idle();
        pc = (pc + (byte) displacement) & 0xFFFF;
    }

    /** CBNE dp,rel: branch if A differs from a direct page byte. Loop helpers for drivers. */
    private void compareBranchDirect() {
        int address = fetch();
        int value = load(address);
        idle();
        int displacement = fetch();
        if (a != value) {
            takeBranch(displacement);
        }
    }

    private void compareBranchDirectIndexed() {
        int address = fetch();
        idle();
        int value = load(address + x);
        idle();
        int displacement = fetch();
        if (a != value) {
            takeBranch(displacement);
        }
    }

    /** DBNZ dp,rel: decrement a direct page byte, branch if it isn't zero (no flags change). */
    private void decrementBranchDirect() {
        int address = fetch();
        int value = (load(address) - 1) & 0xFF;
        store(address, value);
        int displacement = fetch();
        if (value != 0) {
            takeBranch(displacement);
        }
    }

    private void decrementBranchY() {
        dummyRead();
        idle();
        int displacement = fetch();
        y = (y - 1) & 0xFF;
        if (y != 0) {
            takeBranch(displacement);
        }
    }

    private void jumpIndexedIndirect() {
        int table = fetchWord();
        idle();
        pc = read(table + x) | (read(table + x + 1) << 8);
    }

    private void call() {
        int target = fetchWord();
        idle();
        pushReturnAddress();
        idle();
        idle();
        pc = target;
    }

    /** PCALL: a short call into the top page ($FFxx), which is where the boot ROM lives. */
    private void callPage() {
        int target = fetch();
        idle();
        pushReturnAddress();
        idle();
        pc = 0xFF00 | target;
    }

    /**
     * TCALL n: a one-byte call through a table of 16 vectors counting down from $FFDE
     * (n = 0 uses $FFDE, n = 15 uses $FFC0).
     */
    private void tableCall(int n) {
        dummyRead();
        idle();
        pushReturnAddress();
        idle();
        int vector = 0xFFDE - n * 2;
        pc = read(vector) | (read(vector + 1) << 8);
    }

    /** BRK: like an interrupt, through the TCALL 0 vector, setting B and clearing I. */
    private void softwareBreak() {
        dummyRead();
        pushReturnAddress();
        push(psw());
        idle();
        pc = read(0xFFDE) | (read(0xFFDF) << 8);
        interruptEnable = false;
        breakFlag = true;
    }

    private void returnFromSubroutine() {
        dummyRead();
        idle();
        int low = pull();
        pc = low | (pull() << 8);
    }

    private void returnFromInterrupt() {
        dummyRead();
        idle();
        setPsw(pull());
        int low = pull();
        pc = low | (pull() << 8);
    }

    private void pushRegister(int value) {
        dummyRead();
        push(value);
        idle();
    }

    private int pullRegister() {
        dummyRead();
        idle();
        return pull();
    }

    /** SLEEP/STOP stop the CPU. Nothing in the SNES wakes it again; it spins on idle cycles. */
    private void halt() {
        dummyRead();
        idle();
        halted = true;
    }

    // =============================================================================================
    // The ALU
    // =============================================================================================

    private int or(int target, int value) {
        return setNZ(target | value);
    }

    private int and(int target, int value) {
        return setNZ(target & value);
    }

    private int eor(int target, int value) {
        return setNZ(target ^ value);
    }

    /** MOV reg,mem: N and Z follow the value loaded. */
    private int move(int target, int value) {
        return setNZ(value);
    }

    /** CMP: subtract without storing. C means "no borrow" (target >= value). */
    private int cmp(int target, int value) {
        int difference = target - value;
        carry = difference >= 0;
        setNZ(difference & 0xFF);
        return target;
    }

    /**
     * ADC. H is the carry out of bit 3 (used by DAA), V is signed overflow: both operands had
     * the same sign and the result's sign differs.
     */
    private int adc(int target, int value) {
        int result = target + value + (carry ? 1 : 0);
        carry = result > 0xFF;
        halfCarry = ((target ^ value ^ result) & 0x10) != 0;
        overflow = (~(target ^ value) & (target ^ result) & 0x80) != 0;
        return setNZ(result & 0xFF);
    }

    /** SBC is ADC of the inverted operand, with carry meaning "no borrow". */
    private int sbc(int target, int value) {
        return adc(target, ~value & 0xFF);
    }

    private int asl(int value) {
        carry = (value & 0x80) != 0;
        return setNZ((value << 1) & 0xFF);
    }

    private int lsr(int value) {
        carry = (value & 0x01) != 0;
        return setNZ(value >> 1);
    }

    private int rol(int value) {
        int result = ((value << 1) | (carry ? 1 : 0)) & 0xFF;
        carry = (value & 0x80) != 0;
        return setNZ(result);
    }

    private int ror(int value) {
        int result = (value >> 1) | (carry ? 0x80 : 0);
        carry = (value & 0x01) != 0;
        return setNZ(result);
    }

    private int inc(int value) {
        return setNZ((value + 1) & 0xFF);
    }

    private int dec(int value) {
        return setNZ((value - 1) & 0xFF);
    }

    private int loadWord(int target, int value) {
        zero = value == 0;
        negative = (value & 0x8000) != 0;
        return value;
    }

    /** ADDW: two chained byte additions. Flags come from the high byte, except Z (whole word). */
    private int addWord(int target, int value) {
        carry = false;
        int low = adc(target & 0xFF, value & 0xFF);
        int high = adc(target >> 8, value >> 8);
        int result = (high << 8) | low;
        zero = result == 0;
        return result;
    }

    private int subtractWord(int target, int value) {
        carry = true;
        int low = sbc(target & 0xFF, value & 0xFF);
        int high = sbc(target >> 8, value >> 8);
        int result = (high << 8) | low;
        zero = result == 0;
        return result;
    }

    private int compareWord(int target, int value) {
        int difference = target - value;
        carry = difference >= 0;
        zero = (difference & 0xFFFF) == 0;
        negative = (difference & 0x8000) != 0;
        return target;
    }

    private int setNZ(int value) {
        zero = value == 0;
        negative = (value & 0x80) != 0;
        return value;
    }

    // =============================================================================================
    // Bus and stack helpers
    // =============================================================================================

    private int fetch() {
        int value = bus.read(pc);
        pc = (pc + 1) & 0xFFFF;
        return value;
    }

    private int fetchWord() {
        int low = fetch();
        return low | (fetch() << 8);
    }

    /** Most internal cycles read the next program byte and throw it away. */
    private void dummyRead() {
        bus.read(pc);
    }

    private void idle() {
        bus.idle();
    }

    private int read(int address) {
        return bus.read(address & 0xFFFF);
    }

    private void write(int address, int value) {
        bus.write(address & 0xFFFF, value & 0xFF);
    }

    /** A direct page access: page $00 or $01 depending on the P flag, wrapping within it. */
    private int load(int offset) {
        return bus.read((directPage ? 0x100 : 0) | (offset & 0xFF));
    }

    private void store(int offset, int value) {
        bus.write((directPage ? 0x100 : 0) | (offset & 0xFF), value & 0xFF);
    }

    private void push(int value) {
        bus.write(0x100 | sp, value & 0xFF);
        sp = (sp - 1) & 0xFF;
    }

    private int pull() {
        sp = (sp + 1) & 0xFF;
        return bus.read(0x100 | sp);
    }

    private void pushReturnAddress() {
        push(pc >> 8);
        push(pc & 0xFF);
    }
}
