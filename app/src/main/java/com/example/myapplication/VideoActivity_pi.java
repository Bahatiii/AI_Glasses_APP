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
/*
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
*/

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
        //detectHandler.postDelayed(detectRunnable, 4000);
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

        Bitmap rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rawBitmap);
        webView.draw(canvas);

        // --- 按最长边缩放到 640px（防止 OOM / 大流量） ---
        int maxEdge = 640;
        int w = rawBitmap.getWidth(), h = rawBitmap.getHeight();
        if (Math.max(w, h) > maxEdge) {
            float scale = (float) maxEdge / Math.max(w, h);
            Bitmap scaled = Bitmap.createScaledBitmap(rawBitmap, Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            try { rawBitmap.recycle(); } catch (Exception ignored) {}
            rawBitmap = scaled;
        }

        // 固定为 final，供匿名内部类安全使用
        final Bitmap bmpToUpload = rawBitmap;

        Toast.makeText(this, "截图成功，正在上传识别", Toast.LENGTH_SHORT).show();
        Log.d("OCR_DEBUG", "captureAndUploadFrame: 调用BaiduImageUploader");

        BaiduImageUploader.uploadImage(bmpToUpload, new BaiduImageUploader.UploadCallback() {
            @Override
            public void onSuccess(String resultJson) {
                Log.d("OCR_DEBUG", "上传成功 JSON: " + resultJson);
                try {
                    if (patrickAI != null) {
                        patrickAI.speak("文字识别已返回，我来理解结果");
                        String prompt = "图片识别结果：" + resultJson + "，请将结果润色成自然口语并简短返回。";
                        patrickAI.callAI(prompt, aiResult -> {
                            try {
                                if (aiResult != null && !aiResult.trim().isEmpty()) {
                                    patrickAI.speak(aiResult);
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                } catch (Exception e) {
                    Log.e("VideoActivity_pi", "转发图片识别给 PatrickAI 失败: " + e.getMessage());
                } finally {
                    try { if (bmpToUpload != null && !bmpToUpload.isRecycled()) bmpToUpload.recycle(); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e("OCR_DEBUG", "上传失败: " + errorMessage);
                try { if (bmpToUpload != null && !bmpToUpload.isRecycled()) bmpToUpload.recycle(); } catch (Exception ignored) {}
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
        if (pendingRecognitionBitmap != null && !pendingRecognitionBitmap.isRecycled()) {
            try { pendingRecognitionBitmap.recycle(); } catch (Exception ignored) {}
            pendingRecognitionBitmap = null;
        }
        //detectHandler.removeCallbacks(detectRunnable);
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

    // 新增：等待用户选择识别类型的状态与缓存截图
    private volatile boolean awaitingRecognitionChoice = false;
    private Bitmap pendingRecognitionBitmap = null;
    // 新增：当语音已包含“文字”相关关键词时，等待用户确认是否要识别文字
    private volatile boolean expectingTextConfirmation = false;

    public boolean handleUserVoiceInput(String text) {
        Log.d("VideoActivity_pi", "handleUserVoiceInput called, text=[" + text + "], awaitingRecognitionChoice=" + awaitingRecognitionChoice);
        if (text == null) return false;
        String t = text.trim();
        Log.d("VideoActivity_pi", "handleUserVoiceInput normalized t=[" + t + "]");
        // 如果正在等待用户在“文字/物体”间做选择，优先处理该回答
        if (awaitingRecognitionChoice) {
            handleRecognitionChoice(t);
            return true;
        }
        // 扩展触发词：兼容常见说法（“识别文字”、“识别图片”、“识别+...” 等）
        if (t.contains("这是什么") || t.contains("画面是什么") || t.contains("现在前面是什么")
                || t.contains("前面是什么") || t.contains("这是谁") || t.contains("识别一下")
                || t.contains("识别文字") || t.contains("识别文本") || t.contains("识别图片")
                || (t.contains("识别") && (t.contains("字") || t.contains("文") || t.contains("图") || t.length() <= 4))) {
            performVisualRecognition(t);
            return true;
        }

        return false;
    }

    // 修改：performVisualRecognition -> 截图并根据语音内容直接走物体识别或等待确认文字识别
    private void performVisualRecognition(String userQuery) {
        try {
            if (webView == null || webView.getWidth() == 0 || webView.getHeight() == 0) {
                if (patrickAI != null) patrickAI.speak("当前画面不可用，无法识别");
                return;
            }
            Bitmap bitmap = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            webView.draw(canvas);

            // 检查用户语音是否显式提到“文字/字/识别文字”等关键词
            String low = userQuery == null ? "" : userQuery.toLowerCase();
            boolean mentionsText = false;
            String[] textKeywords = new String[] {
                    "识别文字", "识别文本", "识别字", "识别一下文字", "识别一下字", "文字", "看一下文字", "看一下字", "读一下"
            };
            for (String k : textKeywords) {
                if (low.contains(k)) { mentionsText = true; break; }
            }
            if (mentionsText) {
                // 需要确认：先回收旧的 pendingRecognitionBitmap（若存在），再缓存当前截图
                try { if (pendingRecognitionBitmap != null && !pendingRecognitionBitmap.isRecycled()) pendingRecognitionBitmap.recycle(); } catch (Exception ignored) {}
                pendingRecognitionBitmap = bitmap;
                awaitingRecognitionChoice = true;
                expectingTextConfirmation = true;
                if (patrickAI != null) patrickAI.speak("你是要识别文字吗？");
                else TTSPlayer.speak("你是要识别文字吗？");
            } else {
                // 直接走物体识别（使用树莓派的 /api/detect），不保留截图
                try { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); } catch (Exception ignored) {}
                awaitingRecognitionChoice = false;
                expectingTextConfirmation = false;
                triggerRemoteYoloDetection();
            }

        } catch (Exception e) {
            Log.e("VideoActivity_pi", "performVisualRecognition 异常: " + e.getMessage());
            if (patrickAI != null) patrickAI.speak("识别发生异常");
        }
    }

    // 新增：处理用户对“文字还是物体”的回答
    private void handleRecognitionChoice(String userReply) {
        // 清标志由后续流程决定（避免重复）
        awaitingRecognitionChoice = false;

        if (pendingRecognitionBitmap == null) {
            expectingTextConfirmation = false;
            if (patrickAI != null) patrickAI.speak("没有可识别的画面。请再试一次。");
            else TTSPlayer.speak("没有可识别的画面。请再试一次。");
            return;
        }

        String lower = userReply.toLowerCase();
        boolean answerYes = lower.contains("是") || lower.contains("对") || lower.contains("好的") || lower.contains("确定") || lower.contains("要") || lower.contains("可以");
        boolean answerNo = lower.contains("不") || lower.contains("不是") || lower.contains("不要") || lower.contains("否") || lower.contains("不要的") || lower.contains("不是的");

        if (expectingTextConfirmation) {
            // 用户被问 "你是要识别文字吗？" —— 根据回答决定走 OCR 或物体识别
            final Bitmap bmpToProcess = pendingRecognitionBitmap;
            pendingRecognitionBitmap = null;
            expectingTextConfirmation = false;

            if (answerYes) {
                Toast.makeText(this, "正在进行文字识别，请稍候", Toast.LENGTH_SHORT).show();
                BaiduImageUploader.uploadImage(bmpToProcess, new BaiduImageUploader.UploadCallback() {
                    @Override
                    public void onSuccess(String resultJson) {
                        try {
                            if (patrickAI != null) {
                                patrickAI.speak("文字识别已完成，我正在理解结果");
                                String prompt = "图片识别结果：" + resultJson + "，请将结果润色成自然口语并简短返回。";
                                patrickAI.callAI(prompt, aiResult -> {
                                    try {
                                        if (aiResult != null && !aiResult.trim().isEmpty()) patrickAI.speak(aiResult);
                                    } catch (Exception ignored) {}
                                });
                            } else {
                                TTSPlayer.speak("识别结果已返回");
                            }
                        } catch (Exception e) {
                            Log.e("VideoActivity_pi", "转发图片识别给 PatrickAI 失败: " + e.getMessage());
                        } finally {
                            try { if (bmpToProcess != null && !bmpToProcess.isRecycled()) bmpToProcess.recycle(); } catch (Exception ignored) {}
                        }
                    }
                    @Override
                    public void onError(String errorMessage) {
                        try {
                            Log.e("VideoActivity_pi", "文字识别失败: " + errorMessage);
                            if (patrickAI != null) patrickAI.speak("文字识别失败");
                            else TTSPlayer.speak("文字识别失败");
                        } finally {
                            try { if (bmpToProcess != null && !bmpToProcess.isRecycled()) bmpToProcess.recycle(); } catch (Exception ignored) {}
                        }
                    }
                });
                return;
            } else if (answerNo) {
                // 否定 -> 去物体识别
                try { if (bmpToProcess != null && !bmpToProcess.isRecycled()) bmpToProcess.recycle(); } catch (Exception ignored) {}
                triggerRemoteYoloDetection();
                return;
            } else {
                // 回答不明确，重新询问
                pendingRecognitionBitmap = bmpToProcess; // 还原
                awaitingRecognitionChoice = true;
                expectingTextConfirmation = true;
                if (patrickAI != null) patrickAI.speak("请回答是或否，你是要识别文字吗？");
                else TTSPlayer.speak("请回答是或否，你是要识别文字吗？");
                return;
            }
        }


        // 兼容旧逻辑：若没有在等待“是否识别文字”的确认，但用户仍然在选择文字/物体（例如手动）
        boolean wantText = lower.contains("字") || lower.contains("文") || lower.contains("文字") || lower.contains("识字");
        boolean wantObject = lower.contains("物") || lower.contains("东西") || lower.contains("物体") || lower.contains("物品");

        if (wantText && !wantObject) {
            final Bitmap bmpToUpload = pendingRecognitionBitmap;
            pendingRecognitionBitmap = null;
            Toast.makeText(this, "正在进行文字识别，请稍候", Toast.LENGTH_SHORT).show();
            BaiduImageUploader.uploadImage(bmpToUpload, new BaiduImageUploader.UploadCallback() {
                @Override
                public void onSuccess(String resultJson) {
                    try {
                        if (patrickAI != null) {
                            patrickAI.speak("文字识别已完成，我正在理解结果");
                            patrickAI.onInput("图片识别结果：" + resultJson);
                        } else {
                            TTSPlayer.speak("识别结果已返回");
                        }
                    } catch (Exception e) {
                        Log.e("VideoActivity_pi", "转发图片识别给 PatrickAI 失败: " + e.getMessage());
                    } finally {
                        try { if (bmpToUpload != null && !bmpToUpload.isRecycled()) bmpToUpload.recycle(); } catch (Exception ignored) {}
                    }
                }
                @Override
                public void onError(String errorMessage) {
                    try {
                        Log.e("VideoActivity_pi", "文字识别失败: " + errorMessage);
                        if (patrickAI != null) patrickAI.speak("文字识别失败");
                        else TTSPlayer.speak("文字识别失败");
                    } finally {
                        try { if (bmpToUpload != null && !bmpToUpload.isRecycled()) bmpToUpload.recycle(); } catch (Exception ignored) {}
                    }
                }
            });
            return;
        }

        if (wantObject && !wantText) {
            // 物体识别（远程）
            if (pendingRecognitionBitmap != null) {
                try { if (!pendingRecognitionBitmap.isRecycled()) pendingRecognitionBitmap.recycle(); } catch (Exception ignored) {}
                pendingRecognitionBitmap = null;
            }
            triggerRemoteYoloDetection();
            return;
        }

        // 无法判断，询问用户
        if (patrickAI != null) patrickAI.speak("抱歉，我没听清。你是要识别文字还是物体呢？请再说一次。");
        else TTSPlayer.speak("抱歉，我没听清。你是要识别文字还是物体呢？请再说一次。");
        awaitingRecognitionChoice = true;

    }

    // 新增：YOLO 识别防抖与方法（将请求 /api/detect，解析并播报；同时把原始结果发送给 PatrickAI 让其进一步润色）
    private long lastYoloSpeakTime = 0;
    private static final long YOLO_SPEAK_COOLDOWN_MS = 3000; // 最小间隔 ms

    /**
     * 异步向树莓派 /api/detect 请求一次 YOLO 识别，
     * 解析返回 JSON，生成一段“人话”并用 TTS 播报；
     * 同时把原始 JSON 发给 patrickAI.onInput 让 AI 进一步润色（异步）。
     */
    private void triggerRemoteYoloDetection() {
        if (raspiIp == null) {
            Toast.makeText(this, "未连接到树莓派", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastYoloSpeakTime < YOLO_SPEAK_COOLDOWN_MS) return; // 防抖

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://" + raspiIp + ":5000/api/detect");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    final int resp = code;
                    mainHandler.post(() -> Toast.makeText(VideoActivity_pi.this,
                            "识别请求失败: 服务器响应 " + resp, Toast.LENGTH_SHORT).show());
                    return;
                }

                java.io.InputStream in = new java.io.BufferedInputStream(connection.getInputStream());
                java.util.Scanner s = new java.util.Scanner(in).useDelimiter("\\A");
                final String json = s.hasNext() ? s.next() : "";
                in.close();

                mainHandler.post(() -> {
                    try {
                        JSONObject obj = new JSONObject(json);
                        if (!obj.optBoolean("success", false)) {
                            String msg = obj.optString("message", "识别失败");
                            if (patrickAI != null) patrickAI.speak(msg);
                            else TTSPlayer.speak(msg);
                            return;
                        }

                        JSONArray arr = obj.optJSONArray("objects");
                        String speakText = beautifyYoloResult(arr);

                        // 立即播报简短的结果
                        if (patrickAI != null) {
                            patrickAI.speak(speakText);
                            // 异步让 AI 润色：用 callAI，不作为“用户输入”
                            try {
                                String prompt = "请把以下物体识别结果润色成自然口语并返回: " + json;
                                patrickAI.callAI(prompt, aiResult -> {
                                    try {
                                        if (aiResult != null && !aiResult.trim().isEmpty()) patrickAI.speak(aiResult);
                                    } catch (Exception ignored) {}
                                });
                            } catch (Exception e) {
                                Log.e("VideoActivity_pi", "调 AI 润色出错: " + e.getMessage());
                            }
                        } else {
                            TTSPlayer.speak(speakText);
                        }

                        lastYoloSpeakTime = System.currentTimeMillis();
                        Toast.makeText(VideoActivity_pi.this, speakText, Toast.LENGTH_LONG).show();

                    } catch (Exception e) {
                        Toast.makeText(VideoActivity_pi.this, "解析识别结果出错", Toast.LENGTH_SHORT).show();
                        Log.e("VideoActivity_pi", "triggerRemoteYoloDetection 解析错误: " + e.getMessage());
                    }
                });

            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mainHandler.post(() -> {
                    Toast.makeText(VideoActivity_pi.this, "识别请求异常: " + msg, Toast.LENGTH_SHORT).show();
                    Log.e("VideoActivity_pi", "triggerRemoteYoloDetection 异常: " + msg);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /**
     * 简单把 YOLO 返回的 objects 数组美化为一句自然口语。
     * 客户端做基础计数与置信度过滤，AI 会在后台进一步润色（如果可用）。
     */
    private String beautifyYoloResult(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return "前方未识别到明显的物体。";
        }
        java.util.Map<String, Integer> countMap = new java.util.HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            double conf = o.optDouble("confidence", 0.0);
            if (conf < 0.1) continue; // 过滤低置信度
            String label = o.optString("label", "物体");
            countMap.put(label, countMap.getOrDefault(label, 0) + 1);
        }
        if (countMap.isEmpty()) return "前方未识别到明显的物体。";

        StringBuilder sb = new StringBuilder();
        sb.append("前方检测到");
        int idx = 0;
        for (java.util.Map.Entry<String, Integer> e : countMap.entrySet()) {
            if (idx > 0) sb.append("，");
            int cnt = e.getValue();
            String label = e.getKey();
            if (cnt == 1) sb.append("一").append(label);
            else sb.append(cnt).append("个").append(label);
            idx++;
        }
        sb.append("。");
        return sb.toString();
    }

}


