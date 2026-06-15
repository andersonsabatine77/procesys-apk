package com.procesys.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App "casca" (WebView) que carrega o ProcesSys embutido em assets/index.html.
 * Funciona 100% offline. Trata a importação (seletor de arquivo) e a
 * exportação (download de Blob → salva em Downloads).
 */
public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);     // necessário para o localStorage do app
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient());

        // Importação: o app usa <input type="file">.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "Não foi possível abrir o seletor de arquivos.",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // Exportação: o app cria um Blob e dispara download. Capturamos o blob,
        // lemos como base64 no JS e salvamos o arquivo em Downloads.
        web.addJavascriptInterface(new ExportBridge(), "AndroidExport");
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                if (url != null && url.startsWith("blob:")) {
                    String js = "(function(){try{"
                            + "var x=new XMLHttpRequest();x.open('GET','" + url + "',true);"
                            + "x.responseType='blob';"
                            + "x.onload=function(){var r=new FileReader();"
                            + "r.onloadend=function(){AndroidExport.save(r.result);};"
                            + "r.readAsDataURL(x.response);};"
                            + "x.onerror=function(){AndroidExport.fail();};"
                            + "x.send();}catch(e){AndroidExport.fail();}})();";
                    web.evaluateJavascript(js, null);
                }
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null && data.getDataString() != null) {
                    results = new Uri[]{ Uri.parse(data.getDataString()) };
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private class ExportBridge {
        @JavascriptInterface
        public void save(String dataUrl) {
            try {
                int comma = dataUrl.indexOf(',');
                String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                String name = "processys_dados_"
                        + new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date())
                        + ".json";
                String where = writeToDownloads(name, bytes);
                toast("Exportado: " + where);
            } catch (Exception e) {
                toast("Falha ao exportar: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void fail() {
            toast("Falha ao gerar o arquivo de exportação.");
        }
    }

    private String writeToDownloads(String name, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("não foi possível criar o arquivo");
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(bytes);
            os.flush();
            os.close();
            return "Downloads/" + name;
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(bytes);
            fos.flush();
            fos.close();
            return f.getAbsolutePath();
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
