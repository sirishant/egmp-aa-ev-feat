package hmgapi.mods.evcreta;

import android.util.Log;

/**
 * The battery level, range and odometer for Android Auto apps on the phone (A Better Route
 * Planner and others read them through the Car App Library's CarInfo: energy level and
 * mileage). Android Auto carries them as the fuel sensor (6: level %, range, low warning)
 * and the odometer sensor (5); GPM's SensorSource can send both (nativeReportFuelData,
 * reportOdometerData, also in the Creta's GPM) but never offers them. Both are types the
 * stock library accepts, so no library patch: EvRouting offers them at the sensor service's
 * start, and this sends them every PERIOD_MS while a session runs.
 *
 * After AwsomeFox/egmp-aa-ev-hmg-api ddc9e3d ("Live battery %, range and odometer for
 * Android Auto apps"). On the Creta the range is HSCAN's (ableDriveDistance, what CarPlay EV
 * routing uses): the navigation battery packet's DTE reads invalid there (65790).
 * A value that can't be read isn't sent; a fuel report waits for the state of charge.
 */
final class LiveData {
    private static final String TAG = "EvLiveData";
    static final int SENSOR_ODOMETER = 5, SENSOR_FUEL = 6;
    private static final int PERIOD_MS = 5000;
    private static final long MAX_AGE_MS = 1500;   // shares EvDriveLog's and the sender's reads
    private static final int HEV_DISPLAY_SOC = 0x19, HEV_ODOMETER = 0x1f;
    /** the car warns about a low battery around here (%) */
    static final int LOW_PCT = 10;

    /** where the readings go: GPM's SensorSource */
    interface Out {
        /** rangeMeters -1: unknown (left out of the message, not sent as 0) */
        void fuel(int percent, int rangeMeters, boolean low) throws Exception;

        void odometer(double km) throws Exception;
    }

    private static final Object LOCK = new Object();
    private static Thread sThread;
    private static volatile boolean sRunning;
    private static Out sOut;

    private LiveData() {}

    static void start(Out out) {
        synchronized (LOCK) {
            sOut = out;
            if (sRunning) return;
            sRunning = true;
            sThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "EvLiveData");
            sThread.setDaemon(true);
            sThread.start();
            Log.i(TAG, "started");
        }
    }

    static void stop() {
        Thread t;
        synchronized (LOCK) {
            sRunning = false;
            t = sThread;
            sThread = null;
            sOut = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "stopped");
        }
    }

    private static void loop() {
        while (sRunning) {
            try {
                Out out;
                synchronized (LOCK) {
                    out = sOut;
                }
                if (out == null) break;
                send(out, EvCar.hev(HEV_DISPLAY_SOC, MAX_AGE_MS), rangeKm(), EvCar.hev(HEV_ODOMETER, MAX_AGE_MS));
            } catch (Throwable t) {
                Log.w(TAG, "send failed: " + t);
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** the range the car shows, km: HSCAN's on the Creta, else the navigation packet's DTE; -1 unknown */
    static int rangeKm() {
        int r = EvCar.hscanRangeKm();
        if (r >= 0 && r < 1000) return r;
        EvCar.pollNav();
        int d = EvCar.dteRaw();
        return d >= 0 && d < 1000 ? d : -1;
    }

    /** One round: socHalf the HEV raw (0.5%), rangeKm, odoRaw the HEV raw (0.1 km); -1 unknown. */
    static void send(Out out, int socHalf, int rangeKm, int odoRaw) throws Exception {
        int[] f = fuel(socHalf, rangeKm);
        if (f != null) out.fuel(f[0], f[1], f[2] != 0);
        if (odoRaw > 0) out.odometer(odoRaw / 10.0);
    }

    /** the sensor list with one more type, if it isn't in it yet */
    static int[] withSensor(int[] types, int type) {
        for (int t : types) if (t == type) return types;
        int[] out = java.util.Arrays.copyOf(types, types.length + 1);
        out[types.length] = type;
        return out;
    }

    /** {percent, range m (-1 unknown), low 0/1} for the fuel sensor, or null without a state of charge */
    static int[] fuel(int socHalf, int rangeKm) {
        if (socHalf < 0 || socHalf > 200) return null;
        int pct = (socHalf + 1) / 2;   // the cluster rounds the half up
        int range = rangeKm >= 0 && rangeKm < 1000 ? rangeKm * 1000 : -1;
        return new int[] { pct, range, pct <= LOW_PCT ? 1 : 0 };
    }
}
