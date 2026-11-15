package jfc.core.cpu;

import java.util.function.IntUnaryOperator;

/**
 * The WDC 65C816 CPU core, as found inside the SNES's Ricoh 5A22.
 *
 * <h2>How this class is organized</h2>
 * <ol>
 *   <li><b>Execution:</b> {@link #step()} handles pending interrupts, then fetches one opcode
 *       and runs it.</li>
 *   <li><b>The opcode table</b> ({@link #execute}): one line per opcode, naming the instruction and
 *       its addressing mode, e.g. {@code case 0x7D -> adc(absoluteX(READ));}.</li>
 *   <li><b>Addressing modes:</b> methods that fetch operand bytes and work out an
 *       {@link EffectiveAddress}, spending the same bus cycles as the real chip.</li>
 *   <li><b>Instructions:</b> methods that do the actual work, written once for 8-bit and 16-bit
 *       operation.</li>
 *   <li><b>Helpers:</b> bus access, the stack, register widths and flag updates.</li>
 * </ol>
 *
 * <h2>Timing</h2>
 * The CPU itself counts nothing. Every cycle is a {@link CpuBus#read}, {@link CpuBus#write} or
 * {@link CpuBus#idle} call, and the order of these calls matches the cycle-by-cycle tables in the
 * WDC datasheet. The bus decides how long each cycle takes. Getting the number and kind of
 * cycles right is enough to get SNES timing right, and the SingleStepTests suite checks exactly
 * that.
 *
 * <h2>Width rules</h2>
 * In native mode the M flag selects an 8- or 16-bit accumulator and the X flag 8- or 16-bit
 * index registers. In emulation mode both are always 8-bit. Every instruction asks
 * {@link #isAccumulator8Bit()} or {@link #isIndex8Bit()} instead of reading the flags directly.
 * When registers are 8-bit, the accumulator's high byte keeps its value, while X and Y have
 * their high bytes forced to zero.
 */
public final class Cpu {

    // Interrupt vectors live in bank $00, just above the cartridge header.
    // Native mode gets its own set because the 65816 added new interrupt behavior (pushing PBR).
    private static final int VECTOR_NATIVE_COP = 0xFFE4;
    private static final int VECTOR_NATIVE_BRK = 0xFFE6;
    private static final int VECTOR_NATIVE_NMI = 0xFFEA;
    private static final int VECTOR_NATIVE_IRQ = 0xFFEE;
    private static final int VECTOR_EMULATION_COP = 0xFFF4;
    private static final int VECTOR_EMULATION_NMI = 0xFFFA;
    private static final int VECTOR_RESET = 0xFFFC;
    /** In emulation mode BRK shares the IRQ vector, like on the 6502. */
    private static final int VECTOR_EMULATION_IRQ_BRK = 0xFFFE;

    /**
     * Indexed addressing modes take an extra cycle to add the index when the addition carries
     * into the high byte. Reads can skip that cycle when there's no carry. Writes (and
     * read-modify-writes) always take it, because the CPU can't write until it's sure of the
     * address.
     */
    enum Access { READ, WRITE }

    private static final Access READ = Access.READ;
    private static final Access WRITE = Access.WRITE;

    private final CpuBus bus;
    private final Registers r = new Registers();
    private final StatusFlags p = r.p;

    /** NMI is edge-triggered: one press of the "button" means one interrupt. */
    private boolean nmiPending;
    /** IRQ is level-triggered: it keeps interrupting for as long as the line is held. */
    private boolean irqLineAsserted;
    /** Set by WAI: the CPU sleeps until any interrupt arrives. */
    private boolean waitingForInterrupt;
    /** Set by STP: the CPU halts until reset. */
    private boolean stopped;

    /** Notified before each instruction runs (for tracing and debugging tools). */
    public interface InstructionListener {
        void beforeInstruction(Registers registers);
    }

    private InstructionListener instructionListener;

    public Cpu(CpuBus bus) {
        this.bus = bus;
    }

    /** Installs (or, with null, removes) a listener called before every instruction. */
    public void setInstructionListener(InstructionListener listener) {
        this.instructionListener = listener;
    }

    /** Direct access to the registers, for tests and debugging tools. */
    public Registers registers() {
        return r;
    }

    // =============================================================================================
    // Execution
    // =============================================================================================

    /**
     * Puts the CPU into its power-on state and jumps through the RESET vector.
     *
     * <p>The 65816 always wakes up in emulation mode, behaving like a 6502. Boot code usually
     * switches to native mode right away with {@code CLC; XCE}.
     */
    public void reset() {
        r.emulation = true;
        p.memory8 = true;
        p.index8 = true;
        p.irqDisable = true;
        p.decimal = false;
        r.d = 0;
        r.dbr = 0;
        r.pbr = 0;
        r.sp = 0x0100 | (r.sp & 0xFF);
        r.x &= 0xFF;
        r.y &= 0xFF;
        waitingForInterrupt = false;
        stopped = false;
        nmiPending = false;
        r.pc = read(VECTOR_RESET) | (read(VECTOR_RESET + 1) << 8);
    }

    /** Signals a non-maskable interrupt (the SNES raises one at the start of vertical blank). */
    public void triggerNmi() {
        nmiPending = true;
    }

    /** Sets the level of the IRQ line (the SNES raises it from its H/V timers). */
    public void setIrqLine(boolean asserted) {
        irqLineAsserted = asserted;
    }

    public boolean isWaitingForInterrupt() {
        return waitingForInterrupt;
    }

    public boolean isStopped() {
        return stopped;
    }

    /**
     * Runs one instruction, or services one interrupt, or spends one idle cycle while halted.
     *
     * <p>Interrupts are only taken between instructions. On real hardware they're sampled
     * during the last cycle of the previous instruction. Checking at the start of the next one
     * is equivalent for everything except a few cycle-level races.
     */
    public void step() {
        if (stopped) {
            bus.idle();
            return;
        }
        if (waitingForInterrupt) {
            if (!nmiPending && !irqLineAsserted) {
                bus.idle();
                return;
            }
            // Any interrupt wakes WAI, even an IRQ masked by the I flag. A masked IRQ
            // just resumes execution after the WAI without running the handler. Games use this
            // for fast, low-latency waits on a timer.
            waitingForInterrupt = false;
        }
        if (nmiPending) {
            nmiPending = false;
            hardwareInterrupt(VECTOR_NATIVE_NMI, VECTOR_EMULATION_NMI);
            return;
        }
        if (irqLineAsserted && !p.irqDisable) {
            hardwareInterrupt(VECTOR_NATIVE_IRQ, VECTOR_EMULATION_IRQ_BRK);
            return;
        }
        if (instructionListener != null) {
            instructionListener.beforeInstruction(r);
        }
        execute(fetchByte());
    }

    // =============================================================================================
    // The opcode table
    // =============================================================================================

    /**
     * Runs the instruction for {@code opcode}, whose byte has already been fetched.
     *
     * <p>Grouped by instruction. Addressing mode names follow the WDC datasheet:
     * {@code dp} = direct page, {@code abs} = absolute, {@code long} = 24-bit absolute,
     * {@code (..)} = indirect, {@code [..]} = indirect long, {@code sr,S} = stack relative.
     */
    private void execute(int opcode) {
        switch (opcode) {
            // ---- Loads ----------------------------------------------------------------------
            case 0xA9 -> lda(immediateM());                     // LDA #imm
            case 0xA5 -> lda(direct());                         // LDA dp
            case 0xB5 -> lda(directX());                        // LDA dp,X
            case 0xAD -> lda(absolute());                       // LDA abs
            case 0xBD -> lda(absoluteX(READ));                  // LDA abs,X
            case 0xB9 -> lda(absoluteY(READ));                  // LDA abs,Y
            case 0xAF -> lda(absoluteLong());                   // LDA long
            case 0xBF -> lda(absoluteLongX());                  // LDA long,X
            case 0xB2 -> lda(directIndirect());                 // LDA (dp)
            case 0xA1 -> lda(directIndexedIndirect());          // LDA (dp,X)
            case 0xB1 -> lda(directIndirectIndexed(READ));      // LDA (dp),Y
            case 0xA7 -> lda(directIndirectLong());             // LDA [dp]
            case 0xB7 -> lda(directIndirectLongIndexed());      // LDA [dp],Y
            case 0xA3 -> lda(stackRelative());                  // LDA sr,S
            case 0xB3 -> lda(stackRelativeIndirectIndexed());   // LDA (sr,S),Y

            case 0xA2 -> ldx(immediateX());                     // LDX #imm
            case 0xA6 -> ldx(direct());                         // LDX dp
            case 0xB6 -> ldx(directY());                        // LDX dp,Y
            case 0xAE -> ldx(absolute());                       // LDX abs
            case 0xBE -> ldx(absoluteY(READ));                  // LDX abs,Y

            case 0xA0 -> ldy(immediateX());                     // LDY #imm
            case 0xA4 -> ldy(direct());                         // LDY dp
            case 0xB4 -> ldy(directX());                        // LDY dp,X
            case 0xAC -> ldy(absolute());                       // LDY abs
            case 0xBC -> ldy(absoluteX(READ));                  // LDY abs,X

            // ---- Stores ---------------------------------------------------------------------
            case 0x85 -> sta(direct());                         // STA dp
            case 0x95 -> sta(directX());                        // STA dp,X
            case 0x8D -> sta(absolute());                       // STA abs
            case 0x9D -> sta(absoluteX(WRITE));                 // STA abs,X
            case 0x99 -> sta(absoluteY(WRITE));                 // STA abs,Y
            case 0x8F -> sta(absoluteLong());                   // STA long
            case 0x9F -> sta(absoluteLongX());                  // STA long,X
            case 0x92 -> sta(directIndirect());                 // STA (dp)
            case 0x81 -> sta(directIndexedIndirect());          // STA (dp,X)
            case 0x91 -> sta(directIndirectIndexed(WRITE));     // STA (dp),Y
            case 0x87 -> sta(directIndirectLong());             // STA [dp]
            case 0x97 -> sta(directIndirectLongIndexed());      // STA [dp],Y
            case 0x83 -> sta(stackRelative());                  // STA sr,S
            case 0x93 -> sta(stackRelativeIndirectIndexed());   // STA (sr,S),Y

            case 0x86 -> stx(direct());                         // STX dp
            case 0x96 -> stx(directY());                        // STX dp,Y
            case 0x8E -> stx(absolute());                       // STX abs

            case 0x84 -> sty(direct());                         // STY dp
            case 0x94 -> sty(directX());                        // STY dp,X
            case 0x8C -> sty(absolute());                       // STY abs

            case 0x64 -> stz(direct());                         // STZ dp
            case 0x74 -> stz(directX());                        // STZ dp,X
            case 0x9C -> stz(absolute());                       // STZ abs
            case 0x9E -> stz(absoluteX(WRITE));                 // STZ abs,X

            // ---- Logic ----------------------------------------------------------------------
            case 0x09 -> ora(immediateM());                     // ORA #imm
            case 0x05 -> ora(direct());                         // ORA dp
            case 0x15 -> ora(directX());                        // ORA dp,X
            case 0x0D -> ora(absolute());                       // ORA abs
            case 0x1D -> ora(absoluteX(READ));                  // ORA abs,X
            case 0x19 -> ora(absoluteY(READ));                  // ORA abs,Y
            case 0x0F -> ora(absoluteLong());                   // ORA long
            case 0x1F -> ora(absoluteLongX());                  // ORA long,X
            case 0x12 -> ora(directIndirect());                 // ORA (dp)
            case 0x01 -> ora(directIndexedIndirect());          // ORA (dp,X)
            case 0x11 -> ora(directIndirectIndexed(READ));      // ORA (dp),Y
            case 0x07 -> ora(directIndirectLong());             // ORA [dp]
            case 0x17 -> ora(directIndirectLongIndexed());      // ORA [dp],Y
            case 0x03 -> ora(stackRelative());                  // ORA sr,S
            case 0x13 -> ora(stackRelativeIndirectIndexed());   // ORA (sr,S),Y

            case 0x29 -> and(immediateM());                     // AND #imm
            case 0x25 -> and(direct());                         // AND dp
            case 0x35 -> and(directX());                        // AND dp,X
            case 0x2D -> and(absolute());                       // AND abs
            case 0x3D -> and(absoluteX(READ));                  // AND abs,X
            case 0x39 -> and(absoluteY(READ));                  // AND abs,Y
            case 0x2F -> and(absoluteLong());                   // AND long
            case 0x3F -> and(absoluteLongX());                  // AND long,X
            case 0x32 -> and(directIndirect());                 // AND (dp)
            case 0x21 -> and(directIndexedIndirect());          // AND (dp,X)
            case 0x31 -> and(directIndirectIndexed(READ));      // AND (dp),Y
            case 0x27 -> and(directIndirectLong());             // AND [dp]
            case 0x37 -> and(directIndirectLongIndexed());      // AND [dp],Y
            case 0x23 -> and(stackRelative());                  // AND sr,S
            case 0x33 -> and(stackRelativeIndirectIndexed());   // AND (sr,S),Y

            case 0x49 -> eor(immediateM());                     // EOR #imm
            case 0x45 -> eor(direct());                         // EOR dp
            case 0x55 -> eor(directX());                        // EOR dp,X
            case 0x4D -> eor(absolute());                       // EOR abs
            case 0x5D -> eor(absoluteX(READ));                  // EOR abs,X
            case 0x59 -> eor(absoluteY(READ));                  // EOR abs,Y
            case 0x4F -> eor(absoluteLong());                   // EOR long
            case 0x5F -> eor(absoluteLongX());                  // EOR long,X
            case 0x52 -> eor(directIndirect());                 // EOR (dp)
            case 0x41 -> eor(directIndexedIndirect());          // EOR (dp,X)
            case 0x51 -> eor(directIndirectIndexed(READ));      // EOR (dp),Y
            case 0x47 -> eor(directIndirectLong());             // EOR [dp]
            case 0x57 -> eor(directIndirectLongIndexed());      // EOR [dp],Y
            case 0x43 -> eor(stackRelative());                  // EOR sr,S
            case 0x53 -> eor(stackRelativeIndirectIndexed());   // EOR (sr,S),Y

            case 0x89 -> bitImmediate(immediateM());            // BIT #imm
            case 0x24 -> bit(direct());                         // BIT dp
            case 0x34 -> bit(directX());                        // BIT dp,X
            case 0x2C -> bit(absolute());                       // BIT abs
            case 0x3C -> bit(absoluteX(READ));                  // BIT abs,X

            // ---- Arithmetic -----------------------------------------------------------------
            case 0x69 -> adc(immediateM());                     // ADC #imm
            case 0x65 -> adc(direct());                         // ADC dp
            case 0x75 -> adc(directX());                        // ADC dp,X
            case 0x6D -> adc(absolute());                       // ADC abs
            case 0x7D -> adc(absoluteX(READ));                  // ADC abs,X
            case 0x79 -> adc(absoluteY(READ));                  // ADC abs,Y
            case 0x6F -> adc(absoluteLong());                   // ADC long
            case 0x7F -> adc(absoluteLongX());                  // ADC long,X
            case 0x72 -> adc(directIndirect());                 // ADC (dp)
            case 0x61 -> adc(directIndexedIndirect());          // ADC (dp,X)
            case 0x71 -> adc(directIndirectIndexed(READ));      // ADC (dp),Y
            case 0x67 -> adc(directIndirectLong());             // ADC [dp]
            case 0x77 -> adc(directIndirectLongIndexed());      // ADC [dp],Y
            case 0x63 -> adc(stackRelative());                  // ADC sr,S
            case 0x73 -> adc(stackRelativeIndirectIndexed());   // ADC (sr,S),Y

            case 0xE9 -> sbc(immediateM());                     // SBC #imm
            case 0xE5 -> sbc(direct());                         // SBC dp
            case 0xF5 -> sbc(directX());                        // SBC dp,X
            case 0xED -> sbc(absolute());                       // SBC abs
            case 0xFD -> sbc(absoluteX(READ));                  // SBC abs,X
            case 0xF9 -> sbc(absoluteY(READ));                  // SBC abs,Y
            case 0xEF -> sbc(absoluteLong());                   // SBC long
            case 0xFF -> sbc(absoluteLongX());                  // SBC long,X
            case 0xF2 -> sbc(directIndirect());                 // SBC (dp)
            case 0xE1 -> sbc(directIndexedIndirect());          // SBC (dp,X)
            case 0xF1 -> sbc(directIndirectIndexed(READ));      // SBC (dp),Y
            case 0xE7 -> sbc(directIndirectLong());             // SBC [dp]
            case 0xF7 -> sbc(directIndirectLongIndexed());      // SBC [dp],Y
            case 0xE3 -> sbc(stackRelative());                  // SBC sr,S
            case 0xF3 -> sbc(stackRelativeIndirectIndexed());   // SBC (sr,S),Y

            // ---- Comparisons ----------------------------------------------------------------
            case 0xC9 -> cmp(immediateM());                     // CMP #imm
            case 0xC5 -> cmp(direct());                         // CMP dp
            case 0xD5 -> cmp(directX());                        // CMP dp,X
            case 0xCD -> cmp(absolute());                       // CMP abs
            case 0xDD -> cmp(absoluteX(READ));                  // CMP abs,X
            case 0xD9 -> cmp(absoluteY(READ));                  // CMP abs,Y
            case 0xCF -> cmp(absoluteLong());                   // CMP long
            case 0xDF -> cmp(absoluteLongX());                  // CMP long,X
            case 0xD2 -> cmp(directIndirect());                 // CMP (dp)
            case 0xC1 -> cmp(directIndexedIndirect());          // CMP (dp,X)
            case 0xD1 -> cmp(directIndirectIndexed(READ));      // CMP (dp),Y
            case 0xC7 -> cmp(directIndirectLong());             // CMP [dp]
            case 0xD7 -> cmp(directIndirectLongIndexed());      // CMP [dp],Y
            case 0xC3 -> cmp(stackRelative());                  // CMP sr,S
            case 0xD3 -> cmp(stackRelativeIndirectIndexed());   // CMP (sr,S),Y

            case 0xE0 -> cpx(immediateX());                     // CPX #imm
            case 0xE4 -> cpx(direct());                         // CPX dp
            case 0xEC -> cpx(absolute());                       // CPX abs

            case 0xC0 -> cpy(immediateX());                     // CPY #imm
            case 0xC4 -> cpy(direct());                         // CPY dp
            case 0xCC -> cpy(absolute());                       // CPY abs

            // ---- Read-modify-write: shifts, rotates, increments, bit test-and-set -----------
            case 0x0A -> modifyAccumulator(this::shiftLeft);    // ASL A
            case 0x06 -> modify(direct(), this::shiftLeft);     // ASL dp
            case 0x16 -> modify(directX(), this::shiftLeft);    // ASL dp,X
            case 0x0E -> modify(absolute(), this::shiftLeft);   // ASL abs
            case 0x1E -> modify(absoluteX(WRITE), this::shiftLeft); // ASL abs,X

            case 0x4A -> modifyAccumulator(this::shiftRight);   // LSR A
            case 0x46 -> modify(direct(), this::shiftRight);    // LSR dp
            case 0x56 -> modify(directX(), this::shiftRight);   // LSR dp,X
            case 0x4E -> modify(absolute(), this::shiftRight);  // LSR abs
            case 0x5E -> modify(absoluteX(WRITE), this::shiftRight); // LSR abs,X

            case 0x2A -> modifyAccumulator(this::rotateLeft);   // ROL A
            case 0x26 -> modify(direct(), this::rotateLeft);    // ROL dp
            case 0x36 -> modify(directX(), this::rotateLeft);   // ROL dp,X
            case 0x2E -> modify(absolute(), this::rotateLeft);  // ROL abs
            case 0x3E -> modify(absoluteX(WRITE), this::rotateLeft); // ROL abs,X

            case 0x6A -> modifyAccumulator(this::rotateRight);  // ROR A
            case 0x66 -> modify(direct(), this::rotateRight);   // ROR dp
            case 0x76 -> modify(directX(), this::rotateRight);  // ROR dp,X
            case 0x6E -> modify(absolute(), this::rotateRight); // ROR abs
            case 0x7E -> modify(absoluteX(WRITE), this::rotateRight); // ROR abs,X

            case 0x1A -> modifyAccumulator(this::increment);    // INC A
            case 0xE6 -> modify(direct(), this::increment);     // INC dp
            case 0xF6 -> modify(directX(), this::increment);    // INC dp,X
            case 0xEE -> modify(absolute(), this::increment);   // INC abs
            case 0xFE -> modify(absoluteX(WRITE), this::increment); // INC abs,X

            case 0x3A -> modifyAccumulator(this::decrement);    // DEC A
            case 0xC6 -> modify(direct(), this::decrement);     // DEC dp
            case 0xD6 -> modify(directX(), this::decrement);    // DEC dp,X
            case 0xCE -> modify(absolute(), this::decrement);   // DEC abs
            case 0xDE -> modify(absoluteX(WRITE), this::decrement); // DEC abs,X

            case 0x04 -> modify(direct(), this::testAndSetBits);     // TSB dp
            case 0x0C -> modify(absolute(), this::testAndSetBits);   // TSB abs
            case 0x14 -> modify(direct(), this::testAndResetBits);   // TRB dp
            case 0x1C -> modify(absolute(), this::testAndResetBits); // TRB abs

            case 0xE8 -> inx();                                 // INX
            case 0xC8 -> iny();                                 // INY
            case 0xCA -> dex();                                 // DEX
            case 0x88 -> dey();                                 // DEY

            // ---- Branches -------------------------------------------------------------------
            case 0x10 -> branchIf(!p.negative);                 // BPL
            case 0x30 -> branchIf(p.negative);                  // BMI
            case 0x50 -> branchIf(!p.overflow);                 // BVC
            case 0x70 -> branchIf(p.overflow);                  // BVS
            case 0x90 -> branchIf(!p.carry);                    // BCC
            case 0xB0 -> branchIf(p.carry);                     // BCS
            case 0xD0 -> branchIf(!p.zero);                     // BNE
            case 0xF0 -> branchIf(p.zero);                      // BEQ
            case 0x80 -> branchIf(true);                        // BRA
            case 0x82 -> branchLong();                          // BRL

            // ---- Jumps, calls and returns ---------------------------------------------------
            case 0x4C -> jumpAbsolute();                        // JMP abs
            case 0x5C -> jumpLong();                            // JML long
            case 0x6C -> jumpIndirect();                        // JMP (abs)
            case 0x7C -> jumpIndexedIndirect();                 // JMP (abs,X)
            case 0xDC -> jumpIndirectLong();                    // JML [abs]
            case 0x20 -> jsrAbsolute();                         // JSR abs
            case 0xFC -> jsrIndexedIndirect();                  // JSR (abs,X)
            case 0x22 -> jsl();                                 // JSL long
            case 0x60 -> rts();                                 // RTS
            case 0x6B -> rtl();                                 // RTL
            case 0x40 -> rti();                                 // RTI

            // ---- Interrupts and halting -----------------------------------------------------
            case 0x00 -> softwareInterrupt(VECTOR_NATIVE_BRK, VECTOR_EMULATION_IRQ_BRK); // BRK
            case 0x02 -> softwareInterrupt(VECTOR_NATIVE_COP, VECTOR_EMULATION_COP);     // COP
            case 0xCB -> wai();                                 // WAI
            case 0xDB -> stp();                                 // STP

            // ---- Stack ----------------------------------------------------------------------
            case 0x48 -> pha();                                 // PHA
            case 0xDA -> phx();                                 // PHX
            case 0x5A -> phy();                                 // PHY
            case 0x08 -> php();                                 // PHP
            case 0x8B -> phb();                                 // PHB
            case 0x4B -> phk();                                 // PHK
            case 0x0B -> phd();                                 // PHD
            case 0x68 -> pla();                                 // PLA
            case 0xFA -> plx();                                 // PLX
            case 0x7A -> ply();                                 // PLY
            case 0x28 -> plp();                                 // PLP
            case 0xAB -> plb();                                 // PLB
            case 0x2B -> pld();                                 // PLD
            case 0xF4 -> pea();                                 // PEA abs
            case 0xD4 -> pei();                                 // PEI (dp)
            case 0x62 -> per();                                 // PER rel16

            // ---- Register transfers ---------------------------------------------------------
            case 0xAA -> tax();                                 // TAX
            case 0xA8 -> tay();                                 // TAY
            case 0x8A -> txa();                                 // TXA
            case 0x98 -> tya();                                 // TYA
            case 0x9B -> txy();                                 // TXY
            case 0xBB -> tyx();                                 // TYX
            case 0xBA -> tsx();                                 // TSX
            case 0x9A -> txs();                                 // TXS
            case 0x5B -> tcd();                                 // TCD
            case 0x7B -> tdc();                                 // TDC
            case 0x1B -> tcs();                                 // TCS
            case 0x3B -> tsc();                                 // TSC
            case 0xEB -> xba();                                 // XBA
            case 0xFB -> xce();                                 // XCE

            // ---- Status flags ---------------------------------------------------------------
            case 0x18 -> { idle(); p.carry = false; }          // CLC
            case 0x38 -> { idle(); p.carry = true; }           // SEC
            case 0x58 -> { idle(); p.irqDisable = false; }     // CLI
            case 0x78 -> { idle(); p.irqDisable = true; }      // SEI
            case 0xD8 -> { idle(); p.decimal = false; }        // CLD
            case 0xF8 -> { idle(); p.decimal = true; }         // SED
            case 0xB8 -> { idle(); p.overflow = false; }       // CLV
            case 0xC2 -> rep();                                 // REP #imm
            case 0xE2 -> sep();                                 // SEP #imm

            // ---- Block moves ----------------------------------------------------------------
            case 0x54 -> blockMove(+1);                         // MVN src,dst
            case 0x44 -> blockMove(-1);                         // MVP src,dst

            // ---- Do-nothings ----------------------------------------------------------------
            case 0xEA -> idle();                                // NOP
            case 0x42 -> wdm();                                 // WDM (reserved; skips a byte)

            default -> throw new IllegalStateException("Unreachable: every opcode is defined");
        }
    }

    // =============================================================================================
    // Addressing modes
    //
    // Each method fetches the operand bytes that follow the opcode, spends any extra cycles the
    // hardware spends, and returns where the operand lives. The instruction then reads or writes
    // that location.
    // =============================================================================================

    /**
     * {@code #imm}, sized by the M flag. The operand is the next 1 or 2 bytes of the program.
     */
    private EffectiveAddress immediateM() {
        return immediate(!isAccumulator8Bit());
    }

    /** {@code #imm}, sized by the X flag (for LDX, LDY, CPX and CPY). */
    private EffectiveAddress immediateX() {
        return immediate(!isIndex8Bit());
    }

    private EffectiveAddress immediate(boolean wide) {
        EffectiveAddress operand = EffectiveAddress.inBank(r.pbr, r.pc);
        // Step over the operand now; the instruction reads it straight afterwards.
        r.pc = (r.pc + (wide ? 2 : 1)) & 0xFFFF;
        return operand;
    }

    /** {@code abs}: a 16-bit address in the data bank. */
    private EffectiveAddress absolute() {
        int offset = fetchWord();
        return EffectiveAddress.linear((r.dbr << 16) | offset);
    }

    /** {@code abs,X} */
    private EffectiveAddress absoluteX(Access access) {
        return absoluteIndexed(r.x, access);
    }

    /** {@code abs,Y} */
    private EffectiveAddress absoluteY(Access access) {
        return absoluteIndexed(r.y, access);
    }

    private EffectiveAddress absoluteIndexed(int index, Access access) {
        int base = (r.dbr << 16) | fetchWord();
        int address = base + index;
        indexingPenalty(base, address, access);
        return EffectiveAddress.linear(address);
    }

    /** {@code long}: a full 24-bit address. */
    private EffectiveAddress absoluteLong() {
        int offset = fetchWord();
        int bank = fetchByte();
        return EffectiveAddress.linear((bank << 16) | offset);
    }

    /** {@code long,X} */
    private EffectiveAddress absoluteLongX() {
        int offset = fetchWord();
        int bank = fetchByte();
        return EffectiveAddress.linear(((bank << 16) | offset) + r.x);
    }

    /**
     * {@code dp}: an 8-bit offset from the direct page register D. Direct page is the 65816's
     * version of the 6502 zero page. D moves it anywhere in bank $00.
     */
    private EffectiveAddress direct() {
        int offset = fetchByte();
        directPagePenalty();
        return EffectiveAddress.inBank(0, directAddress(offset));
    }

    /** {@code dp,X}. One extra cycle is always spent adding the index. */
    private EffectiveAddress directX() {
        int offset = fetchByte();
        directPagePenalty();
        idle();
        return EffectiveAddress.inBank(0, directAddress(offset + r.x));
    }

    /** {@code dp,Y} (only used by LDX and STX). */
    private EffectiveAddress directY() {
        int offset = fetchByte();
        directPagePenalty();
        idle();
        return EffectiveAddress.inBank(0, directAddress(offset + r.y));
    }

    /** {@code (dp)}: a 16-bit pointer in direct page, pointing into the data bank. */
    private EffectiveAddress directIndirect() {
        int offset = fetchByte();
        directPagePenalty();
        int pointer = readDirectPointer(offset);
        return EffectiveAddress.linear((r.dbr << 16) | pointer);
    }

    /** {@code (dp,X)}: X picks which pointer in direct page to use (a table of pointers). */
    private EffectiveAddress directIndexedIndirect() {
        int offset = fetchByte();
        directPagePenalty();
        idle();
        int pointer = readDirectPointer(offset + r.x);
        return EffectiveAddress.linear((r.dbr << 16) | pointer);
    }

    /** {@code (dp),Y}: a pointer in direct page, then Y added to it (walking an array). */
    private EffectiveAddress directIndirectIndexed(Access access) {
        int offset = fetchByte();
        directPagePenalty();
        int base = (r.dbr << 16) | readDirectPointer(offset);
        int address = base + r.y;
        indexingPenalty(base, address, access);
        return EffectiveAddress.linear(address);
    }

    /** {@code [dp]}: a 24-bit pointer in direct page. */
    private EffectiveAddress directIndirectLong() {
        int offset = fetchByte();
        directPagePenalty();
        return EffectiveAddress.linear(readDirectLongPointer(offset));
    }

    /** {@code [dp],Y} */
    private EffectiveAddress directIndirectLongIndexed() {
        int offset = fetchByte();
        directPagePenalty();
        return EffectiveAddress.linear(readDirectLongPointer(offset) + r.y);
    }

    /**
     * {@code sr,S}: an offset from the stack pointer, for reading parameters and locals pushed
     * on the stack. It costs one extra cycle to add the offset.
     */
    private EffectiveAddress stackRelative() {
        int offset = fetchByte();
        idle();
        return EffectiveAddress.inBank(0, r.sp + offset);
    }

    /** {@code (sr,S),Y}: a pointer stored on the stack, plus Y. */
    private EffectiveAddress stackRelativeIndirectIndexed() {
        int offset = fetchByte();
        idle();
        int pointerAddress = r.sp + offset;
        int low = read(pointerAddress & 0xFFFF);
        int high = read((pointerAddress + 1) & 0xFFFF);
        idle();
        int base = (r.dbr << 16) | (high << 8) | low;
        return EffectiveAddress.linear(base + r.y);
    }

    /**
     * Turns a direct page offset (which may already include an index) into a bank $00 address.
     *
     * <p>In emulation mode with D on a page boundary, indexing wraps within that page, just as
     * zero page indexing wraps on a 6502 ({@code LDA $F0,X} with X=$20 reads $0010). Old 6502 code
     * depends on that. Otherwise the address simply wraps within bank $00.
     */
    private int directAddress(int offset) {
        if (r.emulation && (r.d & 0xFF) == 0) {
            return (r.d & 0xFF00) | (offset & 0xFF);
        }
        return (r.d + offset) & 0xFFFF;
    }

    /** Reads a 16-bit pointer from direct page, following the direct page wrapping rules. */
    private int readDirectPointer(int offset) {
        int low = read(directAddress(offset));
        int high = read(directAddress(offset + 1));
        return (high << 8) | low;
    }

    /**
     * Reads a 24-bit pointer from direct page. [dp] modes didn't exist on the 6502, so they never
     * use 6502-style page wrapping. They always wrap within bank $00.
     */
    private int readDirectLongPointer(int offset) {
        int base = r.d + offset;
        int low = read(base & 0xFFFF);
        int high = read((base + 1) & 0xFFFF);
        int bank = read((base + 2) & 0xFFFF);
        return (bank << 16) | (high << 8) | low;
    }

    /**
     * When the low byte of D isn't zero, direct page addresses need a real 16-bit addition. The
     * CPU spends an extra cycle on it, which is why games keep D page-aligned.
     */
    private void directPagePenalty() {
        if ((r.d & 0xFF) != 0) {
            idle();
        }
    }

    /**
     * The extra cycle for indexed addressing. It's skipped only for reads with 8-bit index
     * registers where adding the index didn't carry into a new page.
     */
    private void indexingPenalty(int base, int indexed, Access access) {
        // Dropping the low 8 bits leaves the page (and bank); if those differ, the addition
        // carried out of the low byte and the CPU needed a cycle to fix up the high byte.
        boolean crossedPage = (base & 0xFFFF00) != (indexed & 0xFFFF00);
        if (access == WRITE || !isIndex8Bit() || crossedPage) {
            idle();
        }
    }

    // =============================================================================================
    // Instructions: loads, stores, logic, arithmetic and comparisons
    // =============================================================================================

    private void lda(EffectiveAddress operand) {
        setAccumulator(readM(operand));
    }

    private void ldx(EffectiveAddress operand) {
        setX(readX(operand));
    }

    private void ldy(EffectiveAddress operand) {
        setY(readX(operand));
    }

    private void sta(EffectiveAddress operand) {
        writeM(operand, r.a);
    }

    private void stx(EffectiveAddress operand) {
        writeX(operand, r.x);
    }

    private void sty(EffectiveAddress operand) {
        writeX(operand, r.y);
    }

    private void stz(EffectiveAddress operand) {
        writeM(operand, 0);
    }

    private void ora(EffectiveAddress operand) {
        setAccumulator(r.a | readM(operand));
    }

    private void and(EffectiveAddress operand) {
        setAccumulator(r.a & readM(operand));
    }

    private void eor(EffectiveAddress operand) {
        setAccumulator(r.a ^ readM(operand));
    }

    /**
     * BIT tests bits without changing A. Z reflects {@code A AND operand}, while N and V are
     * copied straight from the operand's top two bits, a quick way to test flag bits in memory.
     */
    private void bit(EffectiveAddress operand) {
        boolean wide = !isAccumulator8Bit();
        int value = readData(operand, wide);
        p.zero = (r.a & value & mask(wide)) == 0;
        p.negative = (value & signBit(wide)) != 0;
        p.overflow = (value & (signBit(wide) >> 1)) != 0;
    }

    /** The immediate form of BIT only affects Z: N and V would just copy the constant. */
    private void bitImmediate(EffectiveAddress operand) {
        boolean wide = !isAccumulator8Bit();
        p.zero = (r.a & readData(operand, wide) & mask(wide)) == 0;
    }

    private void adc(EffectiveAddress operand) {
        addWithCarry(readM(operand));
    }

    /**
     * SBC is addition of the inverted operand: A - M - borrow = A + ~M + carry, where carry
     * set means "no borrow". The decimal-mode correction works differently, so it has its own
     * method instead of reusing {@link #addWithCarry} wholesale.
     */
    private void sbc(EffectiveAddress operand) {
        subtractWithBorrow(readM(operand));
    }

    private void cmp(EffectiveAddress operand) {
        boolean wide = !isAccumulator8Bit();
        compare(r.a, readData(operand, wide), wide);
    }

    private void cpx(EffectiveAddress operand) {
        boolean wide = !isIndex8Bit();
        compare(r.x, readData(operand, wide), wide);
    }

    private void cpy(EffectiveAddress operand) {
        boolean wide = !isIndex8Bit();
        compare(r.y, readData(operand, wide), wide);
    }

    /**
     * Compares by subtracting without storing the result. C is set when register >= operand
     * (unsigned), Z when they're equal, N from the top bit of the difference.
     */
    private void compare(int register, int operand, boolean wide) {
        int mask = mask(wide);
        int difference = (register & mask) - operand;
        p.carry = difference >= 0;
        setNZ(difference, wide);
    }

    /**
     * ADC: A = A + operand + carry, in binary or binary-coded decimal (BCD) depending on D.
     *
     * <p>In BCD each nibble holds one decimal digit (0-9), so $19 + $01 must give $20, not $1A. The
     * hardware adds one nibble at a time. Whenever a nibble goes past 9 it adds 6 to skip the six
     * unused codes A-F, and the carry ripples into the next nibble. Note one 65816 detail: the
     * overflow flag is computed <i>before</i> the top digit is corrected.
     */
    private void addWithCarry(int operand) {
        boolean wide = !isAccumulator8Bit();
        int accumulator = r.a & mask(wide);
        int digits = wide ? 4 : 2;
        int carryIn = p.carry ? 1 : 0;
        int result;
        if (!p.decimal) {
            result = accumulator + operand + carryIn;
        } else {
            // Work up from the lowest digit. "result" holds the corrected lower digits so far.
            result = 0;
            int carry = carryIn;
            for (int digit = 0; digit < digits; digit++) {
                int shift = digit * 4;
                int digitMask = 0xF << shift;
                int lowerMask = (1 << shift) - 1;
                result = (accumulator & digitMask) + (operand & digitMask) + (carry << shift)
                        + (result & lowerMask);
                if (digit == digits - 1) {
                    // The top digit is corrected after V is computed (below).
                    break;
                }
                // "result" covers this digit and those below it. It exceeds 9 in this digit
                // exactly when it's above 9 followed by all-ones below (e.g. $9F for digit 1).
                if (result > (0x9 << shift | lowerMask)) {
                    result += 0x6 << shift;
                }
                // Anything above $F (or $FF, ...) in these digits carries into the next one.
                carry = result > (digitMask | lowerMask) ? 1 : 0;
            }
        }
        // Signed overflow: both inputs had the same sign, and the result's sign differs.
        p.overflow = (~(accumulator ^ operand) & (accumulator ^ result) & signBit(wide)) != 0;
        if (p.decimal) {
            // Correct the top digit: past 9 (above $9F, or $9FFF when 16-bit) means add 6.
            int topShift = (digits - 1) * 4;
            int topDigitLimit = (0x9 << topShift) | ((1 << topShift) - 1);
            if (result > topDigitLimit) {
                result += 0x6 << topShift;
            }
        }
        p.carry = result > mask(wide);
        setAccumulator(result);
    }

    /**
     * SBC: A = A - operand - borrow, in binary or BCD. The binary form is ADC of the inverted
     * operand. In decimal mode each digit that borrowed (didn't produce a carry) is corrected
     * by subtracting 6.
     */
    private void subtractWithBorrow(int operand) {
        boolean wide = !isAccumulator8Bit();
        int mask = mask(wide);
        int accumulator = r.a & mask;
        int inverted = ~operand & mask;
        int digits = wide ? 4 : 2;
        int carryIn = p.carry ? 1 : 0;
        int result;
        if (!p.decimal) {
            result = accumulator + inverted + carryIn;
        } else {
            result = 0;
            int carry = carryIn;
            for (int digit = 0; digit < digits; digit++) {
                int shift = digit * 4;
                int digitMask = 0xF << shift;
                int lowerMask = (1 << shift) - 1;
                result = (accumulator & digitMask) + (inverted & digitMask) + (carry << shift)
                        + (result & lowerMask);
                if (digit == digits - 1) {
                    break;
                }
                // No carry out of this digit means it borrowed: step back over A-F.
                if (result <= (digitMask | lowerMask)) {
                    result -= 0x6 << shift;
                }
                carry = result > (digitMask | lowerMask) ? 1 : 0;
            }
        }
        int signBit = signBit(wide);
        p.overflow = (~(accumulator ^ inverted) & (accumulator ^ result) & signBit) != 0;
        if (p.decimal && result <= mask) {
            result -= 0x6 << ((digits - 1) * 4);
        }
        p.carry = result > mask;
        setAccumulator(result);
    }

    // =============================================================================================
    // Instructions: read-modify-write
    //
    // Each operation below takes the old value and returns the new one, setting flags along the
    // way. modify() and modifyAccumulator() handle where the value comes from and goes to.
    // =============================================================================================

    /**
     * Read-modify-write on memory: read the operand, spend one cycle computing, write it back.
     * For 16-bit operands the high byte is written first. That isn't a choice we make; it's the
     * order the 65816 drives the bus in.
     */
    private void modify(EffectiveAddress operand, IntUnaryOperator operation) {
        boolean wide = !isAccumulator8Bit();
        int value = readData(operand, wide);
        // The 6502 wrote the unmodified value back during this cycle, and the 65816 still pulls
        // its read/write pin low here in emulation mode. But it also signals that the address
        // isn't valid (VDA and VPA both low), so memory ignores it. To the system it's just
        // an internal operation.
        idle();
        int result = operation.applyAsInt(value);
        if (wide) {
            write(operand.plus(1), result >> 8);
        }
        write(operand.address(), result);
    }

    /** Read-modify-write on the accumulator itself (e.g. {@code ASL A}). */
    private void modifyAccumulator(IntUnaryOperator operation) {
        idle();
        if (isAccumulator8Bit()) {
            r.a = (r.a & 0xFF00) | (operation.applyAsInt(r.a & 0xFF) & 0xFF);
        } else {
            r.a = operation.applyAsInt(r.a) & 0xFFFF;
        }
    }

    /** ASL: shift left, top bit into carry, zero into bit 0. */
    private int shiftLeft(int value) {
        boolean wide = !isAccumulator8Bit();
        p.carry = (value & signBit(wide)) != 0;
        int result = (value << 1) & mask(wide);
        setNZ(result, wide);
        return result;
    }

    /** LSR: shift right, bit 0 into carry, zero into the top bit. */
    private int shiftRight(int value) {
        boolean wide = !isAccumulator8Bit();
        p.carry = (value & 1) != 0;
        int result = value >>> 1;
        setNZ(result, wide);
        return result;
    }

    /** ROL: shift left through carry, a 9- or 17-bit rotation. */
    private int rotateLeft(int value) {
        boolean wide = !isAccumulator8Bit();
        int result = ((value << 1) | (p.carry ? 1 : 0)) & mask(wide);
        p.carry = (value & signBit(wide)) != 0;
        setNZ(result, wide);
        return result;
    }

    /** ROR: shift right through carry. */
    private int rotateRight(int value) {
        boolean wide = !isAccumulator8Bit();
        int result = (value >>> 1) | (p.carry ? signBit(wide) : 0);
        p.carry = (value & 1) != 0;
        setNZ(result, wide);
        return result;
    }

    private int increment(int value) {
        boolean wide = !isAccumulator8Bit();
        int result = (value + 1) & mask(wide);
        setNZ(result, wide);
        return result;
    }

    private int decrement(int value) {
        boolean wide = !isAccumulator8Bit();
        int result = (value - 1) & mask(wide);
        setNZ(result, wide);
        return result;
    }

    /**
     * TSB: Z = (A AND value) == 0, then set the bits of A in memory. Useful for flipping flags in
     * hardware registers while also learning their previous state.
     */
    private int testAndSetBits(int value) {
        boolean wide = !isAccumulator8Bit();
        p.zero = (value & r.a & mask(wide)) == 0;
        return (value | r.a) & mask(wide);
    }

    /** TRB: Z = (A AND value) == 0, then clear the bits of A in memory. */
    private int testAndResetBits(int value) {
        boolean wide = !isAccumulator8Bit();
        p.zero = (value & r.a & mask(wide)) == 0;
        return value & ~r.a & mask(wide);
    }

    private void inx() {
        idle();
        setX(r.x + 1);
    }

    private void iny() {
        idle();
        setY(r.y + 1);
    }

    private void dex() {
        idle();
        setX(r.x - 1);
    }

    private void dey() {
        idle();
        setY(r.y - 1);
    }

    // =============================================================================================
    // Instructions: branches and jumps
    // =============================================================================================

    /**
     * Conditional branch with a signed 8-bit offset from the next instruction. A taken branch
     * costs one more cycle. In emulation mode, crossing into a different page costs another,
     * as on the 6502.
     */
    private void branchIf(boolean condition) {
        int offset = (byte) fetchByte(); // sign-extend: $80-$FF are backward branches
        if (!condition) {
            return;
        }
        idle();
        int target = (r.pc + offset) & 0xFFFF;
        if (r.emulation && (target & 0xFF00) != (r.pc & 0xFF00)) {
            idle();
        }
        r.pc = target;
    }

    /** BRL: always-taken branch with a 16-bit offset, so it can reach anywhere in the bank. */
    private void branchLong() {
        int offset = fetchWord();
        idle();
        r.pc = (r.pc + offset) & 0xFFFF;
    }

    private void jumpAbsolute() {
        r.pc = fetchWord();
    }

    private void jumpLong() {
        int offset = fetchWord();
        r.pbr = fetchByte();
        r.pc = offset;
    }

    /** JMP (abs): the pointer is always in bank $00. */
    private void jumpIndirect() {
        int pointer = fetchWord();
        int low = read(pointer);
        int high = read((pointer + 1) & 0xFFFF);
        r.pc = (high << 8) | low;
    }

    /** JMP (abs,X): a jump table in the program bank. */
    private void jumpIndexedIndirect() {
        int pointer = fetchWord();
        idle();
        r.pc = readProgramBankWord(pointer + r.x);
    }

    /** JML [abs]: a 24-bit pointer in bank $00. */
    private void jumpIndirectLong() {
        int pointer = fetchWord();
        int low = read(pointer);
        int high = read((pointer + 1) & 0xFFFF);
        r.pbr = read((pointer + 2) & 0xFFFF);
        r.pc = (high << 8) | low;
    }

    /**
     * JSR abs: call a subroutine in the same bank. It pushes the address of its own last byte
     * (return address minus one). RTS adds the one back. That quirk comes from the 6502.
     */
    private void jsrAbsolute() {
        int target = fetchWord();
        idle();
        int returnAddress = (r.pc - 1) & 0xFFFF;
        push(returnAddress >> 8);
        push(returnAddress & 0xFF);
        r.pc = target;
    }

    /**
     * JSR (abs,X): call through a jump table. It pushes the return address between fetching the
     * pointer's two bytes; at that moment PC points at the instruction's last byte.
     */
    private void jsrIndexedIndirect() {
        int low = fetchByte();
        pushNative(r.pc >> 8);
        pushNative(r.pc & 0xFF);
        int high = fetchByte();
        idle();
        r.pc = readProgramBankWord(((high << 8) | low) + r.x);
        confineStackInEmulationMode();
    }

    /** JSL: long call. It pushes PBR as well, so RTL can return to the calling bank. */
    private void jsl() {
        int offset = fetchWord();
        pushNative(r.pbr);
        idle();
        int bank = fetchByte();
        int returnAddress = (r.pc - 1) & 0xFFFF;
        pushNative(returnAddress >> 8);
        pushNative(returnAddress & 0xFF);
        r.pbr = bank;
        r.pc = offset;
        confineStackInEmulationMode();
    }

    private void rts() {
        idle();
        idle();
        int low = pull();
        int high = pull();
        idle();
        r.pc = (((high << 8) | low) + 1) & 0xFFFF;
    }

    private void rtl() {
        idle();
        idle();
        int low = pullNative();
        int high = pullNative();
        r.pbr = pullNative();
        r.pc = (((high << 8) | low) + 1) & 0xFFFF;
        confineStackInEmulationMode();
    }

    /** RTI: return from interrupt, restoring P, then PC, then (native mode only) PBR. */
    private void rti() {
        idle();
        idle();
        setStatusRegister(pull());
        int low = pull();
        int high = pull();
        r.pc = (high << 8) | low;
        if (!r.emulation) {
            r.pbr = pull();
        }
    }

    // =============================================================================================
    // Interrupts and halting
    // =============================================================================================

    /**
     * BRK and COP: software interrupts. Each one skips a "signature" byte after the opcode,
     * which the handler can read to see why it was called.
     */
    private void softwareInterrupt(int nativeVector, int emulationVector) {
        fetchByte();
        enterInterrupt(nativeVector, emulationVector, true);
    }

    /** NMI and IRQ. The CPU spends two cycles abandoning the instruction it was about to fetch. */
    private void hardwareInterrupt(int nativeVector, int emulationVector) {
        idle();
        idle();
        enterInterrupt(nativeVector, emulationVector, false);
    }

    /**
     * Saves the CPU state on the stack and jumps through a vector.
     *
     * <p>Native mode pushes PBR too, and handlers return with RTI to any bank. Emulation mode
     * pushes the 6502's three bytes. On the 6502 the pushed copy of P was the only way to tell
     * BRK from IRQ (they share a vector), so bit 4, the "B flag", is set for software interrupts
     * and clear for hardware ones.
     */
    private void enterInterrupt(int nativeVector, int emulationVector, boolean software) {
        if (!r.emulation) {
            push(r.pbr);
        }
        push(r.pc >> 8);
        push(r.pc & 0xFF);
        int status = p.toByte();
        if (r.emulation) {
            status = software ? status | 0x30 : (status | 0x20) & ~0x10;
        }
        push(status);
        p.irqDisable = true;
        p.decimal = false;
        r.pbr = 0;
        int vector = r.emulation ? emulationVector : nativeVector;
        r.pc = read(vector) | (read(vector + 1) << 8);
    }

    /**
     * WAI: sleep until an interrupt. The datasheet lists two internal cycles; the third idle
     * here is the first cycle spent asleep, which the test suite counts as part of the
     * instruction. Later sleeping cycles happen one per {@link #step()}.
     */
    private void wai() {
        idle();
        idle();
        idle();
        waitingForInterrupt = true;
    }

    /** STP: halt until reset. Timed like WAI. */
    private void stp() {
        idle();
        idle();
        idle();
        stopped = true;
    }

    /**
     * WDM ("William D. Mensch", the 65816's designer) was reserved for a future instruction set
     * extension that never came. It's a two-byte NOP. The CPU steps over the second byte without
     * signaling a valid program read, so the bus sees an internal operation.
     */
    private void wdm() {
        idle();
        r.pc = (r.pc + 1) & 0xFFFF;
    }

    // =============================================================================================
    // Instructions: stack
    // =============================================================================================

    private void pha() {
        idle();
        pushWidth(r.a, !isAccumulator8Bit());
    }

    private void phx() {
        idle();
        pushWidth(r.x, !isIndex8Bit());
    }

    private void phy() {
        idle();
        pushWidth(r.y, !isIndex8Bit());
    }

    private void php() {
        idle();
        push(p.toByte());
    }

    private void phb() {
        idle();
        push(r.dbr);
    }

    private void phk() {
        idle();
        push(r.pbr);
    }

    private void phd() {
        idle();
        pushNative(r.d >> 8);
        pushNative(r.d & 0xFF);
        confineStackInEmulationMode();
    }

    private void pla() {
        idle();
        idle();
        setAccumulator(pullWidth(!isAccumulator8Bit()));
    }

    private void plx() {
        idle();
        idle();
        setX(pullWidth(!isIndex8Bit()));
    }

    private void ply() {
        idle();
        idle();
        setY(pullWidth(!isIndex8Bit()));
    }

    private void plp() {
        idle();
        idle();
        setStatusRegister(pull());
    }

    private void plb() {
        idle();
        idle();
        r.dbr = pullNative();
        setNZ(r.dbr, false);
        confineStackInEmulationMode();
    }

    private void pld() {
        idle();
        idle();
        int low = pullNative();
        int high = pullNative();
        r.d = (high << 8) | low;
        setNZ(r.d, true);
        confineStackInEmulationMode();
    }

    /** PEA: push a 16-bit constant ("push effective absolute address"). */
    private void pea() {
        int value = fetchWord();
        pushNative(value >> 8);
        pushNative(value & 0xFF);
        confineStackInEmulationMode();
    }

    /**
     * PEI: push the 16-bit value stored in direct page ("push effective indirect address").
     * PEI is a 65816 addition, so like the [dp] modes it never uses 6502 page wrapping.
     */
    private void pei() {
        int offset = fetchByte();
        directPagePenalty();
        int base = r.d + offset;
        int value = read(base & 0xFFFF) | (read((base + 1) & 0xFFFF) << 8);
        pushNative(value >> 8);
        pushNative(value & 0xFF);
        confineStackInEmulationMode();
    }

    /**
     * PER: push PC plus a 16-bit offset. Code uses it to find its own location, which is how
     * relocatable code takes the address of nearby data.
     */
    private void per() {
        int offset = fetchWord();
        idle();
        int value = (r.pc + offset) & 0xFFFF;
        pushNative(value >> 8);
        pushNative(value & 0xFF);
        confineStackInEmulationMode();
    }

    // =============================================================================================
    // Instructions: transfers and mode changes
    // =============================================================================================

    /**
     * TAX and TAY are sized by the <b>destination</b>. With 16-bit index registers they copy
     * all 16 bits of A, even when the accumulator itself is 8-bit.
     */
    private void tax() {
        idle();
        setX(r.a);
    }

    private void tay() {
        idle();
        setY(r.a);
    }

    /** TXA and TYA are sized by the accumulator. */
    private void txa() {
        idle();
        setAccumulator(r.x);
    }

    private void tya() {
        idle();
        setAccumulator(r.y);
    }

    private void txy() {
        idle();
        setY(r.x);
    }

    private void tyx() {
        idle();
        setX(r.y);
    }

    private void tsx() {
        idle();
        setX(r.sp);
    }

    /** TXS sets no flags. In emulation mode only the low byte of S changes. */
    private void txs() {
        idle();
        r.sp = r.emulation ? 0x0100 | (r.x & 0xFF) : r.x;
    }

    /** TCD: copy all 16 bits of the accumulator ("C") into D. */
    private void tcd() {
        idle();
        r.d = r.a;
        setNZ(r.d, true);
    }

    private void tdc() {
        idle();
        r.a = r.d;
        setNZ(r.a, true);
    }

    /** TCS sets no flags. In emulation mode only the low byte of S changes. */
    private void tcs() {
        idle();
        r.sp = r.emulation ? 0x0100 | (r.a & 0xFF) : r.a;
    }

    private void tsc() {
        idle();
        r.a = r.sp;
        setNZ(r.a, true);
    }

    /** XBA: swap the accumulator's two bytes. Flags follow the new low byte. */
    private void xba() {
        idle();
        idle();
        r.a = ((r.a >> 8) | (r.a << 8)) & 0xFFFF;
        setNZ(r.a, false);
    }

    /**
     * XCE: exchange carry with the emulation flag. This is the only way to switch modes:
     * {@code CLC; XCE} enters native mode and {@code SEC; XCE} returns to emulation mode.
     */
    private void xce() {
        idle();
        boolean wasEmulation = r.emulation;
        r.emulation = p.carry;
        p.carry = wasEmulation;
        if (r.emulation) {
            // Entering emulation mode re-imposes the 6502's limits.
            p.memory8 = true;
            p.index8 = true;
            r.x &= 0xFF;
            r.y &= 0xFF;
            r.sp = 0x0100 | (r.sp & 0xFF);
        }
    }

    /** REP: clear the P bits that are set in the operand ("reset processor status bits"). */
    private void rep() {
        int bits = fetchByte();
        idle();
        setStatusRegister(p.toByte() & ~bits);
    }

    /** SEP: set the P bits that are set in the operand. */
    private void sep() {
        int bits = fetchByte();
        idle();
        setStatusRegister(p.toByte() | bits);
    }

    /**
     * MVN and MVP copy one byte per execution. X is the source address, Y the destination, and
     * A the number of bytes left minus one. While bytes remain, the instruction moves PC back
     * onto itself, so it "executes" once per byte and interrupts can still be taken between
     * bytes. MVN walks upward (for overlapping moves to a lower address); MVP walks downward.
     * The operand order in the machine code is destination bank first, then source bank.
     */
    private void blockMove(int direction) {
        int destinationBank = fetchByte();
        int sourceBank = fetchByte();
        r.dbr = destinationBank;
        int value = read((sourceBank << 16) | r.x);
        write((destinationBank << 16) | r.y, value);
        idle();
        idle();
        int indexMask = isIndex8Bit() ? 0xFF : 0xFFFF;
        r.x = (r.x + direction) & indexMask;
        r.y = (r.y + direction) & indexMask;
        r.a = (r.a - 1) & 0xFFFF;
        if (r.a != 0xFFFF) {
            r.pc = (r.pc - 3) & 0xFFFF;
        }
    }

    // =============================================================================================
    // Register helpers
    // =============================================================================================

    /** True when A and memory operands are 8-bit: always in emulation mode, else per the M flag. */
    public boolean isAccumulator8Bit() {
        return r.emulation || p.memory8;
    }

    /** True when X and Y are 8-bit: always in emulation mode, else per the X flag. */
    public boolean isIndex8Bit() {
        return r.emulation || p.index8;
    }

    /** Loads the accumulator at the current width, keeping the hidden high byte when 8-bit. */
    private void setAccumulator(int value) {
        if (isAccumulator8Bit()) {
            r.a = (r.a & 0xFF00) | (value & 0xFF);
            setNZ(value, false);
        } else {
            r.a = value & 0xFFFF;
            setNZ(value, true);
        }
    }

    private void setX(int value) {
        boolean wide = !isIndex8Bit();
        r.x = value & mask(wide);
        setNZ(r.x, wide);
    }

    private void setY(int value) {
        boolean wide = !isIndex8Bit();
        r.y = value & mask(wide);
        setNZ(r.y, wide);
    }

    /**
     * Writes P, applying its side effects. Emulation mode keeps the registers 8-bit, and when X
     * and Y become 8-bit their high bytes are lost for good.
     */
    private void setStatusRegister(int value) {
        p.setFromByte(value);
        if (r.emulation) {
            p.memory8 = true;
            p.index8 = true;
        }
        if (isIndex8Bit()) {
            r.x &= 0xFF;
            r.y &= 0xFF;
        }
    }

    /** Sets N and Z from a result of the given width. */
    private void setNZ(int value, boolean wide) {
        p.zero = (value & mask(wide)) == 0;
        p.negative = (value & signBit(wide)) != 0;
    }

    private static int mask(boolean wide) {
        return wide ? 0xFFFF : 0xFF;
    }

    private static int signBit(boolean wide) {
        return wide ? 0x8000 : 0x80;
    }

    // =============================================================================================
    // Bus helpers
    // =============================================================================================

    private int read(int address) {
        return bus.read(address & 0xFFFFFF) & 0xFF;
    }

    private void write(int address, int value) {
        bus.write(address & 0xFFFFFF, value & 0xFF);
    }

    private void idle() {
        bus.idle();
    }

    /** Reads the next program byte. PC wraps within the program bank; it never carries into PBR. */
    private int fetchByte() {
        int value = read((r.pbr << 16) | r.pc);
        r.pc = (r.pc + 1) & 0xFFFF;
        return value;
    }

    private int fetchWord() {
        int low = fetchByte();
        int high = fetchByte();
        return (high << 8) | low;
    }

    /** Reads a 16-bit value from the program bank, wrapping within it (used by jump tables). */
    private int readProgramBankWord(int offset) {
        int low = read((r.pbr << 16) | (offset & 0xFFFF));
        int high = read((r.pbr << 16) | ((offset + 1) & 0xFFFF));
        return (high << 8) | low;
    }

    /** Reads a 1- or 2-byte operand. */
    private int readData(EffectiveAddress operand, boolean wide) {
        int low = read(operand.address());
        if (!wide) {
            return low;
        }
        return low | (read(operand.plus(1)) << 8);
    }

    private void writeData(EffectiveAddress operand, int value, boolean wide) {
        write(operand.address(), value);
        if (wide) {
            write(operand.plus(1), value >> 8);
        }
    }

    private int readM(EffectiveAddress operand) {
        return readData(operand, !isAccumulator8Bit());
    }

    private int readX(EffectiveAddress operand) {
        return readData(operand, !isIndex8Bit());
    }

    private void writeM(EffectiveAddress operand, int value) {
        writeData(operand, value, !isAccumulator8Bit());
    }

    private void writeX(EffectiveAddress operand, int value) {
        writeData(operand, value, !isIndex8Bit());
    }

    // =============================================================================================
    // Stack helpers
    //
    // The stack lives in bank $00 and grows downward. S points at the next free byte, so a
    // push writes first and then decrements.
    //
    // In emulation mode the 6502 instructions keep S inside page $01 by wrapping only its low
    // byte. The instructions the 65816 added (PEA, PEI, PER, PHD, PLD, PLB, JSL, RTL, JSR (abs,X))
    // are wired differently: they use the full 16-bit S during the instruction and only
    // put S back in page $01 at the end. So a push from $0100 really writes to $00FF. The
    // "Native" helpers below model those instructions.
    // =============================================================================================

    private void push(int value) {
        write(r.sp, value);
        r.sp = r.emulation ? 0x0100 | ((r.sp - 1) & 0xFF) : (r.sp - 1) & 0xFFFF;
    }

    private int pull() {
        r.sp = r.emulation ? 0x0100 | ((r.sp + 1) & 0xFF) : (r.sp + 1) & 0xFFFF;
        return read(r.sp);
    }

    private void pushNative(int value) {
        write(r.sp, value);
        r.sp = (r.sp - 1) & 0xFFFF;
    }

    private int pullNative() {
        r.sp = (r.sp + 1) & 0xFFFF;
        return read(r.sp);
    }

    private void confineStackInEmulationMode() {
        if (r.emulation) {
            r.sp = 0x0100 | (r.sp & 0xFF);
        }
    }

    /** Pushes 1 or 2 bytes, high byte first, so the value sits in memory in little-endian order. */
    private void pushWidth(int value, boolean wide) {
        if (wide) {
            push(value >> 8);
        }
        push(value & 0xFF);
    }

    private int pullWidth(boolean wide) {
        int low = pull();
        if (!wide) {
            return low;
        }
        return low | (pull() << 8);
    }
}
