package jfc.apu;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the SingleStepTests SPC700 suite: 256 files, one per opcode, 1,000 tests each, checking
 * registers, memory, and every bus cycle (reads with their addresses, writes with their values,
 * and "wait" cycles). Download it with {@code scripts/download-cpu-tests.ps1}.
 */
class Spc700SingleStepTest {

    private static final Path SUITE_DIR = Path.of("testdata", "spc700");
    private static final int FAILURES_TO_SHOW = 3;

    enum Kind { READ, WRITE, WAIT }

    /** One bus cycle; value -1 means "not recorded" (the suite leaves dummy read values out). */
    record Cycle(Kind kind, int address, int value) {
        @Override
        public String toString() {
            return switch (kind) {
                case READ -> String.format("R %04X", address);
                case WRITE -> String.format("W %04X=%02X", address, value);
                case WAIT -> "wait";
            };
        }
    }

    record State(int pc, int a, int x, int y, int sp, int psw, int[][] ram) {
    }

    /** A flat 64 KiB of RAM that records every cycle. */
    static final class FlatBus implements Spc700Bus {
        final int[] memory = new int[0x10000];
        final List<Cycle> cycles = new ArrayList<>();

        @Override
        public int read(int address) {
            cycles.add(new Cycle(Kind.READ, address, memory[address]));
            return memory[address];
        }

        @Override
        public void write(int address, int value) {
            cycles.add(new Cycle(Kind.WRITE, address, value));
            memory[address] = value;
        }

        @Override
        public void idle() {
            cycles.add(new Cycle(Kind.WAIT, -1, -1));
        }
    }

    @TestFactory
    Stream<DynamicTest> singleStepTests() throws IOException {
        assumeTrue(Files.isDirectory(SUITE_DIR),
                "SPC700 SingleStepTests not downloaded; run scripts/download-cpu-tests.ps1");
        List<Path> files;
        try (Stream<Path> listing = Files.list(SUITE_DIR)) {
            files = listing.filter(f -> f.toString().endsWith(".json.gz")).sorted().toList();
        }
        return files.stream().map(file -> DynamicTest.dynamicTest(
                file.getFileName().toString().replace(".json.gz", ""), () -> runFile(file)));
    }

    private static void runFile(Path file) throws IOException {
        List<String> failures = new ArrayList<>();
        int failureCount = 0;
        int run = 0;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             JsonReader json = new JsonReader(reader)) {
            json.beginArray();
            while (json.hasNext()) {
                String problem = runTest(json);
                run++;
                if (problem != null) {
                    failureCount++;
                    if (failures.size() < FAILURES_TO_SHOW) {
                        failures.add(problem);
                    }
                }
            }
        }
        if (failureCount > 0) {
            fail(failureCount + " of " + run + " tests failed, e.g.\n  " + String.join("\n  ", failures));
        }
    }

    private static String runTest(JsonReader json) throws IOException {
        String name = null;
        State initial = null;
        State expected = null;
        List<Cycle> expectedCycles = null;
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "name" -> name = json.nextString();
                case "initial" -> initial = readState(json);
                case "final" -> expected = readState(json);
                case "cycles" -> expectedCycles = readCycles(json);
                default -> json.skipValue();
            }
        }
        json.endObject();

        FlatBus bus = new FlatBus();
        for (int[] entry : initial.ram()) {
            bus.memory[entry[0]] = entry[1];
        }
        Spc700 cpu = new Spc700(bus);
        cpu.pc = initial.pc();
        cpu.a = initial.a();
        cpu.x = initial.x();
        cpu.y = initial.y();
        cpu.sp = initial.sp();
        cpu.setPsw(initial.psw());

        cpu.step();
        // SLEEP and STOP spin forever; the suite records a few of their idle cycle pairs.
        while (cpu.isHalted() && bus.cycles.size() < expectedCycles.size()) {
            cpu.step();
        }

        String diff = compare(cpu, expected);
        if (diff != null) {
            return name + ": " + diff + "  [cycles " + bus.cycles + "]";
        }
        for (int[] entry : expected.ram()) {
            if (bus.memory[entry[0]] != entry[1]) {
                return String.format("%s: RAM[%04X] = %02X, expected %02X", name, entry[0],
                        bus.memory[entry[0]], entry[1]);
            }
        }
        if (!cyclesMatch(bus.cycles, expectedCycles)) {
            return name + ": bus cycles differ\n      got      " + bus.cycles
                    + "\n      expected " + expectedCycles;
        }
        return null;
    }

    private static String compare(Spc700 cpu, State expected) {
        StringBuilder diff = new StringBuilder();
        check(diff, "PC", cpu.pc, expected.pc());
        check(diff, "A", cpu.a, expected.a());
        check(diff, "X", cpu.x, expected.x());
        check(diff, "Y", cpu.y, expected.y());
        check(diff, "SP", cpu.sp, expected.sp());
        check(diff, "PSW", cpu.psw(), expected.psw());
        return diff.isEmpty() ? null : diff.toString().trim();
    }

    private static void check(StringBuilder diff, String name, int actual, int expected) {
        if (actual != expected) {
            diff.append(String.format("%s=%02X (expected %02X) ", name, actual, expected));
        }
    }

    private static boolean cyclesMatch(List<Cycle> actual, List<Cycle> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            Cycle a = actual.get(i);
            Cycle e = expected.get(i);
            if (a.kind() != e.kind()) {
                return false;
            }
            if (a.kind() != Kind.WAIT && a.address() != e.address()) {
                return false;
            }
            if (a.kind() == Kind.WRITE && a.value() != e.value()) {
                return false;
            }
        }
        return true;
    }

    private static State readState(JsonReader json) throws IOException {
        int pc = 0, a = 0, x = 0, y = 0, sp = 0, psw = 0;
        int[][] ram = new int[0][];
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "pc" -> pc = json.nextInt();
                case "a" -> a = json.nextInt();
                case "x" -> x = json.nextInt();
                case "y" -> y = json.nextInt();
                case "sp" -> sp = json.nextInt();
                case "psw" -> psw = json.nextInt();
                case "ram" -> {
                    List<int[]> entries = new ArrayList<>();
                    json.beginArray();
                    while (json.hasNext()) {
                        json.beginArray();
                        entries.add(new int[] {json.nextInt(), json.nextInt()});
                        json.endArray();
                    }
                    json.endArray();
                    ram = entries.toArray(new int[0][]);
                }
                default -> json.skipValue();
            }
        }
        json.endObject();
        return new State(pc, a, x, y, sp, psw, ram);
    }

    private static List<Cycle> readCycles(JsonReader json) throws IOException {
        List<Cycle> cycles = new ArrayList<>();
        json.beginArray();
        while (json.hasNext()) {
            json.beginArray();
            int address = nullableInt(json);
            int value = nullableInt(json);
            Kind kind = switch (json.nextString()) {
                case "read" -> Kind.READ;
                case "write" -> Kind.WRITE;
                default -> Kind.WAIT;
            };
            json.endArray();
            cycles.add(new Cycle(kind, address, value));
        }
        json.endArray();
        return cycles;
    }

    private static int nullableInt(JsonReader json) throws IOException {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull();
            return -1;
        }
        return json.nextInt();
    }
}
