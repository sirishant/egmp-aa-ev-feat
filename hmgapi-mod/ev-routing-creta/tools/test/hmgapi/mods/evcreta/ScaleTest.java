package hmgapi.mods.evcreta;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for EvModelSender.scaleTemplate: the charge curve (4.1) and
 * charge-time tables (8.1) follow the learned usable capacity, so the taper
 * stays at the same SOC. Re-parses the scaled protobuf with a tiny reader
 * (every nested length must add up) and checks the values.
 *
 * Regression (2026-10-29): Maps showed 28 -> 100% in 16 min on a 180 kW
 * charger. Two causes: the tables were on the 77.4 kWh axis with 62.9 kWh in
 * 1.4, and 8.1 was emitted as (energy, seconds from empty) while the server
 * reads (headroom to full, seconds to full), so our fast low-SOC bulk became
 * the last stretch to 100%. Times here are read the server's way (toFull).
 */
public final class ScaleTest {
    static final int REF = EvModelTemplate.CAPACITY_WH;
    static int passed;
    static int failed;

    static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    // ---- minimal protobuf reader ------------------------------------------

    static final class F {
        int num;
        int wire;
        long v;      // varint value
        int off;     // value offset (length-delimited: payload start)
        int len;     // payload length
    }

    static long[] varint(byte[] b, int off) {  // {value, next offset}
        long v = 0;
        int shift = 0;
        while (true) {
            int x = b[off++] & 0xff;
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                return new long[] {v, off};
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalStateException("varint too long");
            }
        }
    }

    static List<F> parse(byte[] b, int start, int end) {
        List<F> out = new ArrayList<F>();
        int off = start;
        while (off < end) {
            long[] t = varint(b, off);
            off = (int) t[1];
            F f = new F();
            f.num = (int) (t[0] >>> 3);
            f.wire = (int) (t[0] & 7);
            if (f.wire == 0) {
                long[] v = varint(b, off);
                f.v = v[0];
                f.off = off;
                off = (int) v[1];
            } else if (f.wire == 2) {
                long[] l = varint(b, off);
                f.off = (int) l[1];
                f.len = (int) l[0];
                off = f.off + f.len;
            } else if (f.wire == 5) {
                f.off = off;
                off += 4;
            } else if (f.wire == 1) {
                f.off = off;
                off += 8;
            } else {
                throw new IllegalStateException("wire type " + f.wire);
            }
            out.add(f);
        }
        if (off != end) {
            throw new IllegalStateException("message overruns its length");
        }
        return out;
    }

    static List<F> sub(byte[] b, F f) {
        return parse(b, f.off, f.off + f.len);
    }

    static F one(List<F> fs, int num) {
        F r = null;
        for (F f : fs) {
            if (f.num == num) {
                r = f;
            }
        }
        return r;
    }

    /** The VehicleEnergyModel inside [80 03][BA 01 len][model]. */
    static List<F> model(byte[] msg) {
        if ((msg[0] & 0xff) != 0x80 || msg[1] != 3) {
            throw new IllegalStateException("not a SensorBatch message");
        }
        List<F> batch = parse(msg, 2, msg.length);
        if (batch.size() != 1 || batch.get(0).num != 23) {
            throw new IllegalStateException("expected one field 23");
        }
        return sub(msg, batch.get(0));
    }

    /** 4.1 as {energy_wh, power_w} pairs. */
    static List<long[]> curve(byte[] msg) {
        List<long[]> out = new ArrayList<long[]>();
        for (F p : sub(msg, one(model(msg), 4))) {
            if (p.num == 1) {
                List<F> ep = sub(msg, p);
                out.add(new long[] {one(ep, 1).v, one(ep, 2).v});
            }
        }
        return out;
    }

    /** 8.1 rows: charger W -> list of {energy_wh, seconds}. */
    static List<Object[]> tables(byte[] msg) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (F row : sub(msg, one(model(msg), 8))) {
            if (row.num != 1) {
                continue;
            }
            long charger = -1;
            List<long[]> pts = new ArrayList<long[]>();
            for (F f : sub(msg, row)) {
                if (f.num == 1) {
                    charger = f.v;
                } else if (f.num == 2) {
                    List<F> et = sub(msg, f);
                    pts.add(new long[] {one(et, 1).v, one(et, 2).v});
                }
            }
            out.add(new Object[] {Long.valueOf(charger), pts});
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<long[]> row(byte[] msg, long charger) {
        for (Object[] r : tables(msg)) {
            if (((Long) r[0]).longValue() == charger) {
                return (List<long[]>) r[1];
            }
        }
        throw new IllegalStateException("no row " + charger);
    }

    /**
     * Seconds to full with h Wh of headroom left. 8.1 points are (headroom
     * to full, seconds to full) in descending headroom, like Ford's DC rows;
     * (0, 0) is implicit (zero varints aren't encoded).
     */
    static double toFull(List<long[]> pts, double h) {
        long[] b = new long[] {0, 0};
        for (int i = pts.size() - 1; i >= 0; i--) {
            long[] a = pts.get(i);
            if (h <= a[0]) {
                return b[1] + (a[1] - b[1]) * (h - b[0]) / (double) (a[0] - b[0]);
            }
            b = a;
        }
        return pts.get(0)[1];
    }

    /** Minutes from fromPct to toPct of capWh on this row, the way the
     *  server reads it: T(headroom at from) - T(headroom at to). */
    static double minutes(byte[] msg, long charger, int capWh, double fromPct, double toPct) {
        List<long[]> pts = row(msg, charger);
        return (toFull(pts, capWh * (100 - fromPct) / 100)
                - toFull(pts, capWh * (100 - toPct) / 100)) / 60;
    }

    static boolean near(double got, double want, double frac) {
        boolean ok = Math.abs(got - want) <= want * frac;
        if (!ok) {
            System.out.printf("  got %.2f, want %.2f +-%.0f%%%n", got, want, frac * 100);
        }
        return ok;
    }

    // ---- the checks -------------------------------------------------------------

    static void checkScaled(byte[] tmpl, byte[] msg, int capWh) {
        String c = " (cap " + capWh + ")";
        check(msg.length == tmpl.length, "same length" + c);
        List<long[]> c0 = curve(tmpl);
        List<long[]> c1 = curve(msg);
        check(c0.size() == c1.size() && c1.size() >= 5, "4.1 point count" + c);
        boolean powers = true;
        boolean shape = true;
        boolean le = true;
        for (int i = 0; i < c1.size(); i++) {
            powers &= c0.get(i)[1] == c1.get(i)[1];
            double soc0 = c0.get(i)[0] / (double) REF;
            double soc1 = c1.get(i)[0] / (double) capWh;
            shape &= Math.abs(soc0 - soc1) < 1e-4;
            le &= c1.get(i)[0] <= capWh;
        }
        check(powers, "4.1 powers unchanged" + c);
        check(shape, "4.1 keeps its SOC shape" + c);
        check(le, "4.1 energy <= capacity" + c);
        check(c1.get(c1.size() - 1)[0] == capWh, "4.1 ends at capacity" + c);

        List<Object[]> t0 = tables(tmpl);
        List<Object[]> t1 = tables(msg);
        check(t0.size() == t1.size() && t1.size() > 0, "8.1 row count" + c);
        double k = capWh / (double) REF;
        for (int r = 0; r < t1.size(); r++) {
            long charger = ((Long) t1.get(r)[0]).longValue();
            String rc = " row " + charger + c;
            check(charger == ((Long) t0.get(r)[0]).longValue(), "charger power unchanged" + rc);
            @SuppressWarnings("unchecked")
            List<long[]> p0 = (List<long[]>) t0.get(r)[1];
            @SuppressWarnings("unchecked")
            List<long[]> p1 = (List<long[]>) t1.get(r)[1];
            check(p0.size() == p1.size(), "point count" + rc);
            boolean ok = true;
            boolean mono = true;
            boolean timeK = true;
            for (int i = 0; i < p1.size(); i++) {
                ok &= p1.get(i)[0] <= capWh;
                if (i > 0) {  // descending headroom, descending time to full
                    mono &= p1.get(i)[0] < p1.get(i - 1)[0] && p1.get(i)[1] < p1.get(i - 1)[1];
                }
                // every seconds value scales by k (rounding only)
                double want = p0.get(i)[1] * k;
                double got = p1.get(i)[1];
                timeK &= Math.abs(got - want) <= 1;
            }
            check(ok, "8.1 headroom <= capacity" + rc);
            check(mono, "8.1 monotonic decreasing" + rc);
            check(timeK, "8.1 seconds ~ k x reference" + rc);
            check(Math.abs(p1.get(0)[0] - capWh) <= 1, "8.1 first headroom = capacity" + rc);
            long last = p1.get(p1.size() - 1)[0];
            check(last > 0 && last <= capWh / 100, "8.1 last headroom > 0 and <= 1%" + rc);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] tmpl = java.util.Base64.getMimeDecoder().decode(EvModelTemplate.TEMPLATE_B64);
        check(EvModelSender.checkPlaceholders(tmpl), "template offsets match");

        // k = 1 is the identity
        byte[] same = EvModelSender.scaleTemplate(tmpl, REF);
        check(java.util.Arrays.equals(same, tmpl), "k = 1 leaves the template unchanged");

        // the Creta: 47.3 kWh is what the car has learned so far
        int cap = 47300;
        byte[] msg = EvModelSender.scaleTemplate(tmpl, cap);
        checkScaled(tmpl, msg, cap);
        check(!EvModelSender.checkPlaceholders(msg), "scaled bytes differ from the template");
        check(java.util.Arrays.equals(EvModelSender.scaleTemplate(tmpl, cap), msg),
                "deterministic");

        // Charging sessions from the owner's log, at the reference 48 kWh, on a
        // charger above the car's limit. The model was fitted to these, so a
        // change that breaks the tables shows up here. Billed energy is 55 kWh
        // per 100%, so minutes = % * 0.55 * 60 / average kW.
        check(near(minutes(tmpl, 150000, REF, 11, 72), 61 * 0.55 * 60 / 62, 0.10), "11->72% at 62 kW avg");
        check(near(minutes(tmpl, 150000, REF, 10, 84), 74 * 0.55 * 60 / 51, 0.10), "10->84% at 51 kW avg");
        check(near(minutes(tmpl, 150000, REF, 81, 100), 46.6, 0.10), "81->100% in 46.6 min");
        // the car's limit is below these chargers, so they all read the same
        check(near(minutes(tmpl, 120000, REF, 10, 80), minutes(tmpl, 150000, REF, 10, 80), 0.01),
                "120 kW row equals 150 kW row");
        // a slower charger never beats a faster one
        check(minutes(tmpl, 50000, REF, 10, 80) > minutes(tmpl, 100000, REF, 10, 80), "50 kW slower");
        check(minutes(tmpl, 7400, REF, 10, 80) > minutes(tmpl, 50000, REF, 10, 80), "7.4 kW slower");
        // same power at the same SOC: a smaller pack takes k times as long
        double k = cap / (double) REF;
        check(near(minutes(msg, 150000, cap, 10, 80), minutes(tmpl, 150000, REF, 10, 80) * k, 0.02),
                "scaled minutes follow capacity");

        // the largest capacity the sender accepts (CAP_MAX_WH): no overflow
        checkScaled(tmpl, EvModelSender.scaleTemplate(tmpl, 90000), 90000);
        checkScaled(tmpl, EvModelSender.scaleTemplate(tmpl, 30000), 30000);

        System.out.println("passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
