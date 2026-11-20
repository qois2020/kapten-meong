package id.kaptenmeong.sehat;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Menyimpan hitungan sensor langkah per hari.
 * Sensor memberi total sejak HP menyala; "base" adalah nilai sensor di awal hari.
 */
final class StepStore {

    private StepStore() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("langkah", Context.MODE_PRIVATE);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static synchronized void record(Context c, long value) {
        SharedPreferences p = prefs(c);
        String today = today();
        String lastDate = p.getString("lastDate", "");
        long last = p.getLong("last", -1);
        long base = p.getLong("base", 0);
        JSONObject hist = history(c);
        try {
            if (lastDate.isEmpty() || last < 0) {
                // Pembacaan pertama setelah aplikasi dipasang: mulai dari 0,
                // jangan hitung langkah sejak HP terakhir dinyalakan.
                base = value;
            } else if (!today.equals(lastDate)) {
                // Tutup hari sebelumnya, lalu mulai hari baru dari pembacaan terakhir.
                if (!lastDate.isEmpty() && last >= 0) hist.put(lastDate, Math.max(0, last - base));
                base = (last >= 0 && value >= last) ? last : 0;
            } else if (last >= 0 && value < last) {
                // HP habis restart: sensor kembali ke nol, lanjutkan hitungan hari ini.
                base = -(last - base);
            }
            hist.put(today, Math.max(0, value - base));
            if (hist.length() > 60) {
                List<String> keys = new ArrayList<>();
                Iterator<String> it = hist.keys();
                while (it.hasNext()) keys.add(it.next());
                Collections.sort(keys);
                for (int i = 0; i < keys.size() - 60; i++) hist.remove(keys.get(i));
            }
        } catch (Exception ignored) { }
        p.edit().putString("lastDate", today).putLong("last", value).putLong("base", base)
                .putString("hist", hist.toString()).apply();
    }

    static JSONObject history(Context c) {
        try {
            return new JSONObject(prefs(c).getString("hist", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static int todaySteps(Context c) {
        SharedPreferences p = prefs(c);
        if (!today().equals(p.getString("lastDate", ""))) return 0;
        return (int) Math.max(0, p.getLong("last", 0) - p.getLong("base", 0));
    }

    interface Reading {
        void got(long value);
    }

    /** Baca sensor sekali (dipakai di latar belakang), simpan, lalu panggil cb atau fail. */
    static void readOnce(final Context c, final Reading cb, final Runnable fail) {
        final SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        boolean allowed = Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        if (sensor == null || !allowed) {
            fail.run();
            return;
        }
        final boolean[] done = {false};
        final SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (done[0]) return;
                done[0] = true;
                sm.unregisterListener(this);
                long v = (long) e.values[0];
                record(c, v);
                cb.got(v);
            }

            @Override
            public void onAccuracyChanged(Sensor s, int a) { }
        };
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                sm.unregisterListener(listener);
                fail.run();
            }
        }, 6000);
    }

    /* ---------- Deteksi naik kendaraan (motor, mobil, sepeda) ---------- */

    static synchronized void vehicleEnter(Context c, String type) {
        prefs(c).edit().putLong("vehStart", System.currentTimeMillis())
                .putInt("vehStartSteps", todaySteps(c))
                .putString("vehDate", today())
                .putString("vehType", type).apply();
    }

    static synchronized void vehicleExit(Context c) {
        SharedPreferences p = prefs(c);
        long start = p.getLong("vehStart", 0);
        if (start <= 0) return;
        String t = today();
        int now = todaySteps(c);
        int delta = t.equals(p.getString("vehDate", "")) ? now - p.getInt("vehStartSteps", 0) : now;
        delta = Math.max(0, delta);
        int mins = (int) ((System.currentTimeMillis() - start) / 60000L);
        if (mins > 360) delta = 0; // perjalanan tidak wajar, abaikan
        try {
            JSONObject veh = vehicles(c);
            JSONObject day = veh.optJSONObject(t);
            if (day == null) day = new JSONObject();
            day.put("s", day.optInt("s", 0) + delta);
            day.put("n", day.optInt("n", 0) + 1);
            day.put("m", day.optInt("m", 0) + mins);
            day.put("t", p.getString("vehType", "kendaraan"));
            veh.put(t, day);
            if (veh.length() > 60) {
                List<String> keys = new ArrayList<>();
                Iterator<String> it = veh.keys();
                while (it.hasNext()) keys.add(it.next());
                Collections.sort(keys);
                for (int i = 0; i < keys.size() - 60; i++) veh.remove(keys.get(i));
            }
            p.edit().putString("veh", veh.toString()).putLong("vehStart", 0).apply();
        } catch (Exception e) {
            p.edit().putLong("vehStart", 0).apply();
        }
    }

    static JSONObject vehicles(Context c) {
        try {
            return new JSONObject(prefs(c).getString("veh", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean inVehicle(Context c) {
        long start = prefs(c).getLong("vehStart", 0);
        return start > 0 && System.currentTimeMillis() - start < 6L * 3600000L;
    }
}
