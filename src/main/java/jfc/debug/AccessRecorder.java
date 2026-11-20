package jfc.debug;

import java.util.ArrayList;
import java.util.List;
import jfc.core.coprocessor.Coprocessor;
import jfc.core.coprocessor.sa1.Sa1;
import jfc.core.system.Snes;
import jfc.core.system.WorkRam;

/**
 * Every memory space of one console, plus the frame counter their access stamps refer to.
 *
 * <p>The spaces, in the order the UI lists them:
 * <ul>
 *   <li><b>WRAM</b>: the console's 128 KiB of work RAM (editable).</li>
 *   <li><b>ROM</b>: the cartridge ROM image.</li>
 *   <li><b>Cart RAM</b>: battery SRAM, SuperFX RAM or SA-1 BW-RAM, if the cartridge has any
 *       (editable).</li>
 *   <li><b>I-RAM</b>: the SA-1's 2 KiB on-chip RAM, on SA-1 cartridges (editable).</li>
 *   <li><b>VRAM</b>: 64 KiB of video RAM (32 K words, shown as bytes: low byte first).</li>
 *   <li><b>CGRAM</b>: the 256-colour palette, 512 bytes.</li>
 *   <li><b>OAM</b>: sprite attributes, 544 bytes.</li>
 *   <li><b>ARAM</b>: the sound CPU's 64 KiB.</li>
 * </ul>
 */
public final class AccessRecorder {

    final MemorySpace wram;
    final MemorySpace rom;
    /** Null when the cartridge has no RAM. */
    final MemorySpace cartRam;
    /** Null unless it's an SA-1 cartridge. */
    final MemorySpace iram;
    final MemorySpace vram;
    final MemorySpace cgram;
    final MemorySpace oam;
    final MemorySpace aram;
    private final List<MemorySpace> spaces = new ArrayList<>();

    /**
     * The current frame number. Starts at 1, so a stamp of 0 can mean "never". An int lasts
     * over a year of emulated frames.
     */
    private int frame = 1;

    AccessRecorder(Snes snes) {
        WorkRam workRam = snes.wram();
        wram = add(new MemorySpace("WRAM", WorkRam.SIZE, workRam::read, true));
        byte[] romBytes = snes.cartridge().romBytes();
        rom = add(new MemorySpace("ROM", romBytes.length, i -> romBytes[i] & 0xFF, false));
        byte[] ramBytes = snes.cartridge().ramBytes();
        cartRam = ramBytes.length == 0 ? null
                : add(new MemorySpace("Cart RAM", ramBytes.length, i -> ramBytes[i] & 0xFF, true));
        Coprocessor coprocessor = snes.coprocessor();
        if (coprocessor instanceof Sa1 sa1) {
            byte[] iramBytes = sa1.iramBytes();
            iram = add(new MemorySpace("I-RAM", iramBytes.length, i -> iramBytes[i] & 0xFF, true));
        } else {
            iram = null;
        }
        var ppu = snes.ppu();
        // VRAM is 32 K 16-bit words. As bytes, word w is bytes 2w (low) and 2w+1 (high), the
        // order the CPU writes them through $2118/$2119.
        vram = add(new MemorySpace("VRAM", 0x10000, i -> (ppu.peekVram(i >> 1) >> ((i & 1) * 8)) & 0xFF, false));
        // CGRAM is 256 15-bit colours, low byte first, the order $2122 takes them.
        cgram = add(new MemorySpace("CGRAM", 0x200, i -> (ppu.peekCgram(i >> 1) >> ((i & 1) * 8)) & 0xFF, false));
        oam = add(new MemorySpace("OAM", 0x220, ppu::peekOam, false));
        byte[] aramBytes = snes.apu().ramBytes();
        aram = add(new MemorySpace("ARAM", aramBytes.length, i -> aramBytes[i] & 0xFF, false));
    }

    private MemorySpace add(MemorySpace space) {
        spaces.add(space);
        return space;
    }

    /** All spaces this console has, in display order. */
    public List<MemorySpace> spaces() {
        return List.copyOf(spaces);
    }

    /** The frame accesses are being recorded in now. */
    public int frame() {
        return frame;
    }

    /** Starts recording into the next frame. Called once per emulated frame. */
    void nextFrame() {
        frame++;
    }

    void record(MemorySpace space, int offset, Access kind) {
        space.record(offset, kind, frame);
    }
}
