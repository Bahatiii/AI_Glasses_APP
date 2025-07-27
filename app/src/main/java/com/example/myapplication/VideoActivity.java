package com.example.myapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.webkit.WebResourceResponse;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.json.JSONArray;
import org.json.JSONObject;
import com.example.myapplication.TTSPlayer;

public class VideoActivity extends AppCompatActivity {
    private WebView webView;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private Button btnRetry;
    private Button btnCapture;

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_RETRY_COUNT = 3;

    private ExecutorService executor;
    private Handler mainHandler;
    private int retryCount = 0;
    private OnnxDetector onnxDetector;


    // UDP相关
    private volatile String esp32Ip = null;
    private DatagramSocket udpSocket;
    private Thread udpListenThread;
    private final int AUTO_DETECT_INTERVAL_MS = 1000; // 1秒一次，可调
    private final Handler detectHandler = new Handler(Looper.getMainLooper());
    private final Runnable detectRunnable = new Runnable() {
        @Override
        public void run() {
            autoDetectFrame();
            detectHandler.postDelayed(this, AUTO_DETECT_INTERVAL_MS);
        }
    };

    private void autoDetectFrame() {
        if (webView.getWidth() == 0 || webView.getHeight() == 0) {
            return; // WebView未布局好，跳过
        }
        Bitmap bitmap = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        webView.draw(canvas);

        // ONNX模型推理
        executor.execute(() -> {
            String result = onnxDetector.detect(bitmap);
            if (result != null && !result.isEmpty()) {
                mainHandler.post(() -> {
                    // 播报识别结果
                    TTSPlayer.speak("前方检测到：" + result);
                });
            }
        });
    }

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
        TTSPlayer.init(this);
        onnxDetector = new OnnxDetector(this);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        startUdpListen(); // 启动UDP监听

        btnCapture = findViewById(R.id.btn_capture);
        btnCapture.setOnClickListener(v -> captureAndUploadFrame());
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
        WebSettings settings = webView.getSettings();

        // 基本设置
        // settings.setJavaScriptEnabled(true); // 不需要JS可注释
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // 缓存设置
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // 网络和媒体设置
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // 安全设置
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        // 禁用硬件加速（对某些设备的MJPEG支持有帮助）
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d("VideoActivity", "页面开始加载: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d("VideoActivity", "页面加载完成: " + url);

                // 只有当URL不是about:blank且包含当前esp32Ip时才显示视频流
                if (!url.equals("about:blank") && esp32Ip != null && url.contains(esp32Ip)) {
                    mainHandler.postDelayed(() -> showVideoStream(), 2000);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e("VideoActivity", "WebView错误: " + error.getDescription());
                showConnectionError("加载失败: " + error.getDescription().toString());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);

                String url = request.getUrl().toString();
                Log.e("VideoActivity", "HTTP错误 - URL: " + url);

                int statusCode = errorResponse.getStatusCode();
                Log.e("VideoActivity", "HTTP错误码: " + statusCode);

                if (url.contains("stream")) {
                    showConnectionError("视频流连接失败: " + statusCode);
                }

            }
        });

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.d("WebView", consoleMessage.message());
                return true;
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

    // UDP监听线程，自动获取ESP32 IP
    private void startUdpListen() {
        udpListenThread = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket(45678, InetAddress.getByName("0.0.0.0"));
                udpSocket.setBroadcast(true);
                byte[] buf = new byte[64];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                    if (msg.startsWith("ESP32CAM:")) {
                        String ip = msg.substring("ESP32CAM:".length());
                        if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                            Log.d("UDP", "收到ESP32 IP: " + ip);
                            if (!ip.equals(esp32Ip)) {
                                esp32Ip = ip;
                                runOnUiThread(this::checkDeviceConnection);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("UDP", "UDP监听异常: " + e.getMessage());
            }
        });
        udpListenThread.start();
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
        if (udpListenThread != null && udpListenThread.isAlive()) {
            udpListenThread.interrupt();
        }
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        detectHandler.removeCallbacks(detectRunnable);
        TTSPlayer.shutdown();
    }

    private void checkDeviceConnection() {
        if (esp32Ip == null) {
            showConnectionError("未收到ESP32 IP广播，请确认设备已上电且与手机同一WiFi");
            return;
        }
        showSearchingStatus();

        executor.execute(() -> {
            boolean isConnected = pingDevice();

            mainHandler.post(() -> {
                if (isConnected) {
                    loadVideoStream();
                } else {
                    retryCount++;
                    if (retryCount < MAX_RETRY_COUNT) {
                        mainHandler.postDelayed(this::checkDeviceConnection, 2000);
                    } else {
                        showConnectionError("No devices found, please check");
                    }
                }
            });
        });
    }

    private boolean pingDevice() {
        if (esp32Ip == null) return false;
        try {
            String urlStr = "http://" + esp32Ip + "/";
            Log.d("VideoActivity", "开始ping ESP32: " + urlStr);
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            Log.d("VideoActivity", "Ping响应码: " + responseCode);
            return responseCode == HttpURLConnection.HTTP_OK;

        } catch (IOException e) {
            Log.e("VideoActivity", "Ping失败: " + e.getMessage());
            return false;
        }
    }

    private void loadVideoStream() {
        if (esp32Ip == null) return;
        Log.d("VideoActivity", "开始加载视频流");
        tvStatus.setText("Successfully connected, loading video...");

        String streamUrl = "http://" + esp32Ip + "/stream";
        // 让图片宽度100%，高度自适应，始终铺满页面宽度
        String html = "<html><body style='margin:0;background:#000;display:flex;justify-content:center;align-items:center;height:100vh;width:100vw;'>" +
                "<img src='" + streamUrl + "' style='width:100vw;height:auto;display:block;' />" +
                "</body></html>";

        Log.d("VideoActivity", "HTML: " + html);
        Log.d("VideoActivity", "目标URL: " + streamUrl);

        webView.loadDataWithBaseURL("http://" + esp32Ip + "/", html, "text/html", "UTF-8", null);
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
        detectHandler.postDelayed(detectRunnable, AUTO_DETECT_INTERVAL_MS);
    }

    private void showConnectionError(String message) {
        tvStatus.setText(message);
        progressBar.setVisibility(View.GONE);
        btnRetry.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void captureAndUploadFrame() {
        int width = webView.getWidth();
        int height = webView.getHeight();
        if (width == 0 || height == 0) {
            Toast.makeText(this, "WebView 大小为 0，无法截图", Toast.LENGTH_SHORT).show();
            return;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        webView.draw(canvas);

        Toast.makeText(this, "截图成功，正在上传识别", Toast.LENGTH_SHORT).show();

        BaiduImageUploader.uploadImage(bitmap, new BaiduImageUploader.UploadCallback() {
            @Override
            public void onSuccess(String resultJson) {
                runOnUiThread(() -> {
                    try {
                        JSONObject json = new JSONObject(resultJson);
                        JSONArray wordsArray = json.getJSONArray("words_result");
                        if (wordsArray.length() > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < wordsArray.length(); i++) {
                                JSONObject obj = wordsArray.getJSONObject(i);
                                String word = obj.getString("words");
                                sb.append(word);
                                if (i < wordsArray.length() - 1) {
                                    sb.append("，");
                                }
                            }
                            String sentence = sb.toString();
                            Toast.makeText(VideoActivity.this, sentence, Toast.LENGTH_LONG).show();
                            TTSPlayer.speak(sentence);
                        } else {
                            TTSPlayer.speak("识别失败，没有识别到文字");
                        }
                    } catch (Exception e) {
                        Toast.makeText(VideoActivity.this, "解析出错：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    Toast.makeText(VideoActivity.this, "上传失败：" + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}