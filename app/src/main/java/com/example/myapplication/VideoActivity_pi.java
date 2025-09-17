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

public class VideoActivity_pi extends AppCompatActivity {
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

    // UDP相关 - 树莓派
    private volatile String raspiIp = null;
    private DatagramSocket udpSocket;
    private Thread udpDiscoverThread;
    private final int AUTO_DETECT_INTERVAL_MS = 1000;
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
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        webView.draw(canvas);

        executor.execute(() -> {
            String result = onnxDetector.detect(bitmap);
            if (result != null && !result.isEmpty()) {
                mainHandler.post(() -> {
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

        // 启动树莓派发现
        discoverRaspberryPi();

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
            discoverRaspberryPi();
        });
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d("VideoActivity_pi", "页面开始加载: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d("VideoActivity_pi", "页面加载完成: " + url);

                if (!url.equals("about:blank") && raspiIp != null && url.contains(raspiIp)) {
                    mainHandler.postDelayed(() -> showVideoStream(), 2000);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e("VideoActivity_pi", "WebView错误: " + error.getDescription());
                showConnectionError("加载失败: " + error.getDescription().toString());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                String url = request.getUrl().toString();
                Log.e("VideoActivity_pi", "HTTP错误 - URL: " + url);
                int statusCode = errorResponse.getStatusCode();
                Log.e("VideoActivity_pi", "HTTP错误码: " + statusCode);

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

    // 发现树莓派设备
    private void discoverRaspberryPi() {
        showSearchingStatus();

        udpDiscoverThread = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setBroadcast(true);
                udpSocket.setSoTimeout(3000); // 3秒超时

                // 发送发现请求
                String discoverMsg = "DISCOVER_RASPI";
                byte[] data = discoverMsg.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName("255.255.255.255"),
                        45678
                );

                Log.d("UDP", "发送树莓派发现请求");
                udpSocket.send(packet);

                // 等待响应
                byte[] buffer = new byte[256];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(responsePacket);

                String response = new String(responsePacket.getData(), 0, responsePacket.getLength()).trim();
                Log.d("UDP", "收到树莓派响应: " + response);

                // 解析响应: RASPI:192.168.1.100:OFF
                if (response.startsWith("RASPI:")) {
                    String[] parts = response.split(":");
                    if (parts.length >= 3) {
                        String ip = parts[1];
                        String videoStatus = parts[2];

                        if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                            raspiIp = ip;
                            Log.d("UDP", "发现树莓派IP: " + ip + ", 视频状态: " + videoStatus);

                            runOnUiThread(() -> {
                                tvStatus.setText("发现树莓派: " + ip);
                                if ("ON".equals(videoStatus)) {
                                    // 视频已启动，直接连接
                                    checkDeviceConnection();
                                } else {
                                    // 启动视频流
                                    startRaspberryPiVideo();
                                }
                            });
                        }
                    }
                }

            } catch (Exception e) {
                Log.e("UDP", "发现树莓派失败: " + e.getMessage());
                runOnUiThread(() -> {
                    retryCount++;
                    if (retryCount < MAX_RETRY_COUNT) {
                        mainHandler.postDelayed(this::discoverRaspberryPi, 2000);
                    } else {
                        showConnectionError("未发现树莓派设备，请检查网络连接");
                    }
                });
            } finally {
                if (udpSocket != null && !udpSocket.isClosed()) {
                    udpSocket.close();
                }
            }
        });
        udpDiscoverThread.start();
    }

    // 启动树莓派视频流
    private void startRaspberryPiVideo() {
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(5000);

                // 发送启动视频命令
                String startCmd = "START_VIDEO";
                byte[] data = startCmd.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName("255.255.255.255"),
                        45678
                );

                Log.d("UDP", "发送启动视频命令");
                socket.send(packet);

                // 等待响应
                byte[] buffer = new byte[256];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket);

                String response = new String(responsePacket.getData(), 0, responsePacket.getLength()).trim();
                Log.d("UDP", "启动视频响应: " + response);

                if (response.startsWith("OK:")) {
                    runOnUiThread(() -> checkDeviceConnection());
                } else {
                    runOnUiThread(() -> showConnectionError("启动视频流失败"));
                }

                socket.close();

            } catch (Exception e) {
                Log.e("UDP", "启动视频流失败: " + e.getMessage());
                runOnUiThread(() -> showConnectionError("启动视频流失败: " + e.getMessage()));
            }
        }).start();
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
        if (udpDiscoverThread != null && udpDiscoverThread.isAlive()) {
            udpDiscoverThread.interrupt();
        }
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        detectHandler.removeCallbacks(detectRunnable);
        TTSPlayer.shutdown();
    }

    private void checkDeviceConnection() {
        if (raspiIp == null) {
            showConnectionError("未获取到树莓派IP");
            return;
        }

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
                        showConnectionError("连接树莓派失败");
                    }
                }
            });
        });
    }

    private boolean pingDevice() {
        if (raspiIp == null) return false;
        try {
            String urlStr = "http://" + raspiIp + ":5000/";
            Log.d("VideoActivity_pi", "开始ping树莓派: " + urlStr);
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            Log.d("VideoActivity_pi", "Ping响应码: " + responseCode);
            return responseCode == HttpURLConnection.HTTP_OK;

        } catch (IOException e) {
            Log.e("VideoActivity_pi", "Ping失败: " + e.getMessage());
            return false;
        }
    }

    private void loadVideoStream() {
        if (raspiIp == null) return;
        Log.d("VideoActivity_pi", "开始加载树莓派视频流");
        tvStatus.setText("连接成功，加载视频流...");

        String streamUrl = "http://" + raspiIp + ":5000/stream";
        String html = "<html><body style='margin:0;background:#000;display:flex;justify-content:center;align-items:center;height:100vh;width:100vw;'>" +
                "<img src='" + streamUrl + "' style='width:100vw;height:auto;display:block;' />" +
                "</body></html>";

        Log.d("VideoActivity_pi", "树莓派流URL: " + streamUrl);
        webView.loadDataWithBaseURL("http://" + raspiIp + ":5000/", html, "text/html", "UTF-8", null);
    }

    private void showSearchingStatus() {
        tvStatus.setText("搜索树莓派设备... (尝试 " + (retryCount + 1) + "/" + MAX_RETRY_COUNT + ")");
        progressBar.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
    }

    private void showVideoStream() {
        tvStatus.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        Toast.makeText(this, "树莓派视频流连接成功", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(VideoActivity_pi.this, sentence, Toast.LENGTH_LONG).show();
                            TTSPlayer.speak(sentence);
                        } else {
                            TTSPlayer.speak("识别失败，没有识别到文字");
                        }
                    } catch (Exception e) {
                        Toast.makeText(VideoActivity_pi.this, "解析出错：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    Toast.makeText(VideoActivity_pi.this, "上传失败：" + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}