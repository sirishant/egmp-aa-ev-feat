package hmgapi.mods.evcreta;

import java.lang.reflect.Method;

import hmgapi.hook.Hooks;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

/**
 * Google Maps EV routing over Android Auto, and the EV logs, for the Hyundai Creta
 * Electric India. CRETA-SPECIFIC: the energy model is this car's, and mod.json's "system"
 * keeps the mod off every other car. EV only: what the cluster shows belongs to the
 * Wireless mod and wireless-creta-rhd.
 *
 * mod.json offers the phone sensor 23 and the car's fuel and connector types; the
 * library recipe lets the AA library accept type 23 and send one whole raw message.
 */
public final class EvRouting extends ModEntry {
    static final String GPM = "com.google.android.projection.sink";
    static final String SENSOR_SOURCE = "com.google.android.projection.protocol.SensorSource";

    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) {
        if (!GPM.equals(app.packageName)) return;
        EvDriveLog.start();
        mod.nativePatch(app, "libautoreceiver_infobank.so");
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param m) {
                try {
                    final Object source = m.thisObject;
                    final Method raw = source.getClass().getDeclaredMethod("nativeReportDiagnosticsData", byte[].class);
                    raw.setAccessible(true);
                    EvModelSender.start(new Sink() {
                        @Override
                        public int send(byte[] message) {
                            try {
                                return (Integer) raw.invoke(source, (Object) message);
                            } catch (ReflectiveOperationException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    });
                } catch (Throwable t) {
                    mod.error("energy model not started", t);
                }
            }
        });
        // shutdown() frees the native object the sender writes to, so the sender has to be
        // gone before it runs. HMG-API has no hook point for it, and a new one would need
        // every car's map redone, so this hooks the method by name.
        Hooks.findAndHookMethod(SENSOR_SOURCE, cl, "shutdown", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                EvModelSender.stop();
            }
        });
        mod.hook(cl, "gpm.receiver.destroy", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                EvDriveLog.flush();
            }
        });
    }
}
