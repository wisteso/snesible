package jfc.core.cpu;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One test from the SingleStepTests 65816 suite: a CPU state, the memory around it, and the
 * expected state and bus activity after one instruction.
 *
 * <p>JSON shape (see the suite's README):
 * <pre>
 * { "name": "3d e 1",
 *   "initial": { "pc":..,"s":..,"p":..,"a":..,"x":..,"y":..,"dbr":..,"d":..,"pbr":..,"e":..,
 *                "ram": [[address, value], ...] },
 *   "final":   { ...same fields... },
 *   "cycles":  [[address, value, "dp-remx-"], ...] }
 * </pre>
 */
record SingleStepTestCase(String name, State initial, State expected, List<RecordingBus.Cycle> cycles) {

    record State(int pc, int s, int p, int a, int x, int y, int dbr, int d, int pbr, boolean e,
                 int[][] ram) {
    }

    /** Reads the next test object from a stream positioned inside the top-level array. */
    static SingleStepTestCase read(JsonReader json) throws IOException {
        String name = null;
        State initial = null;
        State expected = null;
        List<RecordingBus.Cycle> cycles = null;
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "name" -> name = json.nextString();
                case "initial" -> initial = readState(json);
                case "final" -> expected = readState(json);
                case "cycles" -> cycles = readCycles(json);
                default -> json.skipValue();
            }
        }
        json.endObject();
        return new SingleStepTestCase(name, initial, expected, cycles);
    }

    private static State readState(JsonReader json) throws IOException {
        int pc = 0, s = 0, p = 0, a = 0, x = 0, y = 0, dbr = 0, d = 0, pbr = 0;
        boolean e = false;
        int[][] ram = new int[0][];
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "pc" -> pc = json.nextInt();
                case "s" -> s = json.nextInt();
                case "p" -> p = json.nextInt();
                case "a" -> a = json.nextInt();
                case "x" -> x = json.nextInt();
                case "y" -> y = json.nextInt();
                case "dbr" -> dbr = json.nextInt();
                case "d" -> d = json.nextInt();
                case "pbr" -> pbr = json.nextInt();
                case "e" -> e = json.nextInt() != 0;
                case "ram" -> ram = readRam(json);
                default -> json.skipValue();
            }
        }
        json.endObject();
        return new State(pc, s, p, a, x, y, dbr, d, pbr, e, ram);
    }

    private static int[][] readRam(JsonReader json) throws IOException {
        List<int[]> entries = new ArrayList<>();
        json.beginArray();
        while (json.hasNext()) {
            json.beginArray();
            entries.add(new int[] {json.nextInt(), json.nextInt()});
            json.endArray();
        }
        json.endArray();
        return entries.toArray(new int[0][]);
    }

    /**
     * Converts the suite's pin-level cycle records into our three kinds. A cycle where none of
     * VDA, VPA or VPB is active doesn't touch memory, so it's an internal operation, even
     * though the read/write pin says "read".
     */
    private static List<RecordingBus.Cycle> readCycles(JsonReader json) throws IOException {
        List<RecordingBus.Cycle> cycles = new ArrayList<>();
        json.beginArray();
        while (json.hasNext()) {
            json.beginArray();
            int address = nullableInt(json);
            int value = nullableInt(json);
            String pins = json.nextString();
            json.endArray();
            boolean memoryActive = pins.charAt(0) == 'd' || pins.charAt(1) == 'p' || pins.charAt(2) == 'v';
            RecordingBus.Kind kind;
            if (!memoryActive) {
                kind = RecordingBus.Kind.IDLE;
            } else if (pins.charAt(3) == 'r') {
                kind = RecordingBus.Kind.READ;
            } else {
                kind = RecordingBus.Kind.WRITE;
            }
            cycles.add(new RecordingBus.Cycle(kind, address, value));
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
