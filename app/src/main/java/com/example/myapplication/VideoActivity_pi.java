package com.example.myapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.json.JSONArray;
import org.json.JSONObject;

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
    // 删除 OnnxDetector 实例
    // private OnnxDetector onnxDetector;

    private volatile String raspiIp = null;
    private DatagramSocket udpSocket;
    private Thread udpDiscoverThread;

    // 删除自动检测相关的 Handler 和 Runnable
    // private final int AUTO_DETECT_INTERVAL_MS = 1000;
    // private final Handler detectHandler = new Handler(Looper.getMainLooper());
    // private final Runnable detectRunnable = new Runnable() {
    //     @Override
    //     public void run() {
    //         autoDetectFrame();
    //         detectHandler.postDelayed(this, AUTO_DETECT_INTERVAL_MS);
    //     }
    // };

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

        // 删除 OnnxDetector 的初始化和状态检查
        // onnxDetector = new OnnxDetector(this);
        // if (onnxDetector != null && onnxDetector.isModelLoaded()) {
        //     tvStatus.setText("模型加载成功 ✅");
        //     Log.d("VideoActivity_pi", "OnnxDetector 初始化成功");
        // } else {
        //     tvStatus.setText("模型加载失败 ❌");
        //     Log.e("VideoActivity_pi", "OnnxDetector 初始化失败");
        // }
        tvStatus.setText("正在初始化..."); // 可以设置一个默认的初始状态文本

        // 启动树莓派发现
        discoverRaspberryPi();

        btnCapture.setOnClickListener(v -> captureAndUploadFrame());
    }

    private void initViews() {
        webView = findViewById(R.id.webview_video);
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);
        btnRetry = findViewById(R.id.btn_retry);
        btnCapture = findViewById(R.id.btn_capture);

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
            public void onPageFinished(WebView view, String url) {
                if (!url.equals("about:blank") && raspiIp != null && url.contains(raspiIp)) {
                    mainHandler.postDelayed(VideoActivity_pi.this::showVideoStream, 2000);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                showConnectionError("加载失败: " + error.getDescription());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.getUrl().toString().contains("stream")) {
                    showConnectionError("视频流连接失败: " + errorResponse.getStatusCode());
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

    // 删除整个 autoDetectFrame 方法
    // private void autoDetectFrame() {
    //     if (webView.getWidth() == 0 || webView.getHeight() == 0
    //             || onnxDetector == null || !onnxDetector.isModelLoaded()) {
    //         return;
    //     }
    //
    //     Bitmap bitmap = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
    //     Canvas canvas = new Canvas(bitmap);
    //     webView.draw(canvas);
    //
    //     executor.execute(() -> {
    //         String result = onnxDetector.detect(bitmap);
    //         if (result != null && !result.isEmpty()) {
    //             Log.d("VideoActivity_pi", "检测结果: " + result);
    //         }
    //     });
    // }

    private void discoverRaspberryPi() {
        showSearchingStatus();
        udpDiscoverThread = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setBroadcast(true);
                udpSocket.setSoTimeout(3000);

                String discoverMsg = "DISCOVER_RASPI";
                byte[] data = discoverMsg.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), 45678);
                udpSocket.send(packet);

                byte[] buffer = new byte[256];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength()).trim();

                if (response.startsWith("RASPI:")) {
                    String[] parts = response.split(":");
                    if (parts.length >= 3) {
                        String ip = parts[1];
                        String videoStatus = parts[2];
                        if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                            raspiIp = ip;
                            runOnUiThread(() -> {
                                tvStatus.setText("发现树莓派: " + ip);
                                if ("ON".equals(videoStatus)) {
                                    VideoActivity_pi.this.checkDeviceConnection();
                                } else {
                                    startRaspberryPiVideo();
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    retryCount++;
                    if (retryCount < MAX_RETRY_COUNT) {
                        mainHandler.postDelayed(VideoActivity_pi.this::discoverRaspberryPi, 2000);
                    } else {
                        showConnectionError("未发现树莓派设备，请检查网络");
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

    private void startRaspberryPiVideo() {
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(5000);

                String startCmd = "START_VIDEO";
                byte[] data = startCmd.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), 45678);
                socket.send(packet);

                byte[] buffer = new byte[256];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength()).trim();

                if (response.startsWith("OK:")) {
                    runOnUiThread(VideoActivity_pi.this::checkDeviceConnection);
                } else {
                    runOnUiThread(() -> showConnectionError("启动视频流失败"));
                }
                socket.close();
            } catch (Exception e) {
                runOnUiThread(() -> showConnectionError("启动视频流失败: " + e.getMessage()));
            }
        }).start();
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
                        mainHandler.postDelayed(VideoActivity_pi.this::checkDeviceConnection, 2000);
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
            URL url = new URL("http://" + raspiIp + ":5000/");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
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
        if (raspiIp == null) return;
        tvStatus.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        String streamUrl = "http://" + raspiIp + ":5000/stream";
        String html = "<html><body style='margin:0;background:#000;display:flex;justify-content:center;align-items:center;height:100vh;width:100vw;'>"
                + "<img src='" + streamUrl + "' style='width:100vw;height:auto;display:block;' />"
                + "</body></html>";
        webView.loadDataWithBaseURL("http://" + raspiIp + ":5000/", html, "text/html", "UTF-8", null);

        // 删除启动自动检测的调用
        // if (onnxDetector != null && onnxDetector.isModelLoaded()) {
        //     detectHandler.postDelayed(detectRunnable, AUTO_DETECT_INTERVAL_MS);
        // }
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
    }

    private void showConnectionError(String message) {
        tvStatus.setText(message);
        progressBar.setVisibility(View.GONE);
        btnRetry.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void captureAndUploadFrame() {
        Log.d("OCR_DEBUG", "captureAndUploadFrame: 开始截图和上传");
        int width = webView.getWidth();
        int height = webView.getHeight();
        if (width == 0 || height == 0) {
            Toast.makeText(this, "WebView 大小为 0，无法截图", Toast.LENGTH_SHORT).show();
            Log.e("OCR_DEBUG", "captureAndUploadFrame: WebView大小为0，无法截图");
            return;
        }
        Log.d("OCR_DEBUG", "captureAndUploadFrame: WebView尺寸: " + width + "x" + height);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        webView.draw(canvas);

        Toast.makeText(this, "截图成功，正在上传识别", Toast.LENGTH_SHORT).show();
        Log.d("OCR_DEBUG", "captureAndUploadFrame: 截图成功，调用BaiduImageUploader");

        BaiduImageUploader.uploadImage(bitmap, new BaiduImageUploader.UploadCallback() {
            @Override
            public void onSuccess(String resultJson) {
                Log.d("OCR_DEBUG", "onSuccess: 收到成功回调，JSON: " + resultJson);
                runOnUiThread(() -> {
                    try {
                        JSONObject json = new JSONObject(resultJson);
                        StringBuilder sb = new StringBuilder();
                        if (json.has("words_result")) {
                            JSONArray wordsArray = json.getJSONArray("words_result");
                            for (int i = 0; i < wordsArray.length(); i++) {
                                JSONObject obj = wordsArray.getJSONObject(i);
                                sb.append(obj.getString("words"));
                                if (i < wordsArray.length() - 1) sb.append("，");
                            }
                        } else if (json.has("error_msg")) {
                            String errorMsg = json.getString("error_msg");
                            sb.append("识别失败: ").append(errorMsg);
                            Log.e("OCR_DEBUG", "百度API返回错误: " + errorMsg);
                        } else {
                            sb.append("未识别到可用结果");
                        }
                        String sentence = sb.toString();
                        Log.d("OCR_DEBUG", "解析后的句子: " + sentence);
                        Toast.makeText(VideoActivity_pi.this, sentence, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e("OCR_DEBUG", "解析JSON时出错", e);
                        Toast.makeText(VideoActivity_pi.this, "解析出错：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                Log.e("OCR_DEBUG", "onError: 收到错误回调: " + errorMessage);
                runOnUiThread(() -> Toast.makeText(VideoActivity_pi.this, "上传失败：" + errorMessage, Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
        if (webView != null) webView.destroy();
        if (udpDiscoverThread != null && udpDiscoverThread.isAlive()) udpDiscoverThread.interrupt();
        if (udpSocket != null && !udpSocket.isClosed()) udpSocket.close();
        // 删除移除 Runnable 的调用
        // detectHandler.removeCallbacks(detectRunnable);
    }
}
