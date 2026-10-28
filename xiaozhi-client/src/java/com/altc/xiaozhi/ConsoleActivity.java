package com.altc.xiaozhi;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 控制台页。
 *
 * 这台设备没装浏览器；系统 WebView 只有 Chrome 61，跑不动 xiaozhi.me 的现代
 * 前端（实测报 Uncaught SyntaxError: Unexpected token import → 白屏）。
 *
 * 所以默认展示**原生信息页**（把能从设备侧同步到的信息都列出来），
 * 顶部「打开网页」才切到内嵌浏览器去尝试加载控制台。
 */
public class ConsoleActivity extends Activity {

    public static final String CONSOLE_URL = "https://xiaozhi.me/console/agents";

    private WebView web;
    private ProgressBar progress;
    private View infoScroll;
    private View webBox;
    private TextView reload;
    private boolean webMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_console);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        infoScroll = findViewById(R.id.info_scroll);
        webBox = findViewById(R.id.web_box);
        reload = findViewById(R.id.reload);

        findViewById(R.id.back).setOnClickListener(v -> {
            if (webMode) {
                showInfo();
            } else {
                finish();
            }
        });

        reload.setOnClickListener(v -> {
            if (!webMode) {
                showWeb();
            } else {
                web.reload();
            }
        });

        fillInfo();

        // 手机/平板版 WebView 是现代内核，可直接打开控制台网页；
        // 音箱版（旧 WebView 61 会白屏）保持原生信息页。
        if (!AppConfig.IS_SPEAKER) {
            showWeb();
        }
    }

    private void fillInfo() {
        SharedPreferences sp = getSharedPreferences("xz", MODE_PRIVATE);
        TextView tvDevice = findViewById(R.id.tv_device);
        TextView tvState = findViewById(R.id.tv_state);

        tvDevice.setText("device-id：" + sp.getString("deviceId", "-")
                + "\nclient-id：" + sp.getString("clientId", "-")
                + "\nWebSocket：" + sp.getString("url", XiaoZhiClient.DEFAULT_URL)
                + "\nToken：" + mask(sp.getString("token", "-")));

        boolean bound = sp.getBoolean("bound", false);
        long at = sp.getLong("lastOtaAt", 0);
        String time = at > 0
                ? new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(at))
                : "尚未拉取";

        tvState.setText("绑定状态：" + (bound ? "已绑定（云端未返回验证码）" : "未绑定或未同步")
                + "\n最近一次 OTA：" + time
                + "\n\n连续对话：" + onOff(sp.getBoolean("continuous", false))
                + "\n摄像头：" + onOff(sp.getBoolean("cameraEnabled", true))
                + "\n麦克风（软件层）：" + onOff(sp.getBoolean("micEnabled", true))
                + "\n本地留存照片：" + onOff(sp.getBoolean("keepPhoto", false)));
    }

    private String onOff(boolean b) {
        return b ? "开" : "关";
    }

    /** 令牌只露前 6 位：这页是给人看绑定状态的，不是用来抄凭据的。 */
    private static String mask(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) return "-";
        if (s.length() <= 8) return "****";
        return s.substring(0, 6) + "…（共 " + s.length() + " 位）";
    }

    private void showInfo() {
        webMode = false;
        webBox.setVisibility(View.GONE);
        infoScroll.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        reload.setText("打开网页");
        fillInfo();     // 回到信息页时刷新一次
    }

    private void showWeb() {
        webMode = true;
        infoScroll.setVisibility(View.GONE);
        webBox.setVisibility(View.VISIBLE);
        reload.setText("刷新");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(115);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progress.setProgress(newProgress);
            }
        });

        progress.setVisibility(View.VISIBLE);
        web.loadUrl(CONSOLE_URL);
    }

    @Override
    public void onBackPressed() {
        if (webMode) {
            if (web.canGoBack()) {
                web.goBack();
            } else {
                showInfo();
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
