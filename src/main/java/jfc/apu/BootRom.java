package jfc.apu;

/**
 * A replacement for the APU's 64-byte boot ROM (the "IPL"), mapped at $FFC0-$FFFF.
 *
 * <p>The real IPL is copyrighted Sony/Nintendo code, so this emulator doesn't include it. This
 * is a clean-room program written from the protocol alone, the protocol every game's sound
 * upload routine expects:
 * <ol>
 *   <li>The APU writes $AA to port 0 and $BB to port 1, meaning "ready".</li>
 *   <li>The CPU puts a destination address in ports 2-3, a non-zero command in port 1, and
 *       writes $CC to port 0. The APU echoes $CC.</li>
 *   <li>For each byte: the CPU puts the data in port 1 and a counter (0, 1, 2, ...) in port 0.
 *       The APU stores the byte and echoes the counter to acknowledge it.</li>
 *   <li>To start another block, the CPU sets a new address in ports 2-3 and writes any other
 *       value to port 0 (typically the counter plus 2). If port 1 is zero then, the APU jumps to
 *       the address instead: the uploaded sound driver starts running.</li>
 * </ol>
 *
 * <p>Hand-assembled SPC700 code ({@code dp} addresses $F4-$F7 are the four ports):
 * <pre>
 *   FFC0  20        CLRP               ; direct page = $00xx
 *   FFC1  CD EF     MOV  X,#$EF
 *   FFC3  BD        MOV  SP,X          ; stack at $01EF
 *   FFC4  8F BB F5  MOV  $F5,#$BB      ; announce "ready": $BB first, so that by the time
 *   FFC7  8F AA F4  MOV  $F4,#$AA      ;   the CPU sees $AA in port 0, $BB is already in port 1
 *   FFCA  78 CC F4  CMP  $F4,#$CC      ; wait for the CPU's $CC
 *   FFCD  D0 FB     BNE  $FFCA
 *   FFCF  BA F6     MOVW YA,$F6        ; newBlock: destination address from ports 2-3...
 *   FFD1  DA 00     MOVW $00,YA        ; ...kept at $00-$01
 *   FFD3  E4 F4     MOV  A,$F4         ; the value the CPU wrote to port 0 (to echo)
 *   FFD5  EB F5     MOV  Y,$F5         ; the command (sets Z when it's zero)
 *   FFD7  C4 F4     MOV  $F4,A         ; echo (stores don't change flags)
 *   FFD9  D0 05     BNE  $FFE0         ; non-zero command: receive a block
 *   FFDB  CD 00     MOV  X,#$00
 *   FFDD  1F 00 00  JMP  [$0000+X]     ; zero command: run the uploaded code
 *   FFE0  5D        MOV  X,A           ; receive: X = last value echoed
 *   FFE1  8D 00     MOV  Y,#$00        ; Y = counter of the next byte
 *   FFE3  3E F4     CMP  X,$F4         ; waitByte: port 0 unchanged? keep waiting
 *   FFE5  F0 FC     BEQ  $FFE3
 *   FFE7  7E F4     CMP  Y,$F4         ; is it the next counter?
 *   FFE9  D0 E4     BNE  $FFCF         ; no: it's the start of a new block
 *   FFEB  E4 F5     MOV  A,$F5         ; the data byte
 *   FFED  D7 00     MOV  [$00]+Y,A     ; store it
 *   FFEF  CB F4     MOV  $F4,Y         ; acknowledge by echoing the counter
 *   FFF1  DD        MOV  A,Y
 *   FFF2  5D        MOV  X,A           ; remember what we echoed
 *   FFF3  FC        INC  Y
 *   FFF4  D0 ED     BNE  $FFE3
 *   FFF6  AB 01     INC  $01           ; every 256 bytes, move the destination on a page
 *   FFF8  2F E9     BRA  $FFE3
 *   FFFA  00 00 00 00                  ; unused
 *   FFFE  C0 FF                        ; reset vector: $FFC0
 * </pre>
 * Games can re-enter it later by jumping to $FFC0, which is why it doesn't depend on any
 * state left over from a previous run.
 */
final class BootRom {

    static final int START = 0xFFC0;

    static final int[] CODE = {
        0x20,
        0xCD, 0xEF,
        0xBD,
        0x8F, 0xBB, 0xF5,
        0x8F, 0xAA, 0xF4,
        0x78, 0xCC, 0xF4,
        0xD0, 0xFB,
        0xBA, 0xF6,
        0xDA, 0x00,
        0xE4, 0xF4,
        0xEB, 0xF5,
        0xC4, 0xF4,
        0xD0, 0x05,
        0xCD, 0x00,
        0x1F, 0x00, 0x00,
        0x5D,
        0x8D, 0x00,
        0x3E, 0xF4,
        0xF0, 0xFC,
        0x7E, 0xF4,
        0xD0, 0xE4,
        0xE4, 0xF5,
        0xD7, 0x00,
        0xCB, 0xF4,
        0xDD,
        0x5D,
        0xFC,
        0xD0, 0xED,
        0xAB, 0x01,
        0x2F, 0xE9,
        0x00, 0x00, 0x00, 0x00,
        0xC0, 0xFF,
    };

    private BootRom() {
    }
}
