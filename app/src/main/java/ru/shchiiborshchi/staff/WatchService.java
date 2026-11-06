package ru.shchiiborshchi.staff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Работает постоянно, даже при выключенном экране: раз в 5 секунд спрашивает сервер,
 * есть ли новые заявки. Если есть — играет мелодию (как будильник) и показывает уведомление,
 * пока заявку не примут.
 */
public class WatchService extends Service {
    static final String API = "https://" + MainActivity.HOST + "/api/staff.php";
    static final int ID_WATCH = 1, ID_ORDER = 2;
    static final long POLL_MS = 5000, RING_EVERY_MS = 12000;
    static volatile boolean appVisible = false;

    private volatile boolean running;
    private Thread loop;
    private PowerManager.WakeLock wake;
    private long lastRing = 0;
    private int fails = 0;

    static void start(Context c) {
        Intent i = new Intent(c, WatchService.class);
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); } catch (Exception ignored) {}
    }
    static void stop(Context c) { c.stopService(new Intent(c, WatchService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel w = new NotificationChannel("watch", "Работа в фоне", NotificationManager.IMPORTANCE_MIN);
            w.setShowBadge(false);
            nm.createNotificationChannel(w);
            NotificationChannel o = new NotificationChannel("orders2", "Новые заявки", NotificationManager.IMPORTANCE_HIGH);
            o.setSound(null, null); // мелодию играем сами, чтобы повторять её до «Принять»
            o.enableVibration(true);
            o.setVibrationPattern(new long[]{0, 500, 200, 500});
            o.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            o.setBypassDnd(true);
            nm.createNotificationChannel(o);
        }
        Notification n = builder("watch")
                .setContentTitle("Щи и Борщи · Персонал")
                .setContentText("Следим за новыми заявками")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID_WATCH, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shchi:watch");
        wake.acquire();
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (Prefs.token(this).isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        if (loop == null) {
            running = true;
            loop = new Thread(this::run, "shchi-watch");
            loop.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        getSystemService(NotificationManager.class).cancel(ID_ORDER);
        super.onDestroy();
    }

    private void run() {
        while (running) {
            try {
                String token = Prefs.token(this);
                if (token.isEmpty()) { stopSelf(); return; }
                JSONObject r = poll(token);
                if (r == null) { fails++; }
                else {
                    fails = 0;
                    int n = r.optInt("n", 0);
                    if (n > 0) alert(n); else clear();
                }
                if (fails == 12) warnNoNet();
            } catch (Exception ignored) {}
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    private JSONObject poll(String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + token);
            byte[] body = new JSONObject().put("action", "newcount").put("token", token).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            if (code == 401) { Prefs.get(this).edit().putString("token", "").apply(); return null; }
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                return new JSONObject(out.toString("UTF-8"));
            }
        } catch (Exception e) {
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    private void alert(int n) {
        Notification note = builder("orders2")
                .setContentTitle("🔔 Новая заявка" + (n > 1 ? " (" + n + ")" : ""))
                .setContentText("Откройте приложение и нажмите «Принять»")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(openApp())
                .setFullScreenIntent(openApp(), true)
                .build();
        getSystemService(NotificationManager.class).notify(ID_ORDER, note);
        long now = System.currentTimeMillis();
        if (now - lastRing >= RING_EVERY_MS) { lastRing = now; ringOnce(this); }
    }

    private void clear() {
        lastRing = 0;
        getSystemService(NotificationManager.class).cancel(ID_ORDER);
    }

    private void warnNoNet() {
        Notification note = builder("orders2")
                .setContentTitle("Нет связи с сервером заявок")
                .setContentText("Проверьте интернет на этом телефоне")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(ID_ORDER, note);
    }

    /** Мелодия заявки. Играет как будильник: слышно, даже если звук уведомлений выключен. */
    static void ringOnce(Context c) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(c, android.net.Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.chime));
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.prepare();
            mp.start();
        } catch (Exception ignored) {}
        try {
            Vibrator v = (Vibrator) c.getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 150, 400}, -1));
                else v.vibrate(new long[]{0, 400, 150, 400}, -1);
            }
        } catch (Exception ignored) {}
    }

    private Notification.Builder builder(String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
