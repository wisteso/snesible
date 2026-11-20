package snesible.core.observe;

import static org.junit.jupiter.api.Assertions.assertSame;

import snesible.core.apu.Spc700Bus;
import snesible.core.coprocessor.superfx.GsuBus;
import snesible.core.cpu.CpuBus;
import snesible.core.dma.DmaController;
import org.junit.jupiter.api.Test;

/**
 * With no observer installed (every normal run), each {@code wrapXxx} must hand back the very
 * object it was given, so the processors talk to their real buses with nothing in between.
 */
class ObservableTest {

    @Test
    void withoutAnObserverEveryWrapReturnsItsArgument() {
        Observable.install(null);

        CpuBus cpuBus = new CpuBus() {
            @Override
            public int read(int address) {
                return 0;
            }

            @Override
            public void write(int address, int value) {
            }

            @Override
            public void idle() {
            }
        };
        DmaController.Bus dmaBus = new DmaController.Bus() {
            @Override
            public int readA(int address) {
                return 0;
            }

            @Override
            public void writeA(int address, int value) {
            }

            @Override
            public int readB(int register) {
                return 0;
            }

            @Override
            public void writeB(int register, int value) {
            }
        };
        Spc700Bus spcBus = new Spc700Bus() {
            @Override
            public int read(int address) {
                return 0;
            }

            @Override
            public void write(int address, int value) {
            }

            @Override
            public void idle() {
            }
        };
        GsuBus gsuBus = new GsuBus() {
            @Override
            public int read(int address) {
                return 0;
            }

            @Override
            public void write(int address, int value) {
            }
        };

        assertSame(cpuBus, Observable.wrapCpu(cpuBus));
        assertSame(dmaBus, Observable.wrapDma(dmaBus));
        assertSame(spcBus, Observable.wrapSpc700(spcBus));
        assertSame(cpuBus, Observable.wrapSa1(cpuBus));
        assertSame(gsuBus, Observable.wrapGsu(gsuBus));
    }
}
