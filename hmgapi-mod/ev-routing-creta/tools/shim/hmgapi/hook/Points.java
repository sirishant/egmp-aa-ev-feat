package hmgapi.hook;

/** Host stand-in: no hook map off the car, so every car read answers "unknown". */
public final class Points {
    private Points() {}

    public static Object get(ClassLoader cl, String id, Object target) {
        return null;
    }

    public static Object call(ClassLoader cl, String id, Object target, Object... args) {
        return null;
    }
}
