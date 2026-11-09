package net.squeegeebros.lineup;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * SB Lineup: full-screen wrapper around the lineup web app.
 * The app itself lives at APP_URL, so updates pushed to GitHub show up without reinstalling.
 */
public class MainActivity extends Activity {

    private static final String APP_URL = "https://squeegeebros.github.io/lineup/";
    private static final String APP_HOST = "squeegeebros.github.io";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_FILE = 2;

    private static final String OFFLINE_HTML =
            "<html><body style='font-family:sans-serif;background:#0b1f33;color:#fff;display:flex;"
            + "align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;padding:24px'>"
            + "<div><h2>No connection</h2><p>SB Lineup needs internet the first time it opens.<br>"
            + "After that it works offline.</p>"
            + "<p><a style='color:#00e5ff;font-size:18px' href='" + APP_URL + "'>Try again</a></p></div></body></html>";

    private WebView web;
    private PermissionRequest pendingPermission;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f2f5f9"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " SBLineupApp/1");

        web.addJavascriptInterface(new Bridge(), "SBAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String path = u.getPath() == null ? "" : u.getPath();
                if (APP_HOST.equals(u.getHost()) && path.startsWith("/lineup")) {
                    return false; // stay inside the app
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // other links open in the browser
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        grantVideo(request);
                    } else {
                        pendingPermission = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl(APP_URL);
        }
    }

    private void grantVideo(PermissionRequest request) {
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                return;
            }
        }
        request.deny();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                grantVideo(pendingPermission);
            } else {
                pendingPermission.deny();
                Toast.makeText(this, "Camera permission is needed for the lineup camera", Toast.LENGTH_LONG).show();
            }
            pendingPermission = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Let the web app step back one screen; close the app only from the first screen.
        web.evaluateJavascript("(window.sbBack&&window.sbBack())?'y':'n'", value -> {
            if (!"\"y\"".equals(value)) {
                finish();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    /** Called from the web app: save the finished image to Photos, then open the share sheet. */
    private class Bridge {
        @JavascriptInterface
        public void saveAndShare(final String base64Jpeg, final String fileName) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Jpeg, Base64.DEFAULT);
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Squeegee Bros");
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IllegalStateException("insert failed");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new IllegalStateException("no stream");
                        os.write(bytes);
                    }
                    Toast.makeText(MainActivity.this, "Saved to Photos (Squeegee Bros album)", Toast.LENGTH_SHORT).show();

                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/jpeg");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share before & after"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Couldn't save the image", Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
