package ru.tvshell.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * HTML-оболочка Android TV: WebView + мост + просмотр HDMI-входов внутри приложения.
 */
public class MainActivity extends Activity {

    private static final String TAG = "TVShell";
    private static final String GOOGLE_TV = "com.google.android.tvlauncher";
    private static final String GOOGLE_TV_ACT = "com.google.android.tvlauncher.MainActivity";
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String TV_SETTINGS_ACT = "com.android.tv.settings.MainSettings";

    private FrameLayout root;
    private WebView web;
    private TvView tvView;
    private TextView inputHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);

        web = new ShellWebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        web.setBackgroundColor(0xFF07080A);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        hideSystemUi();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void handleBack() {
        if (inputVisible()) {
            hideInput();
        } else if (web != null) {
            web.evaluateJavascript("window.tvBack && window.tvBack()", null);
        }
    }

    /** WebView сам съедает KEYCODE_BACK/MENU, поэтому перехватываем в нём. */
    private class ShellWebView extends WebView {
        private Runnable longPress;

        ShellWebView(android.content.Context c) { super(c); }

        private void openTileMenu() {
            if (web != null) {
                web.evaluateJavascript("window.tvMenuKey && window.tvMenuKey()", null);
            }
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int code = event.getKeyCode();

            if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                handleBack();
                return true;
            }

            if (code == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
                openTileMenu();
                return true;
            }

            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        longPress = new Runnable() {
                            @Override
                            public void run() {
                                longPress = null;
                                openTileMenu();
                            }
                        };
                        postDelayed(longPress, 550);
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (longPress != null) {
                        removeCallbacks(longPress);
                        longPress = null;
                    }
                }
            }

            return super.dispatchKeyEvent(event);
        }
    }

    private boolean inputVisible() {
        return tvView != null && tvView.getVisibility() == View.VISIBLE;
    }

    private void showInput(final String inputId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (tvView == null) {
                        tvView = new TvView(MainActivity.this);
                        tvView.setFocusable(false);
                        tvView.setFocusableInTouchMode(false);
                        root.addView(tvView, new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT));
                    }
                    if (inputHint == null) {
                        inputHint = new TextView(MainActivity.this);
                        inputHint.setText("Назад — вернуться в оболочку");
                        inputHint.setTextSize(18);
                        inputHint.setTextColor(0xCCFFFFFF);
                        inputHint.setBackgroundColor(0x66000000);
                        inputHint.setPadding(40, 22, 40, 22);
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM | Gravity.START);
                        lp.leftMargin = 48;
                        lp.bottomMargin = 48;
                        root.addView(inputHint, lp);
                    }
                    inputHint.setVisibility(View.VISIBLE);
                    tvView.setVisibility(View.VISIBLE);
                    tvView.tune(inputId, null);
                    web.setVisibility(View.GONE);
                    inputHint.bringToFront();
                    // HDMI-поверхность появляется не сразу — повторяем настройку
                    final String retuneId = inputId;
                    tvView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (inputVisible()) tvView.tune(retuneId, null);
                            } catch (Exception ignored) { }
                        }
                    }, 900);
                } catch (Exception e) {
                    Log.w(TAG, "showInput", e);
                    Toast.makeText(MainActivity.this, "Не удалось открыть вход", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void hideInput() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (tvView != null) {
                        tvView.reset();
                        tvView.setVisibility(View.GONE);
                    }
                    if (inputHint != null) inputHint.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    web.evaluateJavascript("window.tvInputsClosed && window.tvInputsClosed()", null);
                } catch (Exception e) {
                    Log.w(TAG, "hideInput", e);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    web.evaluateJavascript("window.tvReload && window.tvReload(); window.tvHomeFocus && window.tvHomeFocus()", null);
                }
            }, 300);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            handleBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "key " + keyCode + " " + KeyEvent.keyCodeToString(keyCode));

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (inputVisible()) {
                hideInput();
                return true;
            }
            if (web != null) {
                web.evaluateJavascript("window.tvBack && window.tvBack()", null);
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MENU && web != null && !inputVisible()) {
            web.evaluateJavascript("window.tvMenuKey && window.tvMenuKey()", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean launch(String pkg, String act) {
        try {
            Intent i;
            if (act == null) {
                i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
            } else {
                i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName(pkg, act));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "launch " + pkg, e);
            return false;
        }
    }

    private void openGoogleTv() {
        
        if (!launch(GOOGLE_TV, GOOGLE_TV_ACT)) {
            Toast.makeText(this, "Лаунчер Google TV не найден на этом ТВ", Toast.LENGTH_SHORT).show();
        }
    }

    private String iconData(Drawable drawable) {
        try {
            int size = 72;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private String inputsJson() {
        JSONArray arr = new JSONArray();
        try {
            TvInputManager m = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (m == null) return arr.toString();
            List<TvInputInfo> list = new ArrayList<TvInputInfo>(m.getTvInputList());
            Collections.sort(list, new Comparator<TvInputInfo>() {
                @Override
                public int compare(TvInputInfo a, TvInputInfo b) {
                    int ta = a.getType(), tb = b.getType();
                    if (ta != tb) return ta - tb;
                    return a.getId().compareTo(b.getId());
                }
            });
            for (TvInputInfo info : list) {
                if (info.getType() == TvInputInfo.TYPE_TUNER) continue;
                JSONObject o = new JSONObject();
                o.put("id", info.getId());
                String label;
                try {
                    label = info.loadLabel(MainActivity.this).toString();
                } catch (Exception e) {
                    label = info.getId();
                }
                o.put("label", label);
                o.put("type", info.getType());
                arr.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "inputs", e);
        }
        return arr.toString();
    }

    public class Bridge {

        @JavascriptInterface
        public String getApps() {
            JSONArray arr = new JSONArray();
            HashSet<String> seen = new HashSet<String>();
            try {
                PackageManager pm = getPackageManager();
                Intent i = new Intent(Intent.ACTION_MAIN, null);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
                for (ResolveInfo ri : ris) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
                    if (pkg.equals(TV_SETTINGS)) continue;
                    seen.add(pkg);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("pkg", pkg);
                        o.put("label", ri.loadLabel(pm).toString());
                        o.put("icon", iconData(ri.loadIcon(pm)));
                        arr.put(o);
                    } catch (Exception e) {
                        Log.w(TAG, "app item", e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getApps", e);
            }
            return arr.toString();
        }

        @JavascriptInterface
        public String getInputs() {
            return inputsJson();
        }

        @JavascriptInterface
        public void openInput(String inputId) {
            // HDMI-поток система не отдаёт сторонним приложениям: открываем штатный селектор входов ТВ
            try {
                Intent i = new Intent("com.android.tv.action.VIEW_INPUTS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "viewInputs", e);
                try {
                    Intent i = new Intent();
                    i.setComponent(new ComponentName("com.mediatek.wwtv.tvcenter",
                            "com.mitv.livetv.input.SelectInputActivity"));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e2) {
                    Log.w(TAG, "selectInput", e2);
                    openSettings();
                }
            }
        }

        @JavascriptInterface
        public void closeInput() {
            hideInput();
        }

        @JavascriptInterface
        public void showInputs() {
            try {
                Intent i = new Intent("com.android.tv.action.VIEW_INPUTS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "showInputs", e);
                try {
                    Intent i = new Intent();
                    i.setComponent(new ComponentName("com.mediatek.wwtv.tvcenter",
                            "com.mitv.livetv.input.SelectInputActivity"));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e2) {
                    Log.w(TAG, "showInputs2", e2);
                    openSettings();
                }
            }
        }

        @JavascriptInterface
        public void startApp(String pkg) {
            if (!launch(pkg, null)) {
                Toast.makeText(MainActivity.this, "Не удалось запустить " + pkg, Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void uninstall(String pkg) {
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "uninstall " + pkg, e);
            }
        }

        @JavascriptInterface
        public void openSettings() {
            if (!launch(TV_SETTINGS, TV_SETTINGS_ACT)) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Log.w(TAG, "openSettings", e);
                }
            }
        }

        @JavascriptInterface
        public void openGoogleTv() {
            MainActivity.this.openGoogleTv();
        }

        @JavascriptInterface
        public void toast(String text) {
            Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
        }
    }
}
