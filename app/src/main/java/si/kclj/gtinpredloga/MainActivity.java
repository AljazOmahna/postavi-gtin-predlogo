package si.kclj.gtinpredloga;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final String TAG = "PostaviGtin";
    private static final int FILE_CHOOSER_REQ = 2001;

    // DataWedge Intent output — zanesljivo za WebView (Keystroke ne tipka v WebView)
    private static final String DW_SCAN_ACTION = "si.kclj.gtinpredloga.SCAN";
    private static final String DW_DATA_KEY    = "com.symbol.datawedge.data_string";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver dwReceiver;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Izberi CSV"), FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/postavi_gtin.html");

        // DataWedge: registriraj Intent receiver in nastavi profil
        dwReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String data = intent.getStringExtra(DW_DATA_KEY);
                if (data == null || data.isEmpty()) return;
                Log.d(TAG, "DW scan: " + data.replace("", "<GS>"));
                // Posreduj direktno JS handleScan() — GS1 separatorji (0x1D) ostanejo za parser
                final String safe = data.replaceAll("['\\\\]", "").replaceAll("[\\r\\n]", "");
                mainHandler.post(() ->
                    webView.evaluateJavascript(
                        "if(typeof handleScan==='function')handleScan(" + JSONObject.quote(safe) + ")", null)
                );
            }
        };
        registerReceiver(dwReceiver, new IntentFilter(DW_SCAN_ACTION));
        setupDataWedgeProfile();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{ data.getData() };
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dwReceiver != null) try { unregisterReceiver(dwReceiver); } catch (Exception ignored) {}
    }

    /** Nastavi DataWedge profil za PostaviGtin: Intent output → DW_SCAN_ACTION, Keystroke off. */
    private void setupDataWedgeProfile() {
        try {
            // Barcode plugin — vklopi DataMatrix in ostale dekoderje
            Bundle barcodeParams = new Bundle();
            barcodeParams.putString("scanner_input_enabled", "true");
            barcodeParams.putString("decoder_datamatrix",    "true");
            barcodeParams.putString("decoder_code128",       "true");
            barcodeParams.putString("decoder_code39",        "true");
            barcodeParams.putString("decoder_ean13",         "true");
            barcodeParams.putString("decoder_ean8",          "true");
            barcodeParams.putString("decoder_upca",          "true");
            barcodeParams.putString("decoder_qrcode",        "true");
            barcodeParams.putString("decoder_pdf417",        "true");
            barcodeParams.putString("decoder_gs1_databar",       "true");
            barcodeParams.putString("decoder_gs1_databar_exp",   "true");
            Bundle barcodePlugin = new Bundle();
            barcodePlugin.putString("PLUGIN_NAME",  "BARCODE");
            barcodePlugin.putString("RESET_CONFIG", "false");
            barcodePlugin.putBundle("PARAM_LIST",   barcodeParams);

            // Intent output — broadcast direktno v app
            Bundle intentParams = new Bundle();
            intentParams.putString("intent_output_enabled", "true");
            intentParams.putString("intent_action",         DW_SCAN_ACTION);
            intentParams.putString("intent_delivery",       "2"); // broadcast
            Bundle intentPlugin = new Bundle();
            intentPlugin.putString("PLUGIN_NAME",  "INTENT");
            intentPlugin.putString("RESET_CONFIG", "true");
            intentPlugin.putBundle("PARAM_LIST",   intentParams);

            // Keystroke — izklopi (prepreci podvajanje vnosov)
            Bundle ksParams = new Bundle();
            ksParams.putString("keystroke_output_enabled", "false");
            Bundle ksPlugin = new Bundle();
            ksPlugin.putString("PLUGIN_NAME",  "KEYSTROKE");
            ksPlugin.putString("RESET_CONFIG", "true");
            ksPlugin.putBundle("PARAM_LIST",   ksParams);

            // App asociacija
            Bundle appAssoc = new Bundle();
            appAssoc.putString("PACKAGE_NAME", getPackageName());
            appAssoc.putStringArray("ACTIVITY_LIST", new String[]{"*"});

            // Profil
            Bundle profile = new Bundle();
            profile.putString("PROFILE_NAME",    "PostaviGtin");
            profile.putString("PROFILE_ENABLED", "true");
            profile.putString("CONFIG_MODE",     "CREATE_IF_NOT_EXIST");
            profile.putParcelableArrayList("PLUGIN_CONFIG",
                new ArrayList<>(Arrays.asList(barcodePlugin, intentPlugin, ksPlugin)));
            profile.putParcelableArray("APP_LIST", new Bundle[]{ appAssoc });

            Intent i = new Intent("com.symbol.datawedge.api.ACTION");
            i.putExtra("com.symbol.datawedge.api.SET_CONFIG", profile);
            sendBroadcast(i);
            Log.d(TAG, "DataWedge profil nastavljen: Intent output → " + DW_SCAN_ACTION);
        } catch (Throwable t) {
            Log.e(TAG, "setupDataWedgeProfile: " + t);
        }
    }

    // JavaScript interface — omogoča HTML klicanje Androida
    class JsBridge {
        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, "JS: " + msg);
        }

        @JavascriptInterface
        public void toast(String msg) {
            if (msg == null) return;
            mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        // Prebere najnovejsi .csv iz lastne mape aplikacije (brez dovoljenj):
        //   /sdcard/Android/data/si.kclj.gtinpredloga/files/
        // Vrne vsebino datoteke ali "" ce je ni.
        @JavascriptInterface
        public String loadTemplate() {
            try {
                File dir = getExternalFilesDir(null);
                if (dir == null || !dir.exists()) return "";
                File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".csv"));
                if (files == null || files.length == 0) return "";
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                File f = files[0];
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                if (s.length() > 0 && s.charAt(0) == '﻿') s = s.substring(1); // odstrani BOM
                Log.d(TAG, "loadTemplate: " + f.getName() + " (" + s.length() + " znakov)");
                return s;
            } catch (Throwable t) {
                Log.e(TAG, "loadTemplate: " + t);
                return "";
            }
        }

        @JavascriptInterface
        public boolean copyToClipboard(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("GTIN CSV", text == null ? "" : text));
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "CSV kopiran v odlozisce", Toast.LENGTH_SHORT).show());
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "copyToClipboard: " + t);
                return false;
            }
        }

        // Shrani CSV v mapo Download. Vrne pot/URI ali "ERR".
        @JavascriptInterface
        public String saveCsv(String filename, String content) {
            String name = (filename == null || filename.trim().isEmpty()) ? "GTIN.csv" : filename.trim();
            if (!name.toLowerCase().endsWith(".csv")) name += ".csv";
            byte[] bytes = ("﻿" + (content == null ? "" : content)).getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "ERR";
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) return "ERR";
                    os.write(bytes);
                    os.close();
                    final String shown = "Download/" + name;
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "Shranjeno: " + shown, Toast.LENGTH_LONG).show());
                    return shown;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(bytes);
                    fos.close();
                    final String shown = f.getAbsolutePath();
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "Shranjeno: " + shown, Toast.LENGTH_LONG).show());
                    return shown;
                }
            } catch (Throwable t) {
                Log.e(TAG, "saveCsv: " + t);
                final String err = t.getMessage();
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Napaka pri shranjevanju: " + err, Toast.LENGTH_LONG).show());
                return "ERR";
            }
        }
    }
}
