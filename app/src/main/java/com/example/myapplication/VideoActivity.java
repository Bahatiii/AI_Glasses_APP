package com.example.myapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoActivity extends AppCompatActivity {
    private WebView webView;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private Button btnRetry;

    private static final String STREAM_URL = "http://esp32-glasses.local/stream";
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_RETRY_COUNT = 3;

    private ExecutorService executor;
    private Handler mainHandler;
    private int retryCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_video);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupWebView();
        setupBackPressedCallback();

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 开始检测设备
        checkDeviceConnection();
    }

    private void initViews() {
        webView = findViewById(R.id.webview_video);
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);
        btnRetry = findViewById(R.id.btn_retry);

        btnRetry.setOnClickListener(v -> {
            retryCount = 0;
            checkDeviceConnection();
        });
    }

    private void setupWebView() {
        // 只在确实需要JavaScript时才启用（这里是为了处理图片加载错误）
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        // 添加安全设置
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                showVideoStream();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    showConnectionError("Loading video failed: " + error.getDescription().toString());
                } else {
                    showConnectionError("Loading video failed");
                }
            }
        });
    }

    private void setupBackPressedCallback() {
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    private void checkDeviceConnection() {
        showSearchingStatus();

        executor.execute(() -> {
            boolean isConnected = pingDevice();

            mainHandler.post(() -> {
                if (isConnected) {
                    loadVideoStream();
                } else {
                    retryCount++;
                    if (retryCount < MAX_RETRY_COUNT) {
                        // 延迟后重试
                        mainHandler.postDelayed(this::checkDeviceConnection, 2000);
                    } else {
                        showConnectionError("No devices found, please check");
                    }
                }
            });
        });
    }

    private boolean pingDevice() {
        try {
            URL url = new URL(STREAM_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (IOException e) {
            return false;
        }
    }

    private void loadVideoStream() {
        tvStatus.setText("Successfully connected, loading video...");

        String html = "<html><body style='margin:0;padding:0;background:#000;'>" +
                "<img src='" + STREAM_URL + "' style='width:100%;height:100%;object-fit:contain;' " +
                "onerror=\"document.body.innerHTML='<div style=\\'color:white;text-align:center;padding-top:50%;\\'>视频流加载失败</div>'\" />" +
                "</body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void showSearchingStatus() {
        tvStatus.setText("Finding devices... (Try " + (retryCount + 1) + "/" + MAX_RETRY_COUNT + ")");
        progressBar.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
    }

    private void showVideoStream() {
        tvStatus.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        Toast.makeText(this, "视频流连接成功", Toast.LENGTH_SHORT).show();
    }

    private void showConnectionError(String message) {
        tvStatus.setText(message);
        progressBar.setVisibility(View.GONE);
        btnRetry.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (webView != null) {
            webView.destroy();
        }
    }
}