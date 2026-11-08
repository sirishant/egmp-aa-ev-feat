package hmgapi.mods.evcreta;

/** LiveData's fuel values: the state of charge rounded as the cluster does, the range in meters or left out. */
public final class LiveDataTest {
    static int fails;

    static void eq(String what, String want, String got) {
        if (want.equals(got)) System.out.println("ok    " + what);
        else {
            System.out.println("FAIL  " + what + ": wanted [" + want + "], got [" + got + "]");
            fails++;
        }
    }

    static String f(int half, int km) {
        int[] r = LiveData.fuel(half, km);
        return r == null ? "null" : r[0] + " " + r[1] + " " + r[2];
    }

    public static void main(String[] a) {
        eq("85% (half steps rounded up), 396 km", "85 396000 0", f(170, 396));
        eq("87.5% shows 88", "88 433000 0", f(175, 433));
        eq("range unknown: left out (-1)", "85 -1 0", f(170, -1));
        eq("range out of range (the Creta's DTE 65790): left out", "85 -1 0", f(170, 65790));
        eq("low at 10%", "10 40000 1", f(20, 40));
        eq("no state of charge: nothing", "null", f(-1, 300));
        eq("sensor offered once", "[1, 6, 5]", java.util.Arrays.toString(withSensor()));
        if (fails > 0) throw new AssertionError(fails + " failed");
    }

    static int[] withSensor() {
        int[] t = { 1 };
        t = LiveData.withSensor(LiveData.withSensor(t, 6), 5);
        return LiveData.withSensor(t, 6);
    }
}
