package snesible.debug;

import snesible.core.ppu.Ppu;
import snesible.core.system.WorkRam;

/**
 * Decodes the console's system bus, as the S-CPU and DMA use it, and records each access in
 * the memory it really reaches. The layout matches {@code SnesBus.readA}:
 * <pre>
 *   $7E-$7F                         work RAM, all 128 KiB
 *   $00-$3F,$80-$BF  $0000-$1FFF    work RAM, first 8 KiB ("low RAM")
 *                    $2100-$21FF    B-bus: PPU, APU ports, work-RAM port
 *                    $4000-$43FF    CPU I/O registers (not memory: nothing recorded)
 *   everything else                 the cartridge (see {@link CartridgeMap})
 * </pre>
 *
 * <h2>Ports: where an access really lands</h2>
 * VRAM, CGRAM, OAM and (through $2180) work RAM can also be reached through data ports on the
 * B-bus. The bus only sees "a write to $2118"; the chip decides which byte that is from an
 * address it keeps and steps by itself. So just before the access is passed on, this class
 * asks the chip where it will go ({@code Ppu.vramPortWordAddress()} and friends, read-only
 * getters) and records it there. That's why the wrappers call these methods <i>before</i>
 * passing an access on: afterwards the address would already have stepped.
 *
 * <p>Every B-bus write is also forwarded to {@link VideoRegisters}, for the derived PPU
 * activity.
 */
final class SystemBusMap implements BusMap {

    private final AccessRecorder recorder;
    private final CartridgeMap cartridge;
    private final Ppu ppu;
    private final WorkRam wram;
    private final VideoRegisters video;

    SystemBusMap(AccessRecorder recorder, CartridgeMap cartridge, Ppu ppu, WorkRam wram, VideoRegisters video) {
        this.recorder = recorder;
        this.cartridge = cartridge;
        this.ppu = ppu;
        this.wram = wram;
        this.video = video;
    }

    /** Records a read of a 24-bit A-bus address (a CPU read or fetch, or DMA's A side). */
    @Override
    public void read(int address, Access kind) {
        int bank = address >>> 16;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            recorder.record(recorder.wram, address & 0x1FFFF, kind);
        } else if ((bank & 0x40) == 0 && offset < 0x2000) {
            recorder.record(recorder.wram, offset, kind);
        } else if ((bank & 0x40) == 0 && offset >= 0x2100 && offset < 0x2200) {
            readB(offset & 0xFF, kind);
        } else if ((bank & 0x40) == 0 && offset >= 0x4000 && offset < 0x4400) {
            // CPU I/O registers: not a memory
        } else {
            cartridge.record(address, kind);
        }
    }

    /** Records a write to a 24-bit A-bus address. */
    @Override
    public void write(int address, int value, Access kind) {
        int bank = address >>> 16;
        int offset = address & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) {
            recorder.record(recorder.wram, address & 0x1FFFF, kind);
        } else if ((bank & 0x40) == 0 && offset < 0x2000) {
            recorder.record(recorder.wram, offset, kind);
        } else if ((bank & 0x40) == 0 && offset >= 0x2100 && offset < 0x2200) {
            writeB(offset & 0xFF, value, kind);
        } else if ((bank & 0x40) == 0 && offset >= 0x4000 && offset < 0x4400) {
            // CPU I/O registers: not a memory
        } else {
            cartridge.recordWrite(address, value, kind);
        }
    }

    /** Records a B-bus read ({@code register} = the low byte of $21xx). */
    void readB(int register, Access kind) {
        switch (register) {
            case 0x38 -> recorder.record(recorder.oam, ppu.oamPortByteAddress(), kind);
            // VRAM reads go through a one-word buffer: the read that steps the address also
            // refills the buffer from the current word, so that's when VRAM is really read.
            case 0x39, 0x3A -> {
                if ((register == 0x3A) == video.vramIncrementOnHighByte()) {
                    int word = ppu.vramPortWordAddress();
                    recorder.record(recorder.vram, word * 2, kind);
                    recorder.record(recorder.vram, word * 2 + 1, kind);
                }
            }
            case 0x3B -> recorder.record(recorder.cgram, ppu.cgramPortByteAddress(), kind);
            case 0x80 -> recorder.record(recorder.wram, wram.portAddress(), kind);
            default -> {
                // other registers aren't memory ports
            }
        }
    }

    /** Records a B-bus write and follows it in the PPU register copy. */
    void writeB(int register, int value, Access kind) {
        switch (register) {
            case 0x04 -> recorder.record(recorder.oam, ppu.oamPortByteAddress(), kind);
            case 0x18 -> recorder.record(recorder.vram, ppu.vramPortWordAddress() * 2, kind);
            case 0x19 -> recorder.record(recorder.vram, ppu.vramPortWordAddress() * 2 + 1, kind);
            case 0x22 -> recorder.record(recorder.cgram, ppu.cgramPortByteAddress(), kind);
            case 0x80 -> recorder.record(recorder.wram, wram.portAddress(), kind);
            default -> {
                // other registers aren't memory ports
            }
        }
        video.write(register, value);
    }
}
