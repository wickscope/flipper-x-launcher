package com.flipperx.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * FLIPPER X launcher: a full-screen WebView that IS the phone's home screen.
 * Loads the bundled FLIPPER X interface from app assets (fully offline) and
 * injects the on-card music library into the Music module via window.FLIPPERX.
 */
public class MainActivity extends Activity {

    private WebView web;
    private static final int REQ_READ = 42;
    private static final String[] AUDIO_EXT =
            {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // Let the file:///android_asset page load file:// audio off the card.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // Keep everything local: block any accidental outbound navigation.
                return !url.startsWith("file://");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectPlaylist();
            }
        });

        web.addJavascriptInterface(new RadioBridge(), "FXNATIVE");
        web.setBackgroundColor(0xFF060D0B);
        web.loadUrl("file:///android_asset/flipperx/index.html");

        setContentView(web);
        // Let the screen sleep normally — do NOT keep it on (that cooked the phone).
        // But draw FLIPPER X OVER the system keyguard so the stock lock/UI never flashes on wake.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        hideSystemUI();

        requestReadIfNeeded();
    }

    private void dismissKeyguard() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) km.requestDismissKeyguard(this, null);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Lid closed / screen off / backgrounded: halt ALL WebView work (JS, rAF, timers,
        // media decode) so the phone stops heating up while idle or closed.
        if (web != null) { web.onPause(); web.pauseTimers(); }
        stopBt();
    }

    @Override
    protected void onDestroy() {
        try { if (wifiRx != null) unregisterReceiver(wifiRx); } catch (Exception e) {}
        try { if (btRx != null) unregisterReceiver(btRx); } catch (Exception e) {}
        wifiRx = null; btRx = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        dismissKeyguard();   // clear the system swipe-lock so it never shows over FLIPPER X
        hideSystemUI();
        if (web != null) {
            web.resumeTimers(); web.onResume();
            injectPlaylist();  // re-scan music when new tracks are added
            // Go straight to the shark cover screen on every wake / lid-open.
            web.evaluateJavascript("window.FXIDLE&&FXIDLE();", null);
        }
    }

    private void requestReadIfNeeded() {
        String perm = (Build.VERSION.SDK_INT >= 33)
                ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{perm}, REQ_READ); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        boolean granted = r.length > 0;
        for (int v : r) if (v != PackageManager.PERMISSION_GRANTED) granted = false;
        if (req == REQ_READ) injectPlaylist();
        else if (req == REQ_WIFI) { if (granted) doWifiScan(); else js("window.FXWIFI&&FXWIFI(null,'location permission denied \u2014 Android requires it for Wi-Fi scanning')"); }
        else if (req == REQ_BT)   { if (granted) doBtScan();   else js("window.FXBT&&FXBT(null,'Bluetooth permission denied')"); }
    }

    // ---------------- Radio recon bridge (real Wi-Fi + Bluetooth scanning) ----------------
    private static final int REQ_WIFI = 51, REQ_BT = 52;
    private BroadcastReceiver wifiRx, btRx;

    /** Run JS on the UI thread. */
    private void js(final String code) {
        runOnUiThread(new Runnable() { public void run() {
            if (web != null) try { web.evaluateJavascript(code, null); } catch (Exception ignored) {}
        }});
    }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }

    final class RadioBridge {
        @JavascriptInterface public void wifiScan() { runOnUiThread(new Runnable(){ public void run(){ startWifi(); }}); }
        @JavascriptInterface public void btScan()   { runOnUiThread(new Runnable(){ public void run(){ startBt(); }}); }
        @JavascriptInterface public void btStop()    { runOnUiThread(new Runnable(){ public void run(){ stopBt(); }}); }
    }

    private void startWifi() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_WIFI); } catch (Exception e) {}
            return;
        }
        doWifiScan();
    }

    private void doWifiScan() {
        final WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) { js("window.FXWIFI&&FXWIFI(null,'no Wi-Fi hardware')"); return; }
        if (!wm.isWifiEnabled()) { js("window.FXWIFI&&FXWIFI(null,'turn Wi-Fi ON in Shield first')"); return; }
        if (wifiRx == null) {
            wifiRx = new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) { emitWifi(wm); }
            };
            try { registerReceiver(wifiRx, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)); } catch (Exception e) {}
        }
        emitWifi(wm);          // cached results immediately
        try { wm.startScan(); } catch (Exception e) {}   // trigger a fresh scan (throttled on newer Android)
    }

    private void emitWifi(WifiManager wm) {
        try {
            List<ScanResult> res = wm.getScanResults();
            JSONArray arr = new JSONArray();
            for (ScanResult r : res) {
                JSONObject o = new JSONObject();
                o.put("ssid", (r.SSID == null || r.SSID.isEmpty()) ? "<hidden>" : r.SSID);
                o.put("bssid", r.BSSID);
                o.put("rssi", r.level);
                o.put("freq", r.frequency);
                o.put("chan", freqToChannel(r.frequency));
                String cap = r.capabilities == null ? "" : r.capabilities;
                o.put("sec", cap.contains("WPA3") ? "WPA3" : cap.contains("WPA2") ? "WPA2"
                        : cap.contains("WPA") ? "WPA" : cap.contains("WEP") ? "WEP" : "open");
                arr.put(o);
            }
            js("window.FXWIFI&&FXWIFI(" + arr.toString() + ",null)");
        } catch (Exception e) { js("window.FXWIFI&&FXWIFI(null,'" + esc(e.getMessage()) + "')"); }
    }

    private static int freqToChannel(int f) {
        if (f == 2484) return 14;
        if (f >= 2412 && f <= 2472) return (f - 2412) / 5 + 1;
        if (f >= 5170 && f <= 5825) return (f - 5000) / 5;
        if (f >= 5955 && f <= 7115) return (f - 5950) / 5;   // 6GHz
        return 0;
    }

    private void startBt() {
        String[] need;
        if (Build.VERSION.SDK_INT >= 31) need = new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"};
        else need = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        boolean ok = true;
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ok = false;
        if (!ok) { try { requestPermissions(need, REQ_BT); } catch (Exception e) {} return; }
        doBtScan();
    }

    private void doBtScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { js("window.FXBT&&FXBT(null,'no Bluetooth hardware')"); return; }
        if (!ad.isEnabled()) { js("window.FXBT&&FXBT(null,'turn Bluetooth ON in Shield first')"); return; }
        if (btRx == null) {
            btRx = new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    if (!BluetoothDevice.ACTION_FOUND.equals(i.getAction())) return;
                    try {
                        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) 0);
                        String name = "";
                        try { name = d.getName(); } catch (SecurityException se) {}
                        JSONObject o = new JSONObject();
                        o.put("name", name == null || name.isEmpty() ? "<unknown>" : name);
                        o.put("mac", d.getAddress());
                        o.put("rssi", rssi);
                        js("window.FXBT&&FXBT(" + o.toString() + ",null)");
                    } catch (Exception e) {}
                }
            };
            try { registerReceiver(btRx, new IntentFilter(BluetoothDevice.ACTION_FOUND)); } catch (Exception e) {}
        }
        try { if (ad.isDiscovering()) ad.cancelDiscovery(); ad.startDiscovery(); js("window.FXBT&&FXBT(null,'scanning\u2026')"); }
        catch (SecurityException se) { js("window.FXBT&&FXBT(null,'Bluetooth permission denied')"); }
        catch (Exception e) { js("window.FXBT&&FXBT(null,'" + esc(e.getMessage()) + "')"); }
    }

    private void stopBt() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            if (ad != null && ad.isDiscovering()) ad.cancelDiscovery();
        } catch (Exception e) {}
    }

    /** Scan every Music folder we can reach and hand the list to the web layer. */
    private void injectPlaylist() {
        if (web == null) return;
        try {
            List<File> roots = new ArrayList<File>();
            // Primary shared Music folder.
            roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
            roots.add(new File("/storage/emulated/0/Music"));
            // Removable volumes (the 1TB card shows up as /storage/XXXX-XXXX).
            File storage = new File("/storage");
            File[] vols = storage.listFiles();
            if (vols != null) {
                for (File v : vols) {
                    String n = v.getName();
                    if (n.equals("emulated") || n.equals("self") || n.equals("private")) continue;
                    roots.add(new File(v, "Music"));
                }
            }
            // App-specific dirs (always readable, no permission needed).
            File[] ext = getExternalFilesDirs(null);
            if (ext != null) for (File e : ext) if (e != null) roots.add(new File(e, "Music"));

            List<File> tracks = new ArrayList<File>();
            List<String> seen = new ArrayList<String>();
            for (File root : roots) collect(root, tracks, seen);

            Collections.sort(tracks, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            JSONArray arr = new JSONArray();
            for (File f : tracks) {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String artist = "";
                String title = base;
                int sep = base.indexOf(" - ");
                if (sep > 0) { artist = base.substring(0, sep).trim(); title = base.substring(sep + 3).trim(); }
                JSONObject o = new JSONObject();
                o.put("title", title);
                o.put("artist", artist);
                // Encode the path so spaces / & / () survive as a valid file:// URL.
                o.put("src", "file://" + Uri.encode(f.getAbsolutePath(), "/"));
                arr.put(o);
            }
            final String json = arr.toString();
            web.post(() -> web.evaluateJavascript(
                    "window.FLIPPERX&&window.FLIPPERX.loadPlaylist&&window.FLIPPERX.loadPlaylist(" + json + ");",
                    null));
        } catch (Exception ignored) {}
    }

    private void collect(File dir, List<File> out, List<String> seen) {
        if (dir == null || !dir.isDirectory()) return;
        String key = dir.getAbsolutePath();
        if (seen.contains(key)) return;
        seen.add(key);
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { collect(f, out, seen); continue; }
            String ln = f.getName().toLowerCase();
            for (String e : AUDIO_EXT) if (ln.endsWith(e)) { out.add(f); break; }
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // right soft key / back / clear -> context back
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_SOFT_RIGHT:
                web.evaluateJavascript("window.FXSOFT&&FXSOFT('right');", null);
                return true;
            // left soft key / menu -> context select / options
            case KeyEvent.KEYCODE_SOFT_LEFT:
            case KeyEvent.KEYCODE_MENU:
                web.evaluateJavascript("window.FXSOFT&&FXSOFT('left');", null);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onBackPressed() {
        // Home app: do nothing (handled in onKeyDown).
    }
}
