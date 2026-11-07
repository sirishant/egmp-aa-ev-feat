package hmgapi.mods.evcreta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.hkmc.cluster.NavigationCluster;
import com.hkmc.cluster.NavigationClusterListener;

import hmgapi.hook.Points;

/**
 * Read-only car access shared by EvModelSender (AA sessions) and EvDriveLog
 * (always on), so the two never read the same signal twice in a row.
 *
 * Every call is a getter (docs/DATA-SOURCES.md); nothing here writes to the
 * car. Every method catches everything and returns "unknown" (-1 / NaN /
 * null): this runs inside GPM, where an uncaught exception kills Android
 * Auto. Any thread.
 *
 * Sources:
 *  Car services are found by name through HMG-API's hook map (the service names and
 *  their asInterface on this car), so no binder transaction numbers are needed:
 *  - HEV getHEVStatus(flag), cached per flag so a second caller within maxAgeMs
 *    gets the same value without a binder call
 *  - hscan getCarSpeed(0) and getXEV_Bms_GDS_SOH()
 *  - the nav battery packet via NavigationCluster.requestBatteryInfoReq()
 *    (pack min temp, outside temp, EV_AVLBL_ENRG, DTE), requested at most
 *    every NAV_POLL_MS whoever asks; the listener stays registered for the
 *    life of the process
 *  - DATC IDATCService.getVRClimateStatus(flag), a pure getter over the
 *    service's caches, and isUnitCelsius(unit), called by their interface names
 *  - GPS altitude: LocationManager.getLastKnownLocation("gps"); only the
 *    altitude leaves this class, never position or time.
 */
final class EvCar {
    private static final String TAG = "EvCar";

    static final int HEV_INVALID16 = 0xffff;

    // the car's services, through HMG-API's hook map
    private static final String HEV = "framework.car_services.hev", HEV_AS = "framework.hev_service_stub.as_interface";
    private static final String HCAN = "framework.hscan_adapter.service_name", HCAN_AS = "framework.hscan_service_stub.as_interface";
    // the hscan classes are in a library Android Auto's app doesn't declare: its own loader, as HMG-API's vehicle hub
    private static final String HCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar";
    private static volatile ClassLoader sHcanLoader;
    private static final String DATC = "framework.car_services.datc", DATC_AS = "framework.datc_service_stub.as_interface";
    // DATCFlag values getVRClimateStatus answers (DATCService.smali)
    static final int DATC_TEMP = 0x2;         // driver set temp, display unit
    static final int DATC_BLOWER_LEVEL = 0x5; // MainBlowerDisp
    static final int DATC_BLOWER_MODE = 0x6;  // ModeDisp (vent/floor/def...)
    static final int DATC_AUTO = 0x8;         // AutoDisp
    static final int DATC_AC = 0x9;           // AcDisp
    static final int DATC_OP_STATE = 0xc;     // OpSts
    static final int DATC_TEMP_UNIT = 0xd;    // TempUnit raw


    // Navigation.BATTERY_INFO_RX packet indices (see
    // com.hkmc.micom.protocol.Navigation$BATTERY_INFO_RX).
    private static final int IDX_MIN_BATT_TEMP = 1;   // XEV_MIN_BATT_TEMP, C
    private static final int IDX_EV_AVLBL_ENRG = 2;   // EV_AVLBL_ENRG, 2 Wh steps
    private static final int IDX_OUT_TEMP = 4;        // DATC_OUT_TEMP_SNSR raw
    private static final int IDX_DTE_BASE = 6;        // VCU_DTE_BASE, km
    private static final int IDX_DTE_ADD = 7;         // VCU_DTE_ADD, km
    private static final long NAV_FRESH_MS = 30000;
    private static final long NAV_POLL_MS = 4000;
    private static final long GPS_FRESH_NS = 10000L * 1000000L;

    private static volatile Object sHev;
    private static volatile Object sHcan;
    private static volatile Object sDatc;

    private static final Object CACHE_LOCK = new Object();
    private static final int[] sHevVal = new int[256];
    private static final long[] sHevAt = new long[256];  // 0 = never read

    private static volatile int sSohRaw = Integer.MIN_VALUE;  // until > 0

    private EvCar() {}

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    // ---- HEV -------------------------------------------------------------------

    /**
     * getHEVStatus(flag), reusing a value read less than maxAgeMs ago (by any
     * caller). -1 on failure (failures are not cached).
     */
    static int hev(int flag, long maxAgeMs) {
        if (flag >= 0 && flag < sHevVal.length && maxAgeMs > 0) {
            synchronized (CACHE_LOCK) {
                long at = sHevAt[flag];
                if (at != 0 && now() - at < maxAgeMs) {
                    return sHevVal[flag];
                }
            }
        }
        int v = hevRead(flag);
        if (v >= 0 && flag >= 0 && flag < sHevVal.length) {
            synchronized (CACHE_LOCK) {
                sHevVal[flag] = v;
                sHevAt[flag] = now();
            }
        }
        return v;
    }

    /** IHEVService.getHEVStatus(flag), -1 on failure. */
    private static int hevRead(int flag) {
        try {
            Object s = sHev;
            if (s == null) {
                s = carService(HEV, HEV_AS, null);
                if (s == null) {
                    return -1;
                }
                sHev = s;
            }
            Object v = Points.call(null, "framework.hev_service.hev_status", s, flag);
            return v instanceof Integer ? ((Integer) v).intValue() : -1;
        } catch (Throwable t) {
            sHev = null;
            return -1;
        }
    }

    // ---- hscan -----------------------------------------------------------------

    /** Display speed in km/h (hscan getCarSpeed unit 0), NaN if unknown. */
    static float speedKph() {
        float v = Float.NaN;
        try {
            Object s = hscan();
            Object r = s == null ? null : Points.call(s.getClass().getClassLoader(), "framework.hscan_service.car_speed", s, (byte) 0);
            if (r instanceof Float) {
                v = ((Float) r).floatValue();
            }
        } catch (Throwable t) {
            sHcan = null;
        }
        return Float.isNaN(v) || v < 0 || v > 400 ? Float.NaN : v;
    }

    /** HSCAN's battery percentage (CarPlay EV routing's charge source on other cars), NaN if unknown. */
    static float hscanSocPct() {
        float v = Float.NaN;
        try {
            Object s = hscan();
            Object r = s == null ? null : Points.call(s.getClass().getClassLoader(), "framework.hscan_service.battery_percent", s);
            if (r instanceof Float) {
                v = ((Float) r).floatValue();
            }
        } catch (Throwable t) {
            sHcan = null;
        }
        return Float.isNaN(v) || v < 0 || v > 100 ? Float.NaN : v;
    }

    /** HSCAN's 12 V battery charge (the battery sensor's estimate, getBatSoc), -1 if unknown. */
    static int battery12vSoc() {
        int v = -1;
        try {
            Object s = hscan();
            Object r = s == null ? null : Points.call(s.getClass().getClassLoader(), "framework.hscan_service.battery_12v_soc", s);
            if (r instanceof Integer) {
                v = ((Integer) r).intValue();
            }
        } catch (Throwable t) {
            sHcan = null;
        }
        return v < 0 || v > 100 ? -1 : v;
    }

    /** HSCAN's range in km (CarPlay EV routing's range source), -1 if unknown. */
    static int hscanRangeKm() {
        int v = -1;
        try {
            Object s = hscan();
            Object r = s == null ? null : Points.call(s.getClass().getClassLoader(), "framework.hscan_service.drive_range", s, (byte) 0);
            if (r instanceof Integer) {
                v = ((Integer) r).intValue();
            }
        } catch (Throwable t) {
            sHcan = null;
        }
        return v >= 0 && v < 2000 ? v : -1;
    }

    /** BMS SOH raw; re-read until it reports a value (it reads 0 at boot). */
    static int sohRaw() {
        int s = sSohRaw;
        if (s <= 0) {
            int v = -1;
            try {
                Object svc = hscan();
                Object r = svc == null ? null : Points.call(svc.getClass().getClassLoader(), "framework.hscan_service.hv_health", svc);
                if (r instanceof Integer) {
                    v = ((Integer) r).intValue();
                }
            } catch (Throwable t) {
                sHcan = null;
            }
            if (v != s) {
                Log.i(TAG, "BMS SOH raw: " + v);
            }
            sSohRaw = v;
            s = v;
        }
        return s;
    }

    private static Object hscan() throws Exception {
        Object s = sHcan;
        if (s == null) {
            ClassLoader cl = sHcanLoader;
            if (cl == null) {
                cl = new dalvik.system.PathClassLoader(HCAN_JAR, EvCar.class.getClassLoader());
                sHcanLoader = cl;
            }
            s = carService(HCAN, HCAN_AS, cl);
            sHcan = s;
        }
        return s;
    }

    // ---- nav battery packet ----------------------------------------------------

    private static final Object NAV_LOCK = new Object();
    private static NavigationCluster sNavCluster;
    private static long sNavPolledAt;
    private static volatile long sNavPacketAt;
    private static volatile int sPackTempC = Integer.MIN_VALUE;
    private static volatile int sOutTempRaw = Integer.MIN_VALUE;
    private static volatile int sAvlblEnrg = -1;
    private static volatile int sDteBase = -1;
    private static volatile int sDteAdd = -1;

    private static final NavigationClusterListener sNavListener =
            new NavigationClusterListener() {
                @Override
                public void onHandleBatteryInfoResp(int[] d) {
                    try {
                        if (d == null || d.length <= IDX_DTE_ADD) {
                            return;
                        }
                        sPackTempC = (byte) d[IDX_MIN_BATT_TEMP];
                        sOutTempRaw = d[IDX_OUT_TEMP] & 0xff;
                        sAvlblEnrg = d[IDX_EV_AVLBL_ENRG];
                        sDteBase = d[IDX_DTE_BASE];
                        sDteAdd = d[IDX_DTE_ADD];
                        sNavPacketAt = now();
                    } catch (Throwable t) {
                        // never disturb GPM
                    }
                }
            };

    /** Ask for a fresh nav battery packet unless one was asked for recently. */
    static void pollNav() {
        try {
            synchronized (NAV_LOCK) {
                long n = now();
                if (sNavPolledAt != 0 && n - sNavPolledAt < NAV_POLL_MS) {
                    return;
                }
                sNavPolledAt = n;
                if (sNavCluster == null) {
                    Context ctx = appContext();
                    if (ctx == null) {
                        return;
                    }
                    NavigationCluster nc = new NavigationCluster(ctx);
                    nc.addListener(sNavListener);
                    sNavCluster = nc;
                    Log.i(TAG, "nav cluster listener registered");
                }
                try {
                    sNavCluster.requestBatteryInfoReq();
                } catch (Throwable t) {
                    // keep the listener; retry next poll
                    Log.w(TAG, "nav battery request failed: " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "nav cluster unavailable: " + t);
        }
    }

    static boolean navFresh() {
        long at = sNavPacketAt;
        return at != 0 && now() - at < NAV_FRESH_MS;
    }

    /** XEV_MIN_BATT_TEMP from a fresh packet, Integer.MIN_VALUE if unknown. */
    static int packMinC() {
        return navFresh() ? sPackTempC : Integer.MIN_VALUE;
    }

    /** DATC_OUT_TEMP_SNSR raw from a fresh packet, -1 if unknown. */
    static int outTempRaw() {
        int raw = sOutTempRaw;
        return navFresh() && raw >= 0 ? raw : -1;
    }

    /** Outside temp in C (raw x 0.5 - 40), NaN if unknown/invalid. */
    static float outsideTempC() {
        int raw = outTempRaw();
        // 0 is -40 C: an empty packet, not a reading (seen on the Creta)
        if (raw <= 0 || raw >= 0xfe) {
            return Float.NaN;
        }
        return raw * 0.5f - 40f;
    }

    /** EV_AVLBL_ENRG from a fresh packet, -1 if unknown. */
    static int navAvlblEnrg() {
        return navFresh() ? sAvlblEnrg : -1;
    }

    /** VCU_DTE_BASE + VCU_DTE_ADD (km) from a fresh packet, -1 if unknown. */
    static int dteRaw() {
        int b = sDteBase;
        return navFresh() && b >= 0 ? b + sDteAdd : -1;
    }

    static String navSummary() {
        return "pack=" + (navFresh() ? String.valueOf(sPackTempC) : "?")
                + "C, out raw " + sOutTempRaw + ", dte=" + sDteBase + "+" + sDteAdd + "km";
    }

    // ---- energy ----------------------------------------------------------------

    /**
     * BMS remaining usable energy raw (HEV 0x31 ENERGYMAP_1000MS, 2 Wh steps;
     * falls back to a fresh nav packet's EV_AVLBL_ENRG, the same signal).
     * -1 if neither is valid.
     */
    static int energyRaw(long maxAgeMs, int maxRaw) {
        int raw = hev(0x31, maxAgeMs);
        if (raw <= 0 || raw >= HEV_INVALID16) {
            raw = navAvlblEnrg();
        }
        if (raw <= 0 || raw >= HEV_INVALID16 || raw > maxRaw) {
            return -1;
        }
        return raw;
    }

    // ---- DATC (climate) --------------------------------------------------------

    private static Object datc() throws Exception {
        Object s = sDatc;
        if (s == null) {
            s = carService(DATC, DATC_AS, null);
            sDatc = s;
        }
        return s;
    }

    /** IDATCService.getVRClimateStatus(flag); NaN on failure or invalid (< 0). */
    /**
     * Climate getter results are cached for CLIMATE_CACHE_MS: each call makes
     * the DATC service write a debug line to logcat, and at ~7 calls per row
     * that would flush the head unit's ~1 min log buffer. Settings change
     * rarely, so 30 s resolution is plenty.
     */
    private static final long CLIMATE_CACHE_MS = 30000;
    private static final java.util.HashMap<Integer, Float> sClimate =
            new java.util.HashMap<Integer, Float>();
    private static final java.util.HashMap<Integer, Long> sClimateAt =
            new java.util.HashMap<Integer, Long>();

    static synchronized float climate(int flag) {
        long now = android.os.SystemClock.uptimeMillis();
        Integer k = Integer.valueOf(flag);
        Long at = sClimateAt.get(k);
        if (at != null && now - at.longValue() < CLIMATE_CACHE_MS) {
            return sClimate.get(k).floatValue();
        }
        float v = climateUncached(flag);
        sClimate.put(k, Float.valueOf(v));
        sClimateAt.put(k, Long.valueOf(now));
        return v;
    }

    private static float climateUncached(int flag) {
        try {
            Object s = datc();
            if (s == null) {
                return Float.NaN;
            }
            Object r = s.getClass().getMethod("getVRClimateStatus", int.class).invoke(s, flag);
            float v = r instanceof Float ? ((Float) r).floatValue() : Float.NaN;
            return v < 0 || Float.isNaN(v) || Float.isInfinite(v) ? Float.NaN : v;
        } catch (Throwable t) {
            sDatc = null;
            return Float.NaN;
        }
    }

    /** IDATCService.isUnitCelsius(unit): 1 yes, 0 no, -1 unknown. */
    static int climateUnitIsCelsius(int unitRaw) {
        try {
            Object s = datc();
            if (s == null) {
                return -1;
            }
            Object r = s.getClass().getMethod("isUnitCelsius", int.class).invoke(s, unitRaw);
            if (r instanceof Integer) {
                return ((Integer) r).intValue() != 0 ? 1 : 0;
            }
            return r instanceof Boolean ? (((Boolean) r).booleanValue() ? 1 : 0) : -1;
        } catch (Throwable t) {
            sDatc = null;
            return -1;
        }
    }

    // ---- BCM drive mode (passive listener) --------------------------------------


    /**
     * Always -1 (unknown). A BCM listener must answer every IBCMListener
     * callback correctly: on the Creta, onDigitalKeyDataChanged expects a byte
     * array back, and an empty reply throws inside BCMService and takes
     * system_server down. The drive mode only fed a log column, so no listener.
     */
    static int driveMode() {
        return -1;
    }

    // ---- GPS altitude ----------------------------------------------------------

    /**
     * Altitude (m) of the last GPS fix if it is under 10 s old and has one,
     * NaN otherwise. Only the altitude is used; the position and time of the
     * fix never leave this method.
     */
    static double gpsAltitudeM() {
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return Double.NaN;
            }
            LocationManager lm = (LocationManager) ctx.getSystemService(
                    Context.LOCATION_SERVICE);
            if (lm == null) {
                return Double.NaN;
            }
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null || !l.hasAltitude()) {
                return Double.NaN;
            }
            long age = SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos();
            if (age < 0 || age > GPS_FRESH_NS) {
                return Double.NaN;
            }
            return l.getAltitude();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    // ---- helpers ---------------------------------------------------------------

    /** a car service's interface: its name and asInterface from HMG-API's hook map, classes from cl (null: the framework) */
    private static Object carService(String namePoint, String asInterfacePoint, ClassLoader cl) throws Exception {
        Object name = Points.get(cl, namePoint, null);
        IBinder b = name == null ? null : service(String.valueOf(name));
        return b == null ? null : Points.call(cl, asInterfacePoint, null, b);
    }

    private static IBinder service(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, name);
    }

    static Context appContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
