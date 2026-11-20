package id.kaptenmeong.sehat;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Locale;

/** Widget layar utama: langkah hari ini, air, dan timer puasa. */
public class MeongWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context);
    }

    private static String fmt(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', '.');
    }

    static void updateAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, MeongWidget.class));
            if (ids == null || ids.length == 0) return;

            JSONObject s = Reminders.summary(c);
            boolean fresh = StepStore.today().equals(s.optString("date"));
            int steps = Math.max(StepStore.todaySteps(c), fresh ? s.optInt("steps", 0) : 0);
            int goal = Math.max(1, s.optInt("stepGoal", 8000));
            int water = fresh ? s.optInt("water", 0) : 0;
            int waterGoal = s.optInt("waterGoal", 8);
            String rank = s.optString("rank", "");

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_title, s.optString("mood", "😺") + " Kapten Meong" + (rank.isEmpty() ? "" : " · " + rank));
            v.setTextViewText(R.id.w_steps, fmt(steps) + " langkah");
            v.setTextViewText(R.id.w_goal, steps >= goal ? "Target tercapai! 🏝️" : "target " + fmt(goal) + " · sisa " + fmt(goal - steps));
            v.setProgressBar(R.id.w_bar, goal, Math.min(steps, goal), false);
            v.setTextViewText(R.id.w_water, "💧 " + water + "/" + waterGoal + " gelas");

            long fastStart = s.optLong("fastStart", 0);
            if (fastStart > 0) {
                long base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - fastStart);
                v.setChronometer(R.id.w_fast, base, "⏳ Puasa %s", true);
                v.setViewVisibility(R.id.w_fast, View.VISIBLE);
                v.setViewVisibility(R.id.w_eat, View.GONE);
            } else {
                v.setChronometer(R.id.w_fast, SystemClock.elapsedRealtime(), null, false);
                v.setViewVisibility(R.id.w_fast, View.GONE);
                v.setViewVisibility(R.id.w_eat, View.VISIBLE);
            }

            Intent open = new Intent(c, MainActivity.class);
            PendingIntent p = PendingIntent.getActivity(c, 50, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            v.setOnClickPendingIntent(R.id.w_root, p);
            m.updateAppWidget(ids, v);
        } catch (Exception ignored) { }
    }
}
