package id.kaptenmeong.sehat;

import android.content.Context;
import android.content.SharedPreferences;

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
            if (!today.equals(lastDate)) {
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
}
