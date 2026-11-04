package dalvik.system;

/** Host stand-in for Android's PathClassLoader (unused off the car). */
public class PathClassLoader extends ClassLoader {
    public PathClassLoader(String path, ClassLoader parent) {
        super(parent);
    }
}
