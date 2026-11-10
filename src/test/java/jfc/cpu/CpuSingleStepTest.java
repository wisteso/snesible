package jfc.cpu;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.stream.JsonReader;
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
 * Runs the SingleStepTests 65816 suite: 512 files (each opcode, in emulation and native mode),
 * 10,000 tests each.
 *
 * <p>Each test sets up a CPU state, runs one instruction, and checks three things: the final
 * registers, the memory contents, and the exact sequence of bus cycles (reads and writes with
 * their addresses and values, plus idle cycles). The last check is what guarantees our timing
 * matches the real chip.
 *
 * <p>Download the suite with {@code scripts/download-cpu-tests.ps1}. Without it, these tests
 * are skipped. By default only the first {@value #DEFAULT_TESTS_PER_FILE} tests of each file run,
 * to keep {@code mvn test} quick; run everything with {@code -Djfc.cpuTests=all}.
 */
class CpuSingleStepTest {

    private static final Path SUITE_DIR = Path.of("testdata", "65816");
    private static final int DEFAULT_TESTS_PER_FILE = 1000;
    /** How many failing cases to describe per file; the rest are only counted. */
    private static final int FAILURES_TO_SHOW = 3;

    @TestFactory
    Stream<DynamicTest> singleStepTests() throws IOException {
        assumeTrue(Files.isDirectory(SUITE_DIR),
                "SingleStepTests not downloaded; run scripts/download-cpu-tests.ps1");
        int limit = testsPerFile();
        List<Path> files;
        try (Stream<Path> listing = Files.list(SUITE_DIR)) {
            files = listing.filter(f -> f.toString().endsWith(".json.gz")).sorted().toList();
        }
        return files.stream().map(file -> DynamicTest.dynamicTest(
                file.getFileName().toString().replace(".json.gz", ""),
                () -> runFile(file, limit)));
    }

    private static int testsPerFile() {
        String setting = System.getProperty("jfc.cpuTests", String.valueOf(DEFAULT_TESTS_PER_FILE));
        return setting.equals("all") ? Integer.MAX_VALUE : Integer.parseInt(setting);
    }

    private static void runFile(Path file, int limit) throws IOException {
        RecordingBus bus = new RecordingBus();
        List<String> failures = new ArrayList<>();
        int failureCount = 0;
        int run = 0;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             JsonReader json = new JsonReader(reader)) {
            json.beginArray();
            while (json.hasNext() && run < limit) {
                SingleStepTestCase test = SingleStepTestCase.read(json);
                run++;
                String problem = runTest(test, bus);
                if (problem != null) {
                    failureCount++;
                    if (failures.size() < FAILURES_TO_SHOW) {
                        failures.add(test.name() + ": " + problem);
                    }
                }
            }
        }
        if (failureCount > 0) {
            fail(failureCount + " of " + run + " tests failed, e.g.\n  " + String.join("\n  ", failures));
        }
    }

    /** Runs one test and returns a description of the first mismatch, or null if it passed. */
    private static String runTest(SingleStepTestCase test, RecordingBus bus) {
        bus.reset();
        Cpu cpu = new Cpu(bus);
        load(cpu.registers(), test.initial());
        for (int[] entry : test.initial().ram()) {
            bus.poke(entry[0], entry[1]);
        }
        bus.cycles().clear();

        int opcode = bus.peek((test.initial().pbr() << 16) | test.initial().pc());
        boolean blockMove = opcode == 0x44 || opcode == 0x54;
        do {
            cpu.step();
            // MVN/MVP repeat by pointing PC back at themselves; the test covers the whole move.
        } while (blockMove && cpu.registers().pc == test.initial().pc()
                && bus.cycles().size() < 100_000);

        String registers = compareRegisters(cpu.registers(), test.expected());
        if (registers != null) {
            return registers + "  [cycles " + bus.cycles() + "]";
        }
        for (int[] entry : test.expected().ram()) {
            int actual = bus.peek(entry[0]);
            if (actual != entry[1]) {
                return String.format("RAM[%06X] = %02X, expected %02X", entry[0], actual, entry[1]);
            }
        }
        return compareCycles(bus.cycles(), test.cycles());
    }

    private static void load(Registers r, SingleStepTestCase.State state) {
        r.pc = state.pc();
        r.a = state.a();
        r.x = state.x();
        r.y = state.y();
        r.d = state.d();
        r.dbr = state.dbr();
        r.pbr = state.pbr();
        r.emulation = state.e();
        r.p.setFromByte(state.p());
        // The real CPU can't have S outside page $01 in emulation mode, but some initial states in
        // the suite do; its expected results show S forced back into page $01.
        r.sp = state.e() ? 0x0100 | (state.s() & 0xFF) : state.s();
    }

    private static String compareRegisters(Registers r, SingleStepTestCase.State expected) {
        StringBuilder diff = new StringBuilder();
        check(diff, "PC", r.pc, expected.pc(), 4);
        check(diff, "A", r.a, expected.a(), 4);
        check(diff, "X", r.x, expected.x(), 4);
        check(diff, "Y", r.y, expected.y(), 4);
        check(diff, "S", r.sp, expected.s(), 4);
        check(diff, "D", r.d, expected.d(), 4);
        check(diff, "DBR", r.dbr, expected.dbr(), 2);
        check(diff, "PBR", r.pbr, expected.pbr(), 2);
        check(diff, "P", r.p.toByte(), expected.p(), 2);
        check(diff, "E", r.emulation ? 1 : 0, expected.e() ? 1 : 0, 1);
        return diff.isEmpty() ? null : diff.toString().trim();
    }

    private static void check(StringBuilder diff, String name, int actual, int expected, int digits) {
        if (actual != expected) {
            String format = "%s=%0" + digits + "X (expected %0" + digits + "X) ";
            diff.append(String.format(format, name, actual, expected));
        }
    }

    private static String compareCycles(List<RecordingBus.Cycle> actual, List<RecordingBus.Cycle> expected) {
        boolean matches = actual.size() == expected.size();
        for (int i = 0; matches && i < actual.size(); i++) {
            RecordingBus.Cycle a = actual.get(i);
            RecordingBus.Cycle e = expected.get(i);
            matches = a.kind() == e.kind()
                    && (a.kind() == RecordingBus.Kind.IDLE || a.address() == e.address())
                    && (a.kind() != RecordingBus.Kind.WRITE || a.value() == e.value());
        }
        if (matches) {
            return null;
        }
        return "bus cycles differ\n      got      " + actual + "\n      expected " + expected;
    }
}
