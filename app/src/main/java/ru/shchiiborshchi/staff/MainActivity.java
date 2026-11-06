package ru.shchiiborshchi.staff;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Окно приложения: внутри обычное приложение сотрудников с сайта. */
public class MainActivity extends Activity {
    static final String HOST = "shchiiborshchi.ru";
    static final String URL = "https://" + HOST + "/staff/";
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " ShchiStaffApp/1");
        web.addJavascriptInterface(new Bridge(this), "ShchiApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost()) || ("www." + HOST).equals(u.getHost())) return false;
                try { startActivity(new Intent(u.getScheme() != null && u.getScheme().equals("tel") ? Intent.ACTION_DIAL : Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        if (b == null) web.loadUrl(URL); else web.restoreState(b);
        askPermissions();
        if (!Prefs.token(this).isEmpty()) WatchService.start(this);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        // Чтобы телефон не «усыплял» приложение при выключенном экране
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        SharedPreferences p = Prefs.get(this);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !p.getBoolean("askedBattery", false)) {
            p.edit().putBoolean("askedBattery", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
    @Override protected void onResume() { super.onResume(); WatchService.appVisible = true; web.onResume(); }
    @Override protected void onPause() { WatchService.appVisible = false; web.onPause(); super.onPause(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else moveTaskToBack(true); }

    /** Мост для страницы: она передаёт токен входа, чтобы приложение могло само проверять заявки. */
    static class Bridge {
        private final Context c;
        Bridge(Context c) { this.c = c.getApplicationContext(); }
        @JavascriptInterface public void setToken(String t) {
            Prefs.get(c).edit().putString("token", t == null ? "" : t).apply();
            if (t == null || t.isEmpty()) WatchService.stop(c); else WatchService.start(c);
        }
        @JavascriptInterface public void testSound() { WatchService.ringOnce(c); }
        @JavascriptInterface public String version() { return "1"; }
    }
}
