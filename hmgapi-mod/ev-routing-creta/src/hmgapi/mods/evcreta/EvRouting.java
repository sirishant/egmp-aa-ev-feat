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
    static final String LIB = "libautoreceiver_infobank.so";

    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) {
        if (!GPM.equals(app.packageName) || !GPM.equals(app.processName)) return;
        EvDriveLog.start();
        mod.nativePatch(app, LIB);
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            // the battery level, range and odometer for Android Auto apps (LiveData): types the stock
            // library takes; HMG-API offers them (mod.json aa_sensors add 6 and 5)
            @Override
            protected void afterHookedMethod(Param m) {
                if (!Boolean.FALSE.equals(m.getResult())) {
                    try {
                        startLiveData(m.thisObject);
                    } catch (Throwable t) {
                        mod.error("live battery data not started", t);
                    }
                }
                if (Boolean.FALSE.equals(m.getResult())) {
                    mod.log("energy model off: Android Auto's sensor service didn't register");
                    return;
                }
                // without the library patch, the raw call is the stock diagnostics report: nothing goes out
                if (!mod.nativePatched(LIB)) {
                    mod.log("energy model off: the AA library patch isn't in place");
                    return;
                }
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
                LiveData.stop();
            }
        });
        mod.hook(cl, "gpm.receiver_service.disconnected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                EvModelSender.stop();
                LiveData.stop();
            }
        });
        mod.hook(cl, "gpm.receiver.destroy", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param m) {
                EvDriveLog.flush();
            }
        });
    }

    /**
     * The battery level, range and odometer through GPM's own senders: the fuel report by its
     * native call (an unknown range is left out rather than sent as 0), the odometer by its
     * Java wrapper (km).
     */
    static void startLiveData(final Object source) throws Exception {
        final Method fuel = source.getClass().getDeclaredMethod("nativeReportFuelData",
                boolean.class, int.class, boolean.class, int.class, boolean.class, boolean.class);
        fuel.setAccessible(true);
        final Method odo = source.getClass().getDeclaredMethod("reportOdometerData", double.class);
        odo.setAccessible(true);
        LiveData.start(new LiveData.Out() {
            @Override
            public void fuel(int percent, int rangeMeters, boolean low) throws Exception {
                fuel.invoke(source, true, percent, rangeMeters >= 0, Math.max(rangeMeters, 0), true, low);
            }

            @Override
            public void odometer(double km) throws Exception {
                odo.invoke(source, km);
            }
        });
    }
}
