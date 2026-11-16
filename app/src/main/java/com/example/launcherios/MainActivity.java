package com.example.launcherios;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

public class MainActivity extends Activity {
    static MainActivity cur;
    WebView web;
    final Handler ui = new Handler(Looper.getMainLooper());
    String appsJson;
    CameraManager cam;
    String camId;
    boolean torch = false;

    static void js(final String s) {
        final MainActivity a = cur;
        if (a == null || a.web == null) return;
        a.ui.post(new Runnable() {
            public void run() { a.web.evaluateJavascript(s, null); }
        });
    }

    final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsJson = null;
            js("if(window.loadApps)loadApps()");
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cur = this;
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams p = getWindow().getAttributes();
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(p);
        }
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);
        web.setHapticFeedbackEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        immersive();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);

        initTorch();
        askPermsNow();
    }

    void initTorch() {
        try {
            cam = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cam.getCameraIdList()) {
                CameraCharacteristics c = cam.getCameraCharacteristics(id);
                Boolean fl = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lf = c.get(CameraCharacteristics.LENS_FACING);
                if (fl != null && fl && lf != null && lf == CameraCharacteristics.LENS_FACING_BACK) {
                    camId = id;
                    break;
                }
            }
            cam.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean on) {
                    if (id.equals(camId)) torch = on;
                }
            }, ui);
        } catch (Exception e) { }
    }

    void askPermsNow() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        js("if(window.refreshWidgets)refreshWidgets()");
    }

    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("if(window.onResumeHome)onResumeHome()");
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        js("if(window.onHomePressed)onHomePressed()");
    }

    @Override
    public void onBackPressed() {
        js("if(typeof homeBtn==='function')homeBtn()");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(rx); } catch (Exception e) { }
        if (cur == this) cur = null;
        super.onDestroy();
    }

    void start(final Intent i) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) { }
            }
        });
    }

    String pkgFor(Intent i) {
        try {
            PackageManager pm = getPackageManager();
            ResolveInfo r = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (r != null && r.activityInfo != null && !r.activityInfo.packageName.equals("android")) {
                return r.activityInfo.packageName;
            }
            for (ResolveInfo q : pm.queryIntentActivities(i, 0)) {
                if (!q.activityInfo.packageName.equals("android")) return q.activityInfo.packageName;
            }
        } catch (Exception e) { }
        return null;
    }

    int cat(ApplicationInfo ai) {
        if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return 0;
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_GAME: return 0;
            case ApplicationInfo.CATEGORY_SOCIAL:
            case ApplicationInfo.CATEGORY_NEWS: return 1;
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO: return 2;
            case ApplicationInfo.CATEGORY_IMAGE: return 3;
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return 4;
            case ApplicationInfo.CATEGORY_MAPS: return 5;
            default: return 6;
        }
    }

    String icon(Drawable d) {
        int S = 96;
        Bitmap bm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
            int o = S / 4;
            Drawable bg = ad.getBackground();
            Drawable fg = ad.getForeground();
            if (bg != null) { bg.setBounds(-o, -o, S + o, S + o); bg.draw(c); }
            if (fg != null) { fg.setBounds(-o, -o, S + o, S + o); fg.draw(c); }
        } else {
            d.setBounds(0, 0, S, S);
            d.draw(c);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    synchronized String buildApps() {
        if (appsJson != null) return appsJson;
        try {
            final PackageManager pm = getPackageManager();
            Intent mi = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> l = pm.queryIntentActivities(mi, 0);
            final HashMap<ResolveInfo, String> lb = new HashMap<>();
            for (ResolveInfo r : l) lb.put(r, r.loadLabel(pm).toString());
            Collections.sort(l, (a, b) -> lb.get(a).compareToIgnoreCase(lb.get(b)));
            JSONArray arr = new JSONArray();
            HashSet<String> seen = new HashSet<>();
            for (ResolveInfo r : l) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || !seen.add(p)) continue;
                JSONObject o = new JSONObject();
                o.put("n", lb.get(r));
                o.put("p", p);
                o.put("c", cat(r.activityInfo.applicationInfo));
                o.put("img", icon(r.loadIcon(pm)));
                arr.put(o);
            }
            LinkedHashSet<String> dk = new LinkedHashSet<>();
            String[] cand = {
                    pkgFor(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))),
                    pkgFor(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
                    pkgFor(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))),
                    pkgFor(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            };
            for (String p : cand) if (p != null) dk.add(p);
            JSONArray dj = new JSONArray();
            for (String p : dk) dj.put(p);
            JSONObject root = new JSONObject();
            root.put("apps", arr);
            root.put("dock", dj);
            appsJson = root.toString();
        } catch (Exception e) {
            appsJson = "{\"apps\":[],\"dock\":[]}";
        }
        return appsJson;
    }

    class Bridge {
        @JavascriptInterface
        public void loadApps() {
            new Thread(new Runnable() {
                public void run() {
                    String j = buildApps();
                    js("onApps(" + JSONObject.quote(j) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void launch(final String p) {
            ui.post(new Runnable() {
                public void run() {
                    Intent i = getPackageManager().getLaunchIntentForPackage(p);
                    if (i == null) { js("if(window.onLaunchFail)onLaunchFail()"); return; }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(i); } catch (Exception e) { js("if(window.onLaunchFail)onLaunchFail()"); }
                }
            });
        }

        @JavascriptInterface public String getNotifs() { return NLService.dump(); }

        @JavascriptInterface
        public boolean notifEnabled() {
            String f = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return f != null && f.contains(getPackageName());
        }

        @JavascriptInterface public void openNotifAccess() { start(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        @JavascriptInterface public void openNotif(String k) { NLService.open(k); }
        @JavascriptInterface public void clearNotifs() { NLService.clear(); }
        @JavascriptInterface public void openHomeSettings() { start(new Intent(Settings.ACTION_HOME_SETTINGS)); }

        @JavascriptInterface
        public void openPerms() {
            start(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface
        public void askPerms() {
            ui.post(new Runnable() { public void run() { askPermsNow(); } });
        }

        @JavascriptInterface
        public String sysState() {
            JSONObject o = new JSONObject();
            try {
                try {
                    WifiManager w = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    o.put("wifi", w != null && w.isWifiEnabled());
                } catch (Exception e) { }
                try {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    o.put("bt", ba != null && ba.isEnabled());
                } catch (Exception e) { }
                o.put("air", Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
                o.put("rot", Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 1);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                o.put("dnd", nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL);
                o.put("torch", torch);
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    int lv = b.getIntExtra("level", 0), sc = b.getIntExtra("scale", 100), st = b.getIntExtra("status", 0);
                    o.put("bat", lv * 100 / Math.max(sc, 1));
                    o.put("chg", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
                }
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                o.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC) / (double) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                o.put("brt", Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255.0);
            } catch (Exception e) { }
            return o.toString();
        }

        @JavascriptInterface
        public void setBrightness(final double v) {
            ui.post(new Runnable() {
                public void run() {
                    int val = Math.max(5, (int) (v * 255));
                    try {
                        if (Settings.System.canWrite(MainActivity.this)) {
                            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
                        } else {
                            WindowManager.LayoutParams p = getWindow().getAttributes();
                            p.screenBrightness = (float) Math.max(0.02, v);
                            getWindow().setAttributes(p);
                        }
                    } catch (Exception e) { }
                }
            });
        }

        @JavascriptInterface
        public void setVolume(double v) {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(v * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void panel(String k) {
            Intent i;
            if (k.equals("air")) i = new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
            else if (k.equals("bt")) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            else if (k.equals("cast")) i = new Intent(Settings.ACTION_CAST_SETTINGS);
            else if (Build.VERSION.SDK_INT >= 29) i = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
            else i = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            start(i);
        }

        @JavascriptInterface
        public void toggleRotate() {
            try {
                if (Settings.System.canWrite(MainActivity.this)) {
                    int cur = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
                    Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, cur == 1 ? 0 : 1);
                } else openPerms();
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void toggleDnd() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm.isNotificationPolicyAccessGranted()) {
                    boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
                    nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                } else {
                    start(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                }
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void toggleTorch() {
            try {
                if (cam != null && camId != null) {
                    torch = !torch;
                    cam.setTorchMode(camId, torch);
                }
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void shortcut(String k) {
            Intent i = null;
            if (k.equals("Reloj")) i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            else if (k.equals("Calculadora")) i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR);
            else if (k.equals("Cámara")) i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            else if (k.equals("cal")) i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR);
            if (i != null) start(i);
        }

        @JavascriptInterface
        public void media(String cmd) {
            int code = cmd.equals("next") ? KeyEvent.KEYCODE_MEDIA_NEXT
                    : cmd.equals("prev") ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }

        @JavascriptInterface
        public String mediaInfo() {
            JSONObject o = new JSONObject();
            try {
                MediaSessionManager m = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
                List<MediaController> l = m.getActiveSessions(new ComponentName(MainActivity.this, NLService.class));
                MediaController pick = null;
                for (MediaController c : l) {
                    PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
                }
                if (pick == null && !l.isEmpty()) pick = l.get(0);
                if (pick != null) {
                    MediaMetadata md = pick.getMetadata();
                    PlaybackState ps = pick.getPlaybackState();
                    if (md != null) {
                        o.put("t", md.getString(MediaMetadata.METADATA_KEY_TITLE));
                        o.put("a", md.getString(MediaMetadata.METADATA_KEY_ARTIST));
                    }
                    o.put("pl", ps != null && ps.getState() == PlaybackState.STATE_PLAYING);
                }
            } catch (Exception e) { }
            return o.toString();
        }

        @JavascriptInterface
        public String getEvents() {
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return "null";
            JSONArray a = new JSONArray();
            try {
                long now = System.currentTimeMillis();
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, 23);
                c.set(Calendar.MINUTE, 59);
                c.set(Calendar.SECOND, 59);
                Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(b, now);
                ContentUris.appendId(b, c.getTimeInMillis());
                Cursor q = getContentResolver().query(b.build(),
                        new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY},
                        null, null, CalendarContract.Instances.BEGIN + " ASC");
                if (q != null) {
                    while (q.moveToNext() && a.length() < 3) {
                        JSONObject o = new JSONObject();
                        o.put("t", q.getString(0));
                        o.put("b", q.getLong(1));
                        o.put("d", q.getInt(2));
                        a.put(o);
                    }
                    q.close();
                }
            } catch (Exception e) { }
            return a.toString();
        }

        @JavascriptInterface
        public String getLocation() {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "";
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            try {
                for (String p : lm.getProviders(true)) {
                    try {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (Exception e) { }
                }
            } catch (Exception e) { }
            return best == null ? "" : best.getLatitude() + "," + best.getLongitude();
        }
    }
}
