package snesible.debug;

import snesible.core.cartridge.Cartridge;
import snesible.core.cartridge.HiRomMapper;
import snesible.core.cartridge.LoRomMapper;
import snesible.core.cartridge.Mapper;

/**
 * A cartridge without a coprocessor: LoROM or HiROM, ROM plus optional battery SRAM. Decoded
 * with the same {@link Mapper} classes the cartridge uses, built here from its map mode and
 * sizes (the same choice {@code Cartridge} makes), so the debugger sees exactly the offsets the
 * emulation reads.
 */
final class PlainCartridgeMap implements CartridgeMap {

    private final Mapper mapper;
    private final AccessRecorder recorder;

    PlainCartridgeMap(Cartridge cartridge, AccessRecorder recorder) {
        this.recorder = recorder;
        int romSize = cartridge.romBytes().length;
        int ramSize = cartridge.ramBytes().length;
        this.mapper = switch (cartridge.mapMode()) {
            case LOROM -> new LoRomMapper(romSize, ramSize);
            case HIROM -> new HiRomMapper(romSize, ramSize);
            // Cartridge itself refuses these, so a Snes can't exist with one.
            case EXHIROM -> throw new IllegalStateException("ExHiROM isn't supported");
        };
    }

    @Override
    public void record(int address, Access kind) {
        // Same order as Cartridge.read: ROM first, then SRAM.
        int romOffset = mapper.romOffset(address);
        if (romOffset != Mapper.NOT_MAPPED) {
            recorder.record(recorder.rom, romOffset, kind);
            return;
        }
        int ramOffset = mapper.sramOffset(address);
        if (ramOffset != Mapper.NOT_MAPPED && recorder.cartRam != null) {
            recorder.record(recorder.cartRam, ramOffset, kind);
        }
    }
}
