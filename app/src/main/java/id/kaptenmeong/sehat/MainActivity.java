package id.kaptenmeong.sehat;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kapten Meong Sehat: halaman lokal (assets/index.html) di dalam WebView,
 * dengan jembatan ke fitur HP: sensor langkah, GPS, pengingat, widget,
 * cadangan data, Health Connect, suara pelatih, dan getar.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_START = 7;
    private static final int REQ_GPS = 8;
    private static final int REQ_NOTIF = 9;
    private static final int REQ_EXPORT = 30;
    private static final int REQ_IMPORT = 31;

    private WebView web;
    private SensorManager sensorManager;
    private Sensor stepCounter;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingExport = null;
    private long lastWidgetUpdate = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#FFF3DC"));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#FFF3DC"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new StepBridge(), "AndroidSteps");
        web.addJavascriptInterface(new GpsBridge(), "AndroidGps");
        web.addJavascriptInterface(new StoreBridge(), "AndroidStore");
        web.addJavascriptInterface(new NotifyBridge(), "AndroidNotify");
        web.addJavascriptInterface(new BackupBridge(), "AndroidBackup");
        web.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
        web.addJavascriptInterface(new HealthBridge(this, web), "AndroidHealth");
        web.addJavascriptInterface(new ShareBridge(), "AndroidShare");
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("id", "ID"));
                ttsReady = true;
            }
        });

        List<String> ask = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29 && stepCounter != null && !hasActivityPermission()) {
            ask.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifPermission()) {
            ask.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQ_START);

        Reminders.scheduleAll(this);
        VehicleReceiver.register(this);
    }

    private boolean hasActivityPermission() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startListening() {
        if (sensorManager != null && stepCounter != null && hasActivityPermission()) {
            sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        MeongWidget.updateAll(this);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startListening();
        VehicleReceiver.register(this);
        notifyPage();
    }

    private void notifyPage() {
        js("window.__sensorChanged && window.__sensorChanged()");
    }

    private void js(String code) {
        if (web != null) web.post(() -> web.evaluateJavascript(code, null));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        StepStore.record(this, (long) event.values[0]);
        long now = System.currentTimeMillis();
        if (now - lastWidgetUpdate > 60000) {
            lastWidgetUpdate = now;
            MeongWidget.updateAll(this);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == HealthBridge.REQ_HC) {
            notifyPage();
            return;
        }
        if (requestCode == REQ_EXPORT) {
            boolean ok = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    if (os != null) {
                        os.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception ignored) { }
            }
            pendingExport = null;
            js("window.__backupDone && window.__backupDone(" + ok + ")");
            return;
        }
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            StringBuilder sb = new StringBuilder();
            try (InputStream is = getContentResolver().openInputStream(data.getData())) {
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
            } catch (Exception ignored) { }
            js("window.__restore && window.__restore(" + JSONObject.quote(sb.toString()) + ")");
        }
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__back && window.__back()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) finish();
        });
    }

    /* ===================== Jembatan JavaScript ===================== */

    public class StepBridge {
        @JavascriptInterface
        public boolean available() {
            return stepCounter != null;
        }

        @JavascriptInterface
        public boolean permitted() {
            return hasActivityPermission();
        }

        @JavascriptInterface
        public int todaySteps() {
            return StepStore.todaySteps(MainActivity.this);
        }

        @JavascriptInterface
        public String history() {
            return StepStore.history(MainActivity.this).toString();
        }

        @JavascriptInterface
        public String vehicles() {
            return StepStore.vehicles(MainActivity.this).toString();
        }

        @JavascriptInterface
        public boolean vehicleAvailable() {
            return VehicleReceiver.available(MainActivity.this);
        }

        @JavascriptInterface
        public boolean vehicleOn() {
            return VehicleReceiver.enabled(MainActivity.this);
        }

        @JavascriptInterface
        public boolean inVehicle() {
            return StepStore.inVehicle(MainActivity.this);
        }

        @JavascriptInterface
        public void setVehicle(boolean on) {
            StepStore.prefs(MainActivity.this).edit().putBoolean("vehOn", on).apply();
            VehicleReceiver.register(MainActivity.this);
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_START);
                }
            });
        }
    }

    public class GpsBridge {
        private LocationManager lm() {
            return (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }

        @JavascriptInterface
        public boolean available() {
            LocationManager lm = lm();
            return lm != null && lm.getAllProviders().contains(LocationManager.GPS_PROVIDER);
        }

        @JavascriptInterface
        public boolean enabled() {
            LocationManager lm = lm();
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        }

        @JavascriptInterface
        public boolean permitted() {
            return hasLocationPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GPS));
        }

        @JavascriptInterface
        public void openLocationSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void start(boolean reset) {
            if (!hasLocationPermission()) return;
            if (reset) GpsService.reset();
            runOnUiThread(() -> {
                try {
                    startForegroundService(new Intent(MainActivity.this, GpsService.class));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void pause() {
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, GpsService.class)));
        }

        @JavascriptInterface
        public String state() {
            return GpsService.json();
        }
    }

    /** Salinan data di penyimpanan aplikasi (ikut cadangan otomatis Android) + ringkasan untuk widget. */
    public class StoreBridge {
        private SharedPreferences sp() {
            return getSharedPreferences("store", Context.MODE_PRIVATE);
        }

        @JavascriptInterface
        public void save(String json) {
            sp().edit().putString("state", json).apply();
        }

        @JavascriptInterface
        public String load() {
            return sp().getString("state", "");
        }

        @JavascriptInterface
        public void summary(String json) {
            sp().edit().putString("summary", json).apply();
            MeongWidget.updateAll(MainActivity.this);
        }
    }

    public class NotifyBridge {
        @JavascriptInterface
        public void configure(String json) {
            Reminders.configure(MainActivity.this, json);
        }

        @JavascriptInterface
        public boolean permitted() {
            return hasNotifPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                }
            });
        }

        @JavascriptInterface
        public void test() {
            Reminders.show(MainActivity.this, 9, "Ahoy! 🐾", "Pengingat Kapten Meong sudah aktif.");
        }
    }

    public class BackupBridge {
        @JavascriptInterface
        public void exportFile(String json, String fileName) {
            pendingExport = json;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(i, REQ_EXPORT);
                } catch (Exception e) {
                    pendingExport = null;
                    js("window.__backupDone && window.__backupDone(false)");
                }
            });
        }

        @JavascriptInterface
        public void importFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_IMPORT);
                } catch (Exception ignored) { }
            });
        }
    }

    public class VoiceBridge {
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null && ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "meong");
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(VibrationEffect.createOneShot(Math.max(10, Math.min(ms, 1000)), VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } catch (Exception ignored) { }
        }
    }

    /** Bagikan kartu "Log Pelayaran" ke Instagram, WhatsApp, dan aplikasi lain. */
    public class ShareBridge {
        @JavascriptInterface
        public void shareImage(String base64Png, String caption) {
            try {
                byte[] bytes = Base64.decode(base64Png, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "kapten-meong-" + System.currentTimeMillis() + ".png");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(bytes);
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/png");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_TEXT, caption);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Bagikan log pelayaran");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(chooser));
            } catch (Exception e) {
                js("window.__shareFail && window.__shareFail()");
            }
        }

        @JavascriptInterface
        public boolean canSave() {
            return Build.VERSION.SDK_INT >= 29;
        }

        @JavascriptInterface
        public boolean saveImage(String base64Png) {
            if (Build.VERSION.SDK_INT < 29) return false;
            try {
                byte[] bytes = Base64.decode(base64Png, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "kapten-meong-" + System.currentTimeMillis() + ".png");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KaptenMeong");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) return false;
                    os.write(bytes);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
