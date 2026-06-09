package si.kclj.gtinpredloga;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String TAG = "PostaviGtin";
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        // Zagotovi, da WebView prejema tipke (DataWedge keystroke / HID)
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.loadUrl("file:///android_asset/postavi_gtin.html");
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
                    // < Android 10: zapis v app-specifično mapo (brez dovoljenj)
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
