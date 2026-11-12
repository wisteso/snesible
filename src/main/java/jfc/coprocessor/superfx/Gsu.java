package jfc.coprocessor.superfx;

/**
 * The GSU ("Graphics Support Unit"), the RISC processor inside the SuperFX chip.
 *
 * <p>It's a 16-bit processor built to draw: Star Fox's polygons and Yoshi's Island's stretched
 * and rotated sprites are computed and plotted by the GSU into its own RAM, and the console
 * then copies the finished picture into VRAM. GSU-1 ("Mario Chip", Star Fox) runs at 10.7 MHz;
 * GSU-2 (Yoshi's Island) can run at the full 21.4 MHz master clock.
 *
 * <h2>Registers</h2>
 * <pre>
 *   R0       default source and destination of most instructions
 *   R1, R2   PLOT's X and Y           R3-R10  general purpose (R4: LMULT low word, R6: FMULT)
 *   R11      LINK return address      R12     LOOP counter     R13  LOOP branch target
 *   R14      ROM data pointer: writing it starts a fetch of ROM byte ROMBR:R14 (for GETB)
 *   R15      program counter
 *   SFR      status: Z CY S OV flags, G (running), ALT1/ALT2/B prefix state, IRQ
 * </pre>
 *
 * <h2>Prefixes instead of operand fields</h2>
 * Almost every instruction is one byte. Instead of naming registers in the opcode, the GSU uses
 * <i>prefix</i> instructions that set up the next one:
 * <ul>
 *   <li>{@code TO Rn} picks the destination, {@code FROM Rn} the source, {@code WITH Rn} both.
 *       Without them, both are R0.</li>
 *   <li>{@code ALT1}, {@code ALT2}, {@code ALT3} select an alternative meaning of the next
 *       opcode: {@code ADD R3} becomes {@code ADC R3} after ALT1, {@code ADD #3} after ALT2.</li>
 *   <li>{@code TO}/{@code FROM} right after {@code WITH} (the B flag) mean {@code MOVE}/{@code MOVES}.</li>
 * </ul>
 * Every ordinary instruction clears the prefix state when it finishes ({@link #resetPrefixes}).
 *
 * <h2>The pipeline and branch delay slot</h2>
 * The GSU fetches the next byte while it executes the current one. So when an instruction
 * changes R15 (a branch, a jump, LOOP, or any write to R15), the byte after it has already
 * been fetched and <i>still executes</i>. Games put a useful instruction (or a NOP) in that
 * "delay slot". The model here: {@link #pipeline} holds the opcode that executes next, and R15
 * points just past it. {@link #step} prefetches the byte at R15, executes, and advances R15
 * unless the instruction wrote R15 itself.
 *
 * <h2>Memory</h2>
 * The GSU reads code through a 512-byte instruction cache ({@link #fetchCode}), ROM data through
 * a one-byte buffer filled when R14 changes, and RAM directly, all via {@link GsuBus}. Words in
 * RAM are little-endian, with the high byte at {@code address ^ 1}: a word access at an odd
 * address takes its high byte from the byte <i>below</i>.
 *
 * <h2>Timing</h2>
 * Each step returns the master cycles it took: a cached code byte costs one GSU clock, a ROM or
 * RAM access about 5-6 master cycles, plus extra time for multiplies and pixel-cache flushes.
 * One GSU clock is 2 master cycles at 10.7 MHz (CLSR = 0) and 1 at 21.4 MHz (CLSR = 1).
 * <i>Simplification:</i> the ROM and RAM buffers' waits are folded into fixed costs; the exact
 * cycle counts don't affect what games compute, only how long it takes.
 */
final class Gsu {

    static final int CACHE_SIZE = 512;
    private static final int CACHE_LINE = 16;

    private final GsuBus bus;
    final PlotUnit plot;

    /** R0-R15, each 16 bits. */
    final int[] r = new int[16];

    // ---- SFR: status flags
    boolean zero;
    boolean carry;
    boolean sign;
    boolean overflow;
    /** G: the GSU is running. Set by the console (writing R15's high byte), cleared by STOP. */
    boolean running;
    boolean alt1;
    boolean alt2;
    /** B: set by WITH, which turns a following TO/FROM into MOVE/MOVES. */
    boolean withPrefix;
    /** IRQ: STOP raised an interrupt; cleared when the console reads SFR's high byte. */
    boolean irqPending;

    /** Register numbers selected by FROM/WITH (source) and TO/WITH (destination). */
    int sourceRegister;
    int destinationRegister;

    // ---- Other registers the console can read or write
    /** PBR: bank code is fetched from. */
    int programBank;
    /** ROMBR: bank of ROM data reads (GETB, GETC). */
    int romBank;
    /** RAMBR: RAM bank, 0 or 1 (CPU banks $70/$71). */
    int ramBank;
    /** CBR: the code address the cache currently mirrors (16-byte aligned). */
    int cacheBase;
    /** CFGR: bit 7 masks the STOP interrupt, bit 5 selects the faster multiplier. */
    int config;
    /** CLSR bit 0: 1 = 21.4 MHz, 0 = 10.7 MHz. */
    boolean fastClock;
    /** SCMR RON/RAN: the GSU has been given the ROM / RAM bus (the console loses it). */
    boolean ownsRom;
    boolean ownsRam;

    /** The opcode byte that executes next (already fetched: see the class comment). */
    int pipeline = NOP;
    /** Set when an instruction writes R15, so {@link #step} doesn't also advance it. */
    private boolean r15Written;
    /** The ROM byte at ROMBR:R14, fetched when R14 was last written. */
    private int romBuffer;
    /** The last RAM address used by a load or store, for SBK. */
    private int lastRamAddress;

    final byte[] cache = new byte[CACHE_SIZE];
    final boolean[] cacheLineValid = new boolean[CACHE_SIZE / CACHE_LINE];

    /** Master cycles spent by the instruction in progress. */
    private int cycles;
    /** Called when STOP raises the interrupt. */
    private final Runnable onInterrupt;

    private static final int NOP = 0x01;

    Gsu(GsuBus bus, Runnable onInterrupt) {
        this.bus = bus;
        this.onInterrupt = onInterrupt;
        this.plot = new PlotUnit(bus);
    }

    /** Power-on state: stopped, registers cleared. */
    void reset() {
        java.util.Arrays.fill(r, 0);
        zero = carry = sign = overflow = running = alt1 = alt2 = withPrefix = irqPending = false;
        sourceRegister = destinationRegister = 0;
        programBank = romBank = ramBank = cacheBase = config = 0;
        fastClock = ownsRom = ownsRam = false;
        pipeline = NOP;
        romBuffer = 0;
        lastRamAddress = 0;
        flushCache();
        plot.reset();
    }

    // =============================================================================================
    // Execution
    // =============================================================================================

    /**
     * Executes one instruction (a prefix counts as one).
     *
     * @return master cycles it took
     */
    int step() {
        cycles = 0;
        int opcode = pipeline;
        pipeline = fetchCode(r[15]); // the delay-slot prefetch
        r15Written = false;
        execute(opcode);
        if (!r15Written) {
            r[15] = (r[15] + 1) & 0xFFFF;
        }
        return cycles;
    }

    /** Takes the next byte of the current instruction (an operand) from the pipeline. */
    private int operand() {
        int value = pipeline;
        r[15] = (r[15] + 1) & 0xFFFF;
        pipeline = fetchCode(r[15]);
        return value;
    }

    private void execute(int opcode) {
        int n = opcode & 0x0F;
        switch (opcode >> 4) {
            case 0x0 -> executeControl(n);
            case 0x1 -> toOrMove(n);
            case 0x2 -> with(n);
            case 0x3 -> {
                switch (n) {
                    case 0x0C -> loop();
                    case 0x0D -> { withPrefix = false; alt1 = true; }               // ALT1
                    case 0x0E -> { withPrefix = false; alt2 = true; }               // ALT2
                    case 0x0F -> { withPrefix = false; alt1 = true; alt2 = true; }  // ALT3
                    default -> store(n);
                }
            }
            case 0x4 -> {
                switch (n) {
                    case 0x0C -> plotOrReadPixel();
                    case 0x0D -> swap();
                    case 0x0E -> colorOrCmode();
                    case 0x0F -> not();
                    default -> load(n);
                }
            }
            case 0x5 -> add(n);
            case 0x6 -> subtract(n);
            case 0x7 -> {
                if (n == 0) {
                    merge();
                } else {
                    andOrBitClear(n);
                }
            }
            case 0x8 -> multiply(n);
            case 0x9 -> executeRow9(n);
            case 0xA -> ibtLmsSms(n);
            case 0xB -> fromOrMoves(n);
            case 0xC -> {
                if (n == 0) {
                    hib();
                } else {
                    orOrXor(n);
                }
            }
            case 0xD -> {
                if (n == 0x0F) {
                    getcRambRomb();
                } else {
                    setRegister(n, r[n] + 1); // INC Rn
                    setSignZero(r[n]);
                    resetPrefixes();
                }
            }
            case 0xE -> {
                if (n == 0x0F) {
                    getb();
                } else {
                    setRegister(n, r[n] - 1); // DEC Rn
                    setSignZero(r[n]);
                    resetPrefixes();
                }
            }
            default -> iwtLmSm(n); // $Fx
        }
    }

    // ---- $00-$0F: STOP, NOP, CACHE, shifts, branches

    private void executeControl(int n) {
        switch (n) {
            case 0x0 -> stop();
            case 0x1 -> resetPrefixes(); // NOP
            case 0x2 -> {                // CACHE: point the cache at the current code
                if (cacheBase != (r[15] & 0xFFF0)) {
                    cacheBase = r[15] & 0xFFF0;
                    flushCache();
                }
                resetPrefixes();
            }
            case 0x3 -> {                // LSR: logical shift right
                int s = sourceValue();
                carry = (s & 1) != 0;
                writeResult(s >> 1);
            }
            case 0x4 -> {                // ROL: rotate left through carry
                int s = sourceValue();
                int result = (s << 1) | (carry ? 1 : 0);
                carry = (s & 0x8000) != 0;
                writeResult(result);
            }
            case 0x5 -> branch(true);                     // BRA
            case 0x6 -> branch(sign == overflow);         // BGE
            case 0x7 -> branch(sign != overflow);         // BLT
            case 0x8 -> branch(!zero);                    // BNE
            case 0x9 -> branch(zero);                     // BEQ
            case 0xA -> branch(!sign);                    // BPL
            case 0xB -> branch(sign);                     // BMI
            case 0xC -> branch(!carry);                   // BCC
            case 0xD -> branch(carry);                    // BCS
            case 0xE -> branch(!overflow);                // BVC
            default -> branch(overflow);                  // BVS
        }
    }

    /**
     * STOP: the GSU halts and (unless CFGR masks it) interrupts the console CPU, which is how a
     * game learns that a frame has been drawn.
     */
    private void stop() {
        if ((config & 0x80) == 0) {
            irqPending = true;
            onInterrupt.run();
        }
        running = false;
        // The byte after STOP is already in the pipeline. Replace it with a NOP, so the next
        // start begins cleanly at the new R15.
        pipeline = NOP;
        resetPrefixes();
    }

    /**
     * Branches are relative to the address of the delay slot (the byte after the offset), and
     * the delay-slot instruction runs whether or not the branch is taken. They don't clear the
     * prefix state.
     */
    private void branch(boolean taken) {
        int displacement = (byte) operand();
        if (taken) {
            setRegister(15, r[15] + displacement);
        }
    }

    // ---- $10-$2F: TO / MOVE, WITH;  $B0-$BF: FROM / MOVES

    private void toOrMove(int n) {
        if (withPrefix) {
            setRegister(n, sourceValue()); // MOVE Rn, Rsource (no flags)
            resetPrefixes();
        } else {
            destinationRegister = n;       // TO Rn: a prefix, so the prefix state stays
        }
    }

    private void with(int n) {
        sourceRegister = n;
        destinationRegister = n;
        withPrefix = true;
    }

    private void fromOrMoves(int n) {
        if (withPrefix) {
            // MOVES Rdest, Rn: a move that sets flags. OV gets bit 7, which lets a game test the
            // sign of the low byte.
            int value = r[n];
            setRegister(destinationRegister, value);
            overflow = (value & 0x80) != 0;
            setSignZero(value);
            resetPrefixes();
        } else {
            sourceRegister = n;            // FROM Rn
        }
    }

    // ---- $30-$4B: STW/STB, LOOP, LDW/LDB

    /** STW (Rn) stores the source word at RAM address Rn; ALT1 makes it STB (low byte only). */
    private void store(int n) {
        lastRamAddress = r[n];
        writeRam(lastRamAddress, sourceValue());
        if (!alt1) {
            writeRam(lastRamAddress ^ 1, sourceValue() >> 8);
        }
        resetPrefixes();
    }

    /** LDW (Rn) loads the word at RAM address Rn; ALT1 makes it LDB (zero-extended byte). */
    private void load(int n) {
        lastRamAddress = r[n];
        int value = readRam(lastRamAddress);
        if (!alt1) {
            value |= readRam(lastRamAddress ^ 1) << 8;
        }
        setRegister(destinationRegister, value);
        resetPrefixes();
    }

    /** LOOP: decrement R12 and, unless it reached zero, jump to the address in R13. */
    private void loop() {
        r[12] = (r[12] - 1) & 0xFFFF;
        setSignZero(r[12]);
        if (!zero) {
            setRegister(15, r[13]);
        }
        resetPrefixes();
    }

    // ---- $4C-$4F: PLOT/RPIX, SWAP, COLOR/CMODE, NOT

    private void plotOrReadPixel() {
        if (!alt1) {
            // PLOT draws at (R1, R2) and steps R1 right, ready for the next pixel of a span.
            cycles += plot.plot(r[1] & 0xFF, r[2] & 0xFF, memoryCycles());
            setRegister(1, r[1] + 1);
        } else {
            // RPIX reads a pixel back (the real chip first flushes its pixel cache).
            int color = plot.readPixel(r[1] & 0xFF, r[2] & 0xFF);
            setRegister(destinationRegister, color);
            setSignZero(color);
            cycles += plot.bitsPerPixel() * memoryCycles();
        }
        resetPrefixes();
    }

    private void swap() {
        int s = sourceValue();
        writeResult((s >> 8) | (s << 8));
    }

    /** COLOR sets the plot color from the source register; ALT1 makes it CMODE (set POR). */
    private void colorOrCmode() {
        if (!alt1) {
            plot.color = plot.colorFrom(sourceValue());
        } else {
            plot.plotOptions = sourceValue() & 0x1F;
        }
        resetPrefixes();
    }

    private void not() {
        writeResult(~sourceValue());
    }

    // ---- $50-$7F: arithmetic and logic

    /** ADD Rn / ADC Rn (ALT1) / ADD #n (ALT2) / ADC #n (ALT3). */
    private void add(int n) {
        int s = sourceValue();
        int operand = alt2 ? n : r[n];
        int result = s + operand + (alt1 && carry ? 1 : 0);
        // Signed overflow: both inputs had the same sign, and the result's sign differs.
        overflow = (~(s ^ operand) & (operand ^ result) & 0x8000) != 0;
        carry = result > 0xFFFF;
        writeResult(result);
    }

    /** SUB Rn / SBC Rn (ALT1) / SUB #n (ALT2) / CMP Rn (ALT3: flags only). */
    private void subtract(int n) {
        boolean immediate = alt2 && !alt1;
        boolean withBorrow = alt1 && !alt2;
        boolean compare = alt1 && alt2;
        int s = sourceValue();
        int operand = immediate ? n : r[n];
        int result = s - operand - (withBorrow && !carry ? 1 : 0);
        overflow = ((s ^ operand) & (s ^ result) & 0x8000) != 0;
        carry = result >= 0; // carry means "no borrow", as on the 6502
        setSignZero(result);
        if (!compare) {
            setRegister(destinationRegister, result);
        }
        resetPrefixes();
    }

    /**
     * MERGE: R7's high byte and R8's high byte combined into one word. It's used to pack two
     * 8.8 fixed-point texture coordinates into a pixel address. The flags test the top bits of
     * both bytes, which tells the game whether either coordinate is out of range.
     */
    private void merge() {
        int value = (r[7] & 0xFF00) | (r[8] >> 8);
        setRegister(destinationRegister, value);
        overflow = (value & 0xC0C0) != 0;
        sign = (value & 0x8080) != 0;
        carry = (value & 0xE0E0) != 0;
        zero = (value & 0xF0F0) != 0; // yes, "set if any bit is set": it's a range test
        resetPrefixes();
    }

    /** AND Rn / BIC Rn (ALT1: AND NOT) / AND #n (ALT2) / BIC #n (ALT3). */
    private void andOrBitClear(int n) {
        int operand = alt2 ? n : r[n];
        writeResult(sourceValue() & (alt1 ? ~operand : operand));
    }

    /** OR Rn / XOR Rn (ALT1) / OR #n (ALT2) / XOR #n (ALT3). */
    private void orOrXor(int n) {
        int operand = alt2 ? n : r[n];
        writeResult(alt1 ? sourceValue() ^ operand : sourceValue() | operand);
    }

    /** MULT: signed 8x8 bit multiply; UMULT (ALT1): unsigned. ALT2/ALT3 take #n instead of Rn. */
    private void multiply(int n) {
        int s = sourceValue();
        int operand = alt2 ? n : r[n];
        int result = alt1 ? (s & 0xFF) * (operand & 0xFF) : (byte) s * (byte) operand;
        writeResult(result);
        if ((config & 0x20) == 0) {
            cycles += gsuClock(); // the slow multiplier takes an extra clock
        }
    }

    // ---- $90-$9F: SBK, LINK, SEX, ASR/DIV2, ROR, JMP/LJMP, LOB, FMULT/LMULT

    private void executeRow9(int n) {
        switch (n) {
            case 0x0 -> {                 // SBK: store the source word back to the last RAM address
                writeRam(lastRamAddress, sourceValue());
                writeRam(lastRamAddress ^ 1, sourceValue() >> 8);
                resetPrefixes();
            }
            case 0x1, 0x2, 0x3, 0x4 -> {  // LINK #n: return address for a following jump
                // R15 already points past LINK, so R11 = address of LINK + 1 + n.
                r[11] = (r[15] + n) & 0xFFFF;
                resetPrefixes();
            }
            case 0x5 -> writeResult((byte) sourceValue()); // SEX: sign-extend the low byte
            case 0x6 -> {                 // ASR / DIV2 (ALT1)
                int s = sourceValue();
                carry = (s & 1) != 0;
                int result = (short) s >> 1;
                // DIV2 rounds toward zero for -1 (ASR would leave -1 at -1).
                if (alt1 && s == 0xFFFF) {
                    result = 0;
                }
                writeResult(result);
            }
            case 0x7 -> {                 // ROR: rotate right through carry
                int s = sourceValue();
                int result = (carry ? 0x8000 : 0) | (s >> 1);
                carry = (s & 1) != 0;
                writeResult(result);
            }
            case 0x8, 0x9, 0xA, 0xB, 0xC, 0xD -> jump(n); // JMP/LJMP R8-R13
            case 0xE -> {                 // LOB: low byte; the sign flag reflects bit 7
                int result = sourceValue() & 0xFF;
                setRegister(destinationRegister, result);
                sign = (result & 0x80) != 0;
                zero = result == 0;
                resetPrefixes();
            }
            default -> fractionalMultiply(); // $9F
        }
    }

    /** JMP Rn sets R15; LJMP Rn (ALT1) also sets the program bank: PBR = Rn, R15 = source. */
    private void jump(int n) {
        if (!alt1) {
            setRegister(15, r[n]);
        } else {
            programBank = r[n] & 0x7F;
            setRegister(15, sourceValue());
            cacheBase = r[15] & 0xFFF0;
            flushCache();
        }
        resetPrefixes();
    }

    /**
     * FMULT: signed 16x16 multiply keeping the top word, i.e. multiplication of 1.15 fixed-point
     * numbers (by R6). LMULT (ALT1) also stores the low word in R4. Carry gets bit 15 of the full
     * product, for rounding.
     */
    private void fractionalMultiply() {
        int product = (short) sourceValue() * (short) r[6];
        if (alt1) {
            setRegister(4, product);
        }
        int high = (product >> 16) & 0xFFFF;
        setRegister(destinationRegister, high);
        sign = (high & 0x8000) != 0;
        zero = high == 0;
        carry = (product & 0x8000) != 0;
        resetPrefixes();
        cycles += ((config & 0x20) != 0 ? 3 : 7) * gsuClock();
    }

    // ---- $A0-$AF: IBT / LMS / SMS;  $F0-$FF: IWT / LM / SM

    /** IBT Rn,#byte (sign-extended); LMS (ALT1) / SMS (ALT2) load/store at RAM address byte*2. */
    private void ibtLmsSms(int n) {
        if (alt1) {
            lastRamAddress = operand() << 1;
            setRegister(n, readRam(lastRamAddress) | readRam(lastRamAddress ^ 1) << 8);
        } else if (alt2) {
            lastRamAddress = operand() << 1;
            writeRam(lastRamAddress, r[n]);
            writeRam(lastRamAddress ^ 1, r[n] >> 8);
        } else {
            setRegister(n, (byte) operand());
        }
        resetPrefixes();
    }

    /** IWT Rn,#word; LM (ALT1) / SM (ALT2) load/store at a 16-bit RAM address. */
    private void iwtLmSm(int n) {
        int low = operand();
        int word = low | operand() << 8;
        if (alt1) {
            lastRamAddress = word;
            setRegister(n, readRam(word) | readRam(word ^ 1) << 8);
        } else if (alt2) {
            lastRamAddress = word;
            writeRam(word, r[n]);
            writeRam(word ^ 1, r[n] >> 8);
        } else {
            setRegister(n, word);
        }
        resetPrefixes();
    }

    // ---- $C0: HIB;  $DF: GETC/RAMB/ROMB;  $EF: GETB family

    private void hib() {
        int result = sourceValue() >> 8;
        setRegister(destinationRegister, result);
        sign = (result & 0x80) != 0;
        zero = result == 0;
        resetPrefixes();
    }

    /** GETC (none or ALT1): color from the ROM buffer; RAMB (ALT2) / ROMB (ALT3): set a bank. */
    private void getcRambRomb() {
        if (!alt2) {
            plot.color = plot.colorFrom(romBuffer);
        } else if (!alt1) {
            ramBank = sourceValue() & 0x01;
        } else {
            romBank = sourceValue() & 0x7F;
        }
        resetPrefixes();
    }

    /** GETB: the ROM buffer byte; GETBH (ALT1) into the high byte; GETBL (ALT2) low; GETBS signed. */
    private void getb() {
        int s = sourceValue();
        int result;
        if (alt1 && alt2) {
            result = (byte) romBuffer;                // GETBS
        } else if (alt1) {
            result = (romBuffer << 8) | (s & 0xFF);   // GETBH
        } else if (alt2) {
            result = (s & 0xFF00) | romBuffer;        // GETBL
        } else {
            result = romBuffer;                       // GETB
        }
        setRegister(destinationRegister, result);
        resetPrefixes();
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private int sourceValue() {
        return r[sourceRegister];
    }

    /**
     * Writes a register. Writing R15 is a jump; writing R14 makes the ROM buffer fetch the byte
     * it now points at.
     */
    void setRegister(int n, int value) {
        r[n] = value & 0xFFFF;
        if (n == 15) {
            r15Written = true;
        } else if (n == 14) {
            refreshRomBuffer();
        }
    }

    /** The common ending: store to the destination, set S and Z from it, clear the prefixes. */
    private void writeResult(int value) {
        setRegister(destinationRegister, value);
        setSignZero(value);
        resetPrefixes();
    }

    private void setSignZero(int value) {
        sign = (value & 0x8000) != 0;
        zero = (value & 0xFFFF) == 0;
    }

    private void resetPrefixes() {
        withPrefix = false;
        alt1 = false;
        alt2 = false;
        sourceRegister = 0;
        destinationRegister = 0;
    }

    /** Fetches the ROM byte at ROMBR:R14 into the buffer GETB and GETC read. */
    void refreshRomBuffer() {
        romBuffer = bus.read(romBank << 16 | r[14]);
        cycles += memoryCycles();
    }

    private int readRam(int address) {
        cycles += memoryCycles();
        return bus.read(0x700000 | ramBank << 16 | (address & 0xFFFF));
    }

    private void writeRam(int address, int value) {
        cycles += memoryCycles();
        bus.write(0x700000 | ramBank << 16 | (address & 0xFFFF), value & 0xFF);
    }

    /**
     * Reads a code byte at PBR:address. Addresses within 512 bytes above CBR come from the
     * instruction cache; the first time a 16-byte line is needed it's loaded from ROM or RAM,
     * after which it costs a single clock. Loops that fit in the cache therefore run several
     * times faster than code fetched from ROM, which is why games issue CACHE before them.
     */
    int fetchCode(int address) {
        int offset = (address - cacheBase) & 0xFFFF;
        if (offset >= CACHE_SIZE) {
            cycles += memoryCycles();
            return bus.read(programBank << 16 | address);
        }
        // The cache is indexed by the low 9 address bits, so cache byte i always holds the
        // code byte whose address ends in i. That's also how the console's view at $3100-$32FF
        // lines up with the code (see SuperFx.readCache).
        int index = address & (CACHE_SIZE - 1);
        int line = index / CACHE_LINE;
        if (!cacheLineValid[line]) {
            int lineAddress = address & 0xFFF0;
            for (int i = 0; i < CACHE_LINE; i++) {
                cache[line * CACHE_LINE + i] = (byte) bus.read(programBank << 16 | ((lineAddress + i) & 0xFFFF));
                cycles += memoryCycles();
            }
            cacheLineValid[line] = true;
        } else {
            cycles += gsuClock();
        }
        return cache[index] & 0xFF;
    }

    void flushCache() {
        java.util.Arrays.fill(cacheLineValid, false);
    }

    /** Master cycles per GSU clock. */
    private int gsuClock() {
        return fastClock ? 1 : 2;
    }

    /** Master cycles for one ROM or RAM access. */
    private int memoryCycles() {
        return fastClock ? 5 : 6;
    }

    /** SFR as the console reads it ($3030-$3031). */
    int statusRegister() {
        return (zero ? 0x0002 : 0)
                | (carry ? 0x0004 : 0)
                | (sign ? 0x0008 : 0)
                | (overflow ? 0x0010 : 0)
                | (running ? 0x0020 : 0)
                | (alt1 ? 0x0100 : 0)
                | (alt2 ? 0x0200 : 0)
                | (withPrefix ? 0x1000 : 0)
                | (irqPending ? 0x8000 : 0);
    }
}
