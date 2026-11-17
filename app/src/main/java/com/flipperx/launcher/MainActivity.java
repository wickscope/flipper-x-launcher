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
import android.hardware.ConsumerIrManager;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellIdentityNr;
import android.telephony.CellSignalStrength;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.app.PendingIntent;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

        // Let the WebView's getUserMedia (camera QR scanner, mic spectrum) actually open hardware.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() { public void run() {
                    try { request.grant(request.getResources()); } catch (Exception e) {}
                }});
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
        if (web != null) {
            try { web.evaluateJavascript("window.__fxStopMedia&&__fxStopMedia();", null); } catch (Exception e) {}
            web.onPause(); web.pauseTimers();
        }
        stopBt();
        stopGnss();
        serialClosePort();
        try { doTorch(false); } catch (Exception e) {}
    }

    @Override
    protected void onDestroy() {
        try { if (wifiRx != null) unregisterReceiver(wifiRx); } catch (Exception e) {}
        try { if (btRx != null) unregisterReceiver(btRx); } catch (Exception e) {}
        try { if (usbPermRx != null) unregisterReceiver(usbPermRx); } catch (Exception e) {}
        wifiRx = null; btRx = null; usbPermRx = null;
        serialClosePort();
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
        else if (req == REQ_CELL) { if (granted) { doCellScan(); } else js("window.FXCELL&&FXCELL(null,'location + phone permission needed for tower recon')"); }
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
        // ---- expanded cyber capabilities ----
        @JavascriptInterface public void cellScan() { runOnUiThread(new Runnable(){ public void run(){ startCell(); }}); }
        @JavascriptInterface public void gnssStart(){ runOnUiThread(new Runnable(){ public void run(){ startGnss(); }}); }
        @JavascriptInterface public void gnssStop() { runOnUiThread(new Runnable(){ public void run(){ stopGnss(); }}); }
        @JavascriptInterface public void usbList()  { runOnUiThread(new Runnable(){ public void run(){ emitUsb(); }}); }
        @JavascriptInterface public void nfcInfo()  { runOnUiThread(new Runnable(){ public void run(){ emitNfc(); }}); }
        @JavascriptInterface public void irInfo()   { runOnUiThread(new Runnable(){ public void run(){ emitIr(); }}); }
        @JavascriptInterface public void irTx(final int freq, final String patternCsv){
            runOnUiThread(new Runnable(){ public void run(){ doIrTx(freq, patternCsv); }}); }
        @JavascriptInterface public void sysRecon() { runOnUiThread(new Runnable(){ public void run(){ emitSys(); }}); }
        @JavascriptInterface public void torch(final boolean on){ runOnUiThread(new Runnable(){ public void run(){ doTorch(on); }}); }
        @JavascriptInterface public void vibrate(final int ms){ doVibrate(ms); }
        // ---- USB-serial (talk to attached modules: CC1101, PN532, generic UART) ----
        @JavascriptInterface public void serialList(){ runOnUiThread(new Runnable(){ public void run(){ emitSerialList(); }}); }
        @JavascriptInterface public void serialOpen(final int vid, final int pid, final int baud){
            runOnUiThread(new Runnable(){ public void run(){ serialOpen(vid, pid, baud); }}); }
        @JavascriptInterface public void serialWrite(final String text){
            runOnUiThread(new Runnable(){ public void run(){ serialWrite(text, false); }}); }
        @JavascriptInterface public void serialWriteHex(final String hex){
            runOnUiThread(new Runnable(){ public void run(){ serialWrite(hex, true); }}); }
        @JavascriptInterface public void serialClose(){ runOnUiThread(new Runnable(){ public void run(){ serialClose(); }}); }
    }

    // ---------------- USB-serial engine ----------------
    private static final String ACTION_USB_PERM = "com.flipperx.launcher.USB_PERM";
    private UsbSerialPort fxPort;
    private SerialInputOutputManager fxIo;
    private BroadcastReceiver usbPermRx;
    private int pendVid, pendPid, pendBaud;

    private void emitSerialList() {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            JSONArray arr = new JSONArray();
            if (um != null) {
                List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um);
                for (UsbSerialDriver drv : drivers) {
                    UsbDevice d = drv.getDevice();
                    JSONObject o = new JSONObject();
                    o.put("vid", d.getVendorId()); o.put("pid", d.getProductId());
                    o.put("vidhex", String.format("%04x", d.getVendorId()));
                    o.put("pidhex", String.format("%04x", d.getProductId()));
                    String pn=null; try { pn=d.getProductName(); } catch (Exception e) {}
                    o.put("name", pn==null? d.getDeviceName() : pn);
                    String cls = drv.getClass().getSimpleName().replace("SerialDriver","");
                    o.put("driver", cls);
                    o.put("ports", drv.getPorts().size());
                    o.put("hasPerm", um.hasPermission(d));
                    arr.put(o);
                }
            }
            js("window.FXSERIALLIST&&FXSERIALLIST(" + arr.toString() + ")");
        } catch (Exception e) { js("window.FXSERIALLIST&&FXSERIALLIST([])"); }
    }

    private void serialOpen(int vid, int pid, int baud) {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um == null) { serialStatus(false, "no USB host service"); return; }
            UsbSerialDriver target = null;
            for (UsbSerialDriver drv : UsbSerialProber.getDefaultProber().findAllDrivers(um)) {
                UsbDevice d = drv.getDevice();
                if (d.getVendorId()==vid && d.getProductId()==pid) { target = drv; break; }
            }
            if (target == null) { serialStatus(false, "no serial driver — is the module attached?"); return; }
            UsbDevice dev = target.getDevice();
            if (!um.hasPermission(dev)) {
                pendVid=vid; pendPid=pid; pendBaud=baud;
                if (usbPermRx == null) {
                    usbPermRx = new BroadcastReceiver() {
                        public void onReceive(Context c, Intent i) {
                            if (!ACTION_USB_PERM.equals(i.getAction())) return;
                            boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                            if (granted) serialOpen(pendVid, pendPid, pendBaud);
                            else serialStatus(false, "USB permission denied");
                        }
                    };
                    IntentFilter f = new IntentFilter(ACTION_USB_PERM);
                    if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPermRx, f, Context.RECEIVER_NOT_EXPORTED);
                    else registerReceiver(usbPermRx, f);
                }
                int flag = (Build.VERSION.SDK_INT >= 31) ? PendingIntent.FLAG_MUTABLE : 0;
                Intent it = new Intent(ACTION_USB_PERM).setPackage(getPackageName());
                PendingIntent pi = PendingIntent.getBroadcast(this, 0, it, flag);
                um.requestPermission(dev, pi);
                serialStatus(false, "allow the USB permission popup…");
                return;
            }
            UsbDeviceConnection conn = um.openDevice(dev);
            if (conn == null) { serialStatus(false, "could not open USB device"); return; }
            serialClosePort();   // close any previous
            UsbSerialPort port = target.getPorts().get(0);
            port.open(conn);
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try { port.setDTR(true); port.setRTS(true); } catch (Exception e) {}
            fxPort = port;
            fxIo = new SerialInputOutputManager(port, new SerialInputOutputManager.Listener() {
                public void onNewData(byte[] data) { emitSerialRx(data); }
                public void onRunError(Exception e) { serialStatus(false, "serial read error: " + e.getMessage()); }
            });
            fxIo.start();
            serialStatus(true, target.getClass().getSimpleName().replace("SerialDriver","") + " @ " + baud);
        } catch (Exception e) { serialStatus(false, e.getMessage()==null? "open failed" : e.getMessage()); }
    }

    private void serialWrite(String s, boolean hex) {
        try {
            if (fxPort == null) { serialStatus(false, "not connected"); return; }
            byte[] out;
            if (hex) {
                String h = s.replaceAll("[^0-9a-fA-F]", "");
                int n = h.length()/2; out = new byte[n];
                for (int i=0;i<n;i++) out[i]=(byte) Integer.parseInt(h.substring(i*2,i*2+2),16);
            } else {
                out = s.getBytes(StandardCharsets.UTF_8);
            }
            fxPort.write(out, 1000);
        } catch (Exception e) { serialStatus(false, "write failed: " + e.getMessage()); }
    }

    private void serialClose() { serialClosePort(); serialStatus(false, "closed"); }
    private void serialClosePort() {
        try { if (fxIo != null) { fxIo.stop(); fxIo = null; } } catch (Exception e) {}
        try { if (fxPort != null) { fxPort.close(); fxPort = null; } } catch (Exception e) {}
    }

    private void serialStatus(boolean open, String msg) {
        js("window.FXSERIAL&&FXSERIAL(" + open + ",'" + esc(msg) + "')");
    }
    private void emitSerialRx(byte[] data) {
        if (data == null || data.length == 0) return;
        StringBuilder hx = new StringBuilder(), tx = new StringBuilder();
        for (byte b : data) {
            int v = b & 0xff;
            hx.append(String.format("%02x ", v));
            tx.append((v>=32 && v<127) ? (char)v : (v==10||v==13 ? (char)v : '.'));
        }
        js("window.FXSERIALRX&&FXSERIALRX('" + esc(hx.toString().trim()) + "','" + esc(tx.toString()) + "')");
    }

    // ---------------- Cell-tower recon ----------------
    private static final int REQ_CELL = 53;
    private void startCell() {
        boolean loc = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean phone = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        if (!loc || !phone) {
            try { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE}, REQ_CELL); } catch (Exception e) {}
            return;
        }
        doCellScan();
    }

    @SuppressWarnings("MissingPermission")
    private void doCellScan() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) { js("window.FXCELL&&FXCELL(null,'no telephony hardware')"); return; }
            String op = "";
            try { op = tm.getNetworkOperatorName(); } catch (Exception e) {}
            List<CellInfo> all = tm.getAllCellInfo();
            JSONArray arr = new JSONArray();
            if (all != null) for (CellInfo ci : all) {
                JSONObject o = new JSONObject();
                try {
                    if (ci instanceof CellInfoLte) {
                        CellInfoLte c = (CellInfoLte) ci; CellIdentityLte id = c.getCellIdentity();
                        o.put("type","LTE"); o.put("cid", id.getCi()); o.put("tac", id.getTac());
                        o.put("pci", id.getPci()); o.put("earfcn", id.getEarfcn());
                        o.put("mcc", safeMccLte(id)); o.put("mnc", safeMncLte(id));
                        o.put("dbm", c.getCellSignalStrength().getDbm());
                    } else if (ci instanceof CellInfoWcdma) {
                        CellInfoWcdma c = (CellInfoWcdma) ci; CellIdentityWcdma id = c.getCellIdentity();
                        o.put("type","WCDMA"); o.put("cid", id.getCid()); o.put("tac", id.getLac());
                        o.put("pci", id.getPsc()); o.put("earfcn", id.getUarfcn());
                        o.put("dbm", c.getCellSignalStrength().getDbm());
                    } else if (ci instanceof CellInfoGsm) {
                        CellInfoGsm c = (CellInfoGsm) ci; CellIdentityGsm id = c.getCellIdentity();
                        o.put("type","GSM"); o.put("cid", id.getCid()); o.put("tac", id.getLac());
                        o.put("pci", id.getBsic()); o.put("earfcn", id.getArfcn());
                        o.put("dbm", c.getCellSignalStrength().getDbm());
                    } else if (Build.VERSION.SDK_INT >= 29 && ci instanceof CellInfoNr) {
                        CellInfoNr c = (CellInfoNr) ci;
                        CellIdentityNr id = (CellIdentityNr) c.getCellIdentity();
                        o.put("type","5G-NR"); o.put("cid", id.getNci()); o.put("tac", id.getTac());
                        o.put("pci", id.getPci()); o.put("earfcn", id.getNrarfcn());
                        o.put("dbm", c.getCellSignalStrength().getDbm());
                    } else if (ci instanceof CellInfoCdma) {
                        o.put("type","CDMA");
                        o.put("dbm", ((CellInfoCdma) ci).getCellSignalStrength().getDbm());
                    } else continue;
                    o.put("reg", ci.isRegistered());
                    o.put("op", op);
                    arr.put(o);
                } catch (Exception inner) {}
            }
            js("window.FXCELL&&FXCELL(" + arr.toString() + ",null)");
        } catch (SecurityException se) {
            js("window.FXCELL&&FXCELL(null,'permission denied')");
        } catch (Exception e) {
            js("window.FXCELL&&FXCELL(null,'" + esc(e.getMessage()) + "')");
        }
    }
    private static int safeMccLte(CellIdentityLte id){ try{ if(Build.VERSION.SDK_INT>=28){String m=id.getMccString();return m==null?-1:Integer.parseInt(m);} return id.getMcc(); }catch(Exception e){return -1;} }
    private static int safeMncLte(CellIdentityLte id){ try{ if(Build.VERSION.SDK_INT>=28){String m=id.getMncString();return m==null?-1:Integer.parseInt(m);} return id.getMnc(); }catch(Exception e){return -1;} }

    // ---------------- GNSS / GPS ----------------
    private LocationManager locMgr;
    private GnssStatus.Callback gnssCb;
    private void startGnss() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_CELL); } catch (Exception e) {}
            js("window.FXGNSS&&FXGNSS(null,'grant location, then press Start again')");
            return;
        }
        try {
            locMgr = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locMgr == null) { js("window.FXGNSS&&FXGNSS(null,'no location hardware')"); return; }
            boolean gpsOn = false;
            try { gpsOn = locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception e) {}
            if (!gpsOn) { js("window.FXGNSS&&FXGNSS(null,'turn GPS/Location ON in Shield first')"); return; }
            if (Build.VERSION.SDK_INT >= 24) {
                if (gnssCb == null) gnssCb = new GnssStatus.Callback() {
                    @Override public void onSatelliteStatusChanged(GnssStatus st) { emitGnss(st); }
                };
                try { locMgr.registerGnssStatusCallback(gnssCb); } catch (SecurityException se) {}
            }
            try {
                locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener);
            } catch (Exception e) {}
            js("window.FXGNSS&&FXGNSS({sats:0,used:0,fix:false,waiting:true},null)");
        } catch (SecurityException se) { js("window.FXGNSS&&FXGNSS(null,'permission denied')"); }
        catch (Exception e) { js("window.FXGNSS&&FXGNSS(null,'" + esc(e.getMessage()) + "')"); }
    }
    private final android.location.LocationListener gpsListener = new android.location.LocationListener() {
        public void onLocationChanged(Location l) { lastFix = l; }
        public void onProviderEnabled(String p) {}
        public void onProviderDisabled(String p) {}
        public void onStatusChanged(String p, int s, Bundle b) {}
    };
    private Location lastFix;
    private void emitGnss(GnssStatus st) {
        try {
            int n = st.getSatelliteCount(), used = 0; double cnSum = 0; int cnN = 0;
            int gps=0,glo=0,gal=0,bds=0,qzss=0,other=0;
            for (int i=0;i<n;i++){
                if (st.usedInFix(i)) used++;
                float cn=st.getCn0DbHz(i); if(cn>0){cnSum+=cn;cnN++;}
                int c=st.getConstellationType(i);
                switch(c){case GnssStatus.CONSTELLATION_GPS:gps++;break;
                    case GnssStatus.CONSTELLATION_GLONASS:glo++;break;
                    case GnssStatus.CONSTELLATION_GALILEO:gal++;break;
                    case GnssStatus.CONSTELLATION_BEIDOU:bds++;break;
                    case GnssStatus.CONSTELLATION_QZSS:qzss++;break;default:other++;}
            }
            JSONObject o=new JSONObject();
            o.put("sats",n); o.put("used",used); o.put("fix",used>=4);
            o.put("avgcn", cnN>0?Math.round(cnSum/cnN):0);
            o.put("gps",gps);o.put("glo",glo);o.put("gal",gal);o.put("bds",bds);o.put("qzss",qzss);o.put("other",other);
            if (lastFix!=null){ o.put("lat",lastFix.getLatitude()); o.put("lon",lastFix.getLongitude());
                o.put("alt",lastFix.getAltitude()); o.put("acc",lastFix.getAccuracy()); }
            js("window.FXGNSS&&FXGNSS(" + o.toString() + ",null)");
        } catch (Exception e) {}
    }
    private void stopGnss() {
        try { if (locMgr!=null){ if(gnssCb!=null&&Build.VERSION.SDK_INT>=24) locMgr.unregisterGnssStatusCallback(gnssCb);
            locMgr.removeUpdates(gpsListener); } } catch (Exception e) {}
    }

    // ---------------- USB-OTG host scanner (the backbone of every attachable module) ----------------
    private void emitUsb() {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            JSONArray arr = new JSONArray();
            if (um != null) {
                Map<String, UsbDevice> devs = um.getDeviceList();
                for (UsbDevice d : devs.values()) {
                    JSONObject o = new JSONObject();
                    o.put("vid", d.getVendorId()); o.put("pid", d.getProductId());
                    o.put("vidhex", String.format("%04x", d.getVendorId()));
                    o.put("pidhex", String.format("%04x", d.getProductId()));
                    String pn=null, mn=null;
                    try { pn=d.getProductName(); mn=d.getManufacturerName(); } catch (Exception e) {}
                    o.put("name", pn==null? d.getDeviceName() : pn);
                    o.put("maker", mn==null? "" : mn);
                    o.put("cls", d.getDeviceClass());
                    int ifc = 0; boolean cdc=false, hid=false;
                    try { ifc=d.getInterfaceCount();
                        for (int i=0;i<ifc;i++){ UsbInterface it=d.getInterface(i);
                            int c=it.getInterfaceClass(); if(c==2||c==10)cdc=true; if(c==3)hid=true; } } catch (Exception e) {}
                    o.put("ifs", ifc); o.put("serial", cdc); o.put("hid", hid);
                    o.put("kind", classifyUsb(d.getVendorId(), d.getProductId(), cdc));
                    arr.put(o);
                }
            }
            js("window.FXUSB&&FXUSB(" + arr.toString() + ",null)");
        } catch (Exception e) { js("window.FXUSB&&FXUSB(null,'" + esc(e.getMessage()) + "')"); }
    }
    /** Name common cyber modules by their USB id so attached hardware is recognised, not guessed. */
    private static String classifyUsb(int vid, int pid, boolean cdc) {
        if (vid==0x0bda && (pid==0x2838||pid==0x2832)) return "RTL-SDR (DVB-T dongle)";
        if (vid==0x1d50 && pid==0x6089) return "HackRF One (SDR)";
        if (vid==0x1d50 && (pid==0x604b||pid==0x6108)) return "Proxmark3 (RFID)";
        if (vid==0x072f || vid==0x04e6) return "PC/SC NFC reader (PN532/ACR)";
        if (vid==0x1a86 && (pid==0x7523||pid==0x5523)) return "CH340 USB-serial (CC1101/GPIO bridge)";
        if (vid==0x1a86 && pid==0x55d4) return "CH9102 USB-serial";
        if (vid==0x10c4 && pid==0xea60) return "CP210x USB-serial";
        if (vid==0x0403) return "FTDI USB-serial";
        if (vid==0x2341 || vid==0x2a03) return "Arduino (serial firmware)";
        if (vid==0x303a) return "Espressif ESP32 (USB-serial)";
        if (cdc) return "USB-serial (CDC-ACM)";
        return "USB device";
    }

    // ---------------- NFC presence ----------------
    private void emitNfc() {
        try {
            NfcAdapter na = NfcAdapter.getDefaultAdapter(this);
            JSONObject o = new JSONObject();
            o.put("present", na != null);
            o.put("enabled", na != null && na.isEnabled());
            js("window.FXNFC&&FXNFC(" + o.toString() + ")");
        } catch (Exception e) { js("window.FXNFC&&FXNFC({present:false,enabled:false})"); }
    }

    // ---------------- IR blaster ----------------
    private void emitIr() {
        try {
            ConsumerIrManager cir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
            JSONObject o = new JSONObject();
            boolean has = cir != null && cir.hasIrEmitter();
            o.put("present", has);
            JSONArray freqs = new JSONArray();
            if (has) try { for (ConsumerIrManager.CarrierFrequencyRange r : cir.getCarrierFrequencies()) {
                JSONObject f=new JSONObject(); f.put("min",r.getMinFrequency()); f.put("max",r.getMaxFrequency()); freqs.put(f);
            } } catch (Exception e) {}
            o.put("freqs", freqs);
            js("window.FXIR&&FXIR(" + o.toString() + ",null)");
        } catch (Exception e) { js("window.FXIR&&FXIR({present:false},'" + esc(e.getMessage()) + "')"); }
    }
    private void doIrTx(int freq, String patternCsv) {
        try {
            ConsumerIrManager cir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
            if (cir == null || !cir.hasIrEmitter()) { js("window.FXIRTX&&FXIRTX(false,'no IR emitter on this phone')"); return; }
            String[] parts = patternCsv.split(",");
            int[] pat = new int[parts.length]; int n=0;
            for (String p : parts){ p=p.trim(); if(p.isEmpty())continue; pat[n++]=Integer.parseInt(p); }
            int[] use = (n==pat.length)?pat:Arrays.copyOf(pat,n);
            cir.transmit(freq, use);
            js("window.FXIRTX&&FXIRTX(true,null)");
        } catch (Exception e) { js("window.FXIRTX&&FXIRTX(false,'" + esc(e.getMessage()) + "')"); }
    }

    // ---------------- Device self-recon ----------------
    private void emitSys() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", Build.MODEL); o.put("device", Build.DEVICE); o.put("product", Build.PRODUCT);
            o.put("manufacturer", Build.MANUFACTURER); o.put("hardware", Build.HARDWARE); o.put("board", Build.BOARD);
            o.put("soc", (Build.VERSION.SDK_INT>=31? (Build.SOC_MANUFACTURER+" "+Build.SOC_MODEL):Build.HARDWARE));
            o.put("android", Build.VERSION.RELEASE); o.put("sdk", Build.VERSION.SDK_INT);
            o.put("abis", Arrays.toString(Build.SUPPORTED_ABIS));
            o.put("bootloader", Build.BOOTLOADER); o.put("fingerprint", Build.FINGERPRINT);
            try { TelephonyManager tm=(TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
                if(tm!=null){ o.put("carrier", tm.getNetworkOperatorName());
                    o.put("sim", tm.getSimOperatorName()); o.put("country", tm.getNetworkCountryIso()); } } catch (Exception e){}
            try { Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi!=null){ int t=bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1);
                    int v=bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);
                    int lvl=bi.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                    o.put("batTemp", t>0? (t/10.0):-1); o.put("batMv", v); o.put("batPct", lvl); } } catch (Exception e){}
            boolean nfc=false; try{ nfc=NfcAdapter.getDefaultAdapter(this)!=null; }catch(Exception e){}
            boolean ir=false; try{ ConsumerIrManager c=(ConsumerIrManager)getSystemService(Context.CONSUMER_IR_SERVICE); ir=c!=null&&c.hasIrEmitter(); }catch(Exception e){}
            o.put("nfc", nfc); o.put("ir", ir);
            o.put("usbHost", getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST));
            o.put("telephony", getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY));
            o.put("camera", getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));
            js("window.FXSYS&&FXSYS(" + o.toString() + ")");
        } catch (Exception e) { js("window.FXSYS&&FXSYS({err:'" + esc(e.getMessage()) + "'})"); }
    }

    // ---------------- Torch + vibration ----------------
    private void doTorch(boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) { js("window.FXTORCH&&FXTORCH(false,'no camera service')"); return; }
            String flashId = null;
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) { flashId = id; break; }
            }
            if (flashId == null) { js("window.FXTORCH&&FXTORCH(false,'no flash on this phone')"); return; }
            cm.setTorchMode(flashId, on);
            js("window.FXTORCH&&FXTORCH(" + on + ",null)");
        } catch (Exception e) { js("window.FXTORCH&&FXTORCH(false,'" + esc(e.getMessage()) + "')"); }
    }
    private void doVibrate(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Exception e) {}
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
