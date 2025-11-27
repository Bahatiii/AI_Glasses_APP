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
    private PatrickAIEngine patrickAI;

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

    private volatile String raspiIp = null;
    private DatagramSocket udpSocket;
    private Thread udpDiscoverThread;

    // ========== 自动检测 + 播报部分 ==========
    private final Handler detectHandler = new Handler(Looper.getMainLooper());
    private static final long AUTO_DETECT_INTERVAL_MS = 15000; // 每15秒检测一次
    private static final long SPEAK_INTERVAL_MS = 8000; // 最小播报间隔
    private long lastSpeakTime = 0;

    private final Runnable detectRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (webView != null && webView.getWidth() > 0 && webView.getHeight() > 0) {
                    Bitmap bitmap = Bitmap.createBitmap(
                            webView.getWidth(),
                            webView.getHeight(),
                            Bitmap.Config.ARGB_8888
                    );
                    Canvas canvas = new Canvas(bitmap);
                    webView.draw(canvas);

                    // 调用百度交通识别接口
                    BaiduTraffic.detectTraffic(bitmap, new BaiduTraffic.TrafficCallback() {
                        @Override
                        public void onSuccess(String resultJson) {
                            Log.d("VideoActivity_pi", "✅ 百度识别返回 JSON: " + resultJson);
                            try {
                                JSONObject json = new JSONObject(resultJson);
                                JSONObject vehicleNum = json.optJSONObject("vehicle_num");
                                if (vehicleNum == null) {
                                    Log.d("VideoActivity_pi", "🚫 未识别到车辆字段");
                                    return;
                                }

                                int car = vehicleNum.optInt("car", 0);
                                int truck = vehicleNum.optInt("truck", 0);
                                int bus = vehicleNum.optInt("bus", 0);
                                int motorbike = vehicleNum.optInt("motorbike", 0);
                                int tricycle = vehicleNum.optInt("tricycle", 0);

                                int total = car + truck + bus + motorbike + tricycle;
                                if (total == 0) {
                                    Log.d("VideoActivity_pi", "🚫 未检测到车辆，不播报");
                                    return;
                                }

                                StringBuilder sb = new StringBuilder();
                                sb.append("前方检测到 ").append(total).append(" 辆车辆，");
                                if (car > 0) sb.append(car).append(" 辆小汽车，");
                                if (truck > 0) sb.append(truck).append(" 辆卡车，");
                                if (bus > 0) sb.append(bus).append(" 辆公交车，");
                                if (motorbike > 0) sb.append(motorbike).append(" 辆摩托车，");
                                if (tricycle > 0) sb.append(tricycle).append(" 辆三轮车，");

                                String speakText = sb.toString();
                                if (speakText.endsWith("，")) {
                                    speakText = speakText.substring(0, speakText.length() - 1);
                                }

                                long now = System.currentTimeMillis();
                                if (now - lastSpeakTime > SPEAK_INTERVAL_MS) {
                                    lastSpeakTime = now;
                                    TTSPlayer.speak(speakText);
                                    Log.d("VideoActivity_pi", "🔊 播报内容: " + speakText);
                                }
                            } catch (Exception e) {
                                Log.e("VideoActivity_pi", "❌ 解析百度返回 JSON 出错: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onError(String errorMessage) {
                            Log.e("VideoActivity_pi", "❌ 百度识别失败: " + errorMessage);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("VideoActivity_pi", "detectRunnable 出错: " + e.getMessage());
            }
            detectHandler.postDelayed(this, AUTO_DETECT_INTERVAL_MS);
        }
    };
    // =========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_video);

        // 初始化 PatrickAIEngine（与 Navigation/AIChat 的初始化风格相同）
        try {
            patrickAI = new PatrickAIEngine(this, text -> runOnUiThread(() -> {
                // 将 AI 的 UI 输出追加到状态栏，便于调试与查看
                if (tvStatus != null) tvStatus.append(text + "\n");
                Log.d("VideoActivity_pi", "Patrick UI: " + text);
            }));
            // 延迟启动欢迎语，确保 TTS 就绪
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (patrickAI != null) {
                    patrickAI.speak("已进入视频模式，可以向我提问或直接用语音交互");
                }
            }, 800);
            patrickAI.startListening();
        } catch (Exception e) {
            Log.e("VideoActivity_pi", "初始化 PatrickAIEngine 失败: " + e.getMessage(), e);
        }

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

        tvStatus.setText("正在初始化...");
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
        getOnBackPressedDispatcher().addCallback(callback);
    }

    private void discoverRaspberryPi() {
        showSearchingStatus();
        udpDiscoverThread = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setBroadcast(true);
                udpSocket.setSoTimeout(3000);

                String discoverMsg = "DISCOVER_RASPI";
                byte[] data = discoverMsg.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length,
                        InetAddress.getByName("255.255.255.255"), 45678);
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
                DatagramPacket packet = new DatagramPacket(data, data.length,
                        InetAddress.getByName("255.255.255.255"), 45678);
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

        // ✅ 启动自动识别任务
        detectHandler.postDelayed(detectRunnable, 4000);
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

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        webView.draw(canvas);

        Toast.makeText(this, "截图成功，正在上传识别", Toast.LENGTH_SHORT).show();
        Log.d("OCR_DEBUG", "captureAndUploadFrame: 调用BaiduImageUploader");

        BaiduImageUploader.uploadImage(bitmap, new BaiduImageUploader.UploadCallback() {
            @Override
            public void onSuccess(String resultJson) {
                Log.d("OCR_DEBUG", "上传成功 JSON: " + resultJson);
                // 将图片识别结果转发给 PatrickAI 做后续对话（保持原有上传日志）
                try {
                    if (patrickAI != null) {
                        patrickAI.onInput("图片识别结果：" + resultJson);
                    }
                } catch (Exception e) {
                    Log.e("VideoActivity_pi", "转发图片识别给 PatrickAI 失败: " + e.getMessage());
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e("OCR_DEBUG", "上传失败: " + errorMessage);
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
        detectHandler.removeCallbacks(detectRunnable);
        TTSPlayer.shutdown();
        // 销毁/释放 Patrick 引擎，避免内存泄漏
        try {
            if (patrickAI != null) {
                patrickAI.destroy();
                patrickAI = null;
            }
        } catch (Exception e) {
            Log.e("VideoActivity_pi", "销毁 PatrickAIEngine 失败: " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (patrickAI != null) patrickAI.pauseListening();
        } catch (Exception e) {
            Log.e("VideoActivity_pi", "onPause patrickAI pause 失败: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (patrickAI != null) patrickAI.startListening();
        } catch (Exception e) {
            Log.e("VideoActivity_pi", "onResume patrickAI startListening 失败: " + e.getMessage());
        }
    }

    public boolean handleUserVoiceInput(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.contains("这是什么") || t.contains("画面是什么") || t.contains("现在前面是什么") || t.contains("前面是什么") || t.contains("这是谁") || t.contains("识别一下")) {
            performVisualRecognition(t);
            return true;
        }
        return false;
    }

    // 执行一次性视觉识别：先尝试交通/目标检测（BaiduTraffic），若未检测到车辆则回退到图片 OCR（BaiduImageUploader）
    private void performVisualRecognition(String userQuery) {
        try {
            if (webView == null || webView.getWidth() == 0 || webView.getHeight() == 0) {
                if (patrickAI != null) patrickAI.speak("当前画面不可用，无法识别");
                return;
            }
            Bitmap bitmap = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            webView.draw(canvas);

            // 先做交通/目标检测（更适合询问前面有什么车）
            BaiduTraffic.detectTraffic(bitmap, new BaiduTraffic.TrafficCallback() {
                @Override
                public void onSuccess(String resultJson) {
                    try {
                        JSONObject json = new JSONObject(resultJson);
                        JSONObject vehicleNum = json.optJSONObject("vehicle_num");
                        int total = 0;
                        if (vehicleNum != null) {
                            int car = vehicleNum.optInt("car", 0);
                            int truck = vehicleNum.optInt("truck", 0);
                            int bus = vehicleNum.optInt("bus", 0);
                            int motorbike = vehicleNum.optInt("motorbike", 0);
                            int tricycle = vehicleNum.optInt("tricycle", 0);
                            total = car + truck + bus + motorbike + tricycle;
                            if (total > 0) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("识别到 ").append(total).append(" 辆车辆，");
                                if (car > 0) sb.append(car).append(" 辆小汽车，");
                                if (truck > 0) sb.append(truck).append(" 辆卡车，");
                                if (bus > 0) sb.append(bus).append(" 辆公交车，");
                                if (motorbike > 0) sb.append(motorbike).append(" 辆摩托车，");
                                if (tricycle > 0) sb.append(tricycle).append(" 辆三轮车，");
                                String speakText = sb.toString();
                                if (speakText.endsWith("，")) speakText = speakText.substring(0, speakText.length()-1);
                                if (patrickAI != null) {
                                    patrickAI.speak("我看到：" + speakText);
                                    patrickAI.onInput("图片识别结果：" + resultJson);
                                } else {
                                    TTSPlayer.speak("我看到：" + speakText);
                                }
                                return;
                            }
                        }

                        BaiduImageUploader.uploadImage(bitmap, new BaiduImageUploader.UploadCallback() {
                            @Override
                            public void onSuccess(String ocrJson) {
                                try {
                                    if (patrickAI != null) {
                                        patrickAI.speak("识别结果已返回，请稍等片刻，我正在帮你理解");
                                        patrickAI.onInput("图片识别结果：" + ocrJson);
                                    } else {
                                        TTSPlayer.speak("识别结果：" + ocrJson);
                                    }
                                } catch (Exception e) {
                                    Log.e("VideoActivity_pi", "performVisualRecognition OCR 回调处理失败: " + e.getMessage());
                                }
                            }

                            @Override
                            public void onError(String errorMessage) {
                                Log.e("VideoActivity_pi", "performVisualRecognition OCR 失败: " + errorMessage);
                                if (patrickAI != null) patrickAI.speak("图像识别失败：" + errorMessage);
                                else TTSPlayer.speak("图像识别失败");
                            }
                        });
                    } catch (Exception e) {
                        Log.e("VideoActivity_pi", "performVisualRecognition 解析 traffic 结果失败: " + e.getMessage());
                        if (patrickAI != null) patrickAI.speak("识别失败：" + e.getMessage());
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    Log.e("VideoActivity_pi", "performVisualRecognition traffic 失败: " + errorMessage);
                    // 当流量检测失败时，退回到 OCR
                    BaiduImageUploader.uploadImage(bitmap, new BaiduImageUploader.UploadCallback() {
                        @Override
                        public void onSuccess(String ocrJson) {
                            if (patrickAI != null) {
                                patrickAI.speak("识别结果已返回，我已转发给AI进行理解。");
                                patrickAI.onInput("图片识别结果：" + ocrJson);
                            } else {
                                TTSPlayer.speak("识别结果：" + ocrJson);
                            }
                        }

                        @Override
                        public void onError(String errorMessage2) {
                            Log.e("VideoActivity_pi", "performVisualRecognition OCR 失败: " + errorMessage2);
                            if (patrickAI != null) patrickAI.speak("图像识别失败：" + errorMessage2);
                            else TTSPlayer.speak("图像识别失败");
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e("VideoActivity_pi", "performVisualRecognition 异常: " + e.getMessage());
            if (patrickAI != null) patrickAI.speak("识别发生异常：" + e.getMessage());
        }
    }
}
