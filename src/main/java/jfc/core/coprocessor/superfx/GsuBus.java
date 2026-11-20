package jfc.core.coprocessor.superfx;

/**
 * The GSU's view of the cartridge memories. Unlike the console CPU, the GSU only ever sees the
 * ROM and its own RAM:
 * <pre>
 *   Banks $00-$3F  $0000-$FFFF   ROM, LoROM layout (both halves show the same 32 KiB)
 *   Banks $40-$5F  $0000-$FFFF   ROM, linear: 64 KiB per bank
 *   Banks $60-$7F  $0000-$FFFF   RAM (in practice $70-$71; larger addresses mirror)
 * </pre>
 * Every access the GSU makes uses a 24-bit address in these terms: code is fetched from
 * {@code PBR:R15}, ROM data from {@code ROMBR:R14}, RAM data from {@code (bank $70 + RAMBR):address}.
 *
 * <p><b>Debug support:</b> public only so {@link jfc.core.observe.Observable#wrapGsu} can hand
 * it to an outside observer (the visual memory debugger). Emulation itself only uses it inside
 * this package.
 */
public interface GsuBus {

    /** Reads ROM or RAM at a 24-bit GSU address. */
    int read(int address);

    /** Writes RAM at a 24-bit GSU address (ROM addresses ignore writes). */
    void write(int address, int value);
}
