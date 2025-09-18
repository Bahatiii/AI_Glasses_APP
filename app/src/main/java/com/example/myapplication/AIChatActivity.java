package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.iflytek.cloud.*;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;

public class AIChatActivity extends AppCompatActivity {

    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;

    private EditText etInput;
    private Button btnSend;
    private TextView tvChat;
    private ScrollView scrollChat;
    private ImageButton btnVoice;

    private GLMApiClient apiClient;

    private SpeechRecognizer mIat;
    private boolean isListening = false;

    // 简单意图解析标记
    private boolean awaitingNavigationConfirm = false;
    private boolean awaitingVideoConfirm = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        tvChat = findViewById(R.id.tv_chat);
        scrollChat = findViewById(R.id.scroll_chat);
        btnVoice = findViewById(R.id.btn_voice);

        apiClient = new GLMApiClient();

        // 权限检查
        if (!checkAudioPermission()) requestAudioPermission();
        else initSpeechRecognizer();

        setupVoiceButton();
        setupSendButton();
    }

    // --- 权限 ---
    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE && Arrays.stream(grantResults).allMatch(r -> r == PackageManager.PERMISSION_GRANTED)) {
            initSpeechRecognizer();
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用语音", Toast.LENGTH_SHORT).show();
        }
    }

    // --- 语音识别初始化 ---
    private void initSpeechRecognizer() {
        SpeechUtility.createUtility(this, "appid=9be1e7dc");
        mIat = SpeechRecognizer.createRecognizer(this, null);
    }

    private void startIat() {
        if (mIat == null) return;
        mIat.setParameter(SpeechConstant.LANGUAGE, "zh_cn");
        mIat.setParameter(SpeechConstant.ACCENT, "mandarin");
        mIat.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD);
        mIat.setParameter(SpeechConstant.RESULT_TYPE, "plain");
        mIat.startListening(mRecognizerListener);
        isListening = true;
    }

    private void stopIat() {
        if (mIat != null && isListening) {
            mIat.stopListening();
            isListening = false;
        }
    }

    private final RecognizerListener mRecognizerListener = new RecognizerListener() {
        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            String text = results.getResultString();
            if (text != null && !text.trim().isEmpty()) {
                etInput.setText(text);
                btnSend.performClick(); // 自动触发发送
            }
        }
        @Override public void onBeginOfSpeech() {}
        @Override public void onEndOfSpeech() {}
        @Override public void onError(SpeechError error) {
            Toast.makeText(AIChatActivity.this, "语音识别错误: " + error.getPlainDescription(true), Toast.LENGTH_SHORT).show();
        }
        @Override public void onVolumeChanged(int volume, byte[] data) {}
        @Override public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {}
    };

    // --- 语音按钮 ---
    private void setupVoiceButton() {
        btnVoice.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    Toast.makeText(this, "开始语音输入", Toast.LENGTH_SHORT).show();
                    startIat();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Toast.makeText(this, "停止语音输入", Toast.LENGTH_SHORT).show();
                    stopIat();
                    v.performClick();
                    return true;
            }
            return false;
        });
    }

    // --- 文本发送按钮 ---
    private void setupSendButton() {
        btnSend.setOnClickListener(v -> {
            // 隐藏键盘
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);

            String userText = etInput.getText().toString().trim();
            if (userText.isEmpty()) return;

            tvChat.append("你: " + userText + "\n");
            etInput.setText("");
            btnSend.setEnabled(false);
            scrollToBottom();

            // 本地处理“导航”或“视频”关键词
            if (userText.contains("导航")) {
                tvChat.append("AI: 我好像听到你提到了导航，要打开导航模式吗？\n");
                awaitingNavigationConfirm = true;
                btnSend.setEnabled(true);
                scrollToBottom();
                return;
            } else if (userText.contains("视频")) {
                tvChat.append("AI: 我好像听到你提到了视频，要打开视频模式吗？\n");
                awaitingVideoConfirm = true;
                btnSend.setEnabled(true);
                scrollToBottom();
                return;
            }


            // 其他内容走接口
            apiClient.chatCompletion(userText, new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        tvChat.append("AI: 网络错误\n");
                        btnSend.setEnabled(true);
                        scrollToBottom();
                    });
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) {
                    try {
                        String resp = response.body() != null ? response.body().string() : "";
                        String content = GLMResponseParser.parseContent(resp);
                        runOnUiThread(() -> {
                            if (content != null && !content.trim().isEmpty()) {
                                tvChat.append("AI: " + content + "\n");
                                handleAIIntent(content);  // 保留意图解析
                            } else tvChat.append("AI: 暂无回复内容\n");

                            btnSend.setEnabled(true);
                            scrollToBottom();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            tvChat.append("AI: 回复解析失败\n");
                            btnSend.setEnabled(true);
                            scrollToBottom();
                        });
                    }
                }
            });
        });
    }

    // --- 滚动 ---
    private void scrollToBottom() {
        scrollChat.post(() -> scrollChat.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // --- GLM响应解析 ---
    static class GLMResponseParser {
        static String parseContent(String json) {
            try {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("choices")) {
                    com.google.gson.JsonArray choices = obj.getAsJsonArray("choices");
                    if (choices.size() > 0) {
                        com.google.gson.JsonObject messageObj = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                        if (messageObj != null && messageObj.has("content")) {
                            return messageObj.get("content").getAsString();
                        }
                    }
                }
                return "";
            } catch (Exception e) {
                return "解析失败";
            }
        }
    }

    // --- AI意图解析 ---
    private void handleAIIntent(String reply) {
        if (awaitingNavigationConfirm) {
            if (reply.contains("是") || reply.contains("好的") || reply.contains("确定") || reply.contains("可以")) {
                startActivity(new Intent(this, NavigationActivity.class));
            }
            awaitingNavigationConfirm = false;
            return;
        }
        if (awaitingVideoConfirm) {
            if (reply.contains("是") || reply.contains("好的") || reply.contains("确定") || reply.contains("可以")
                    || reply.contains("行") || reply.contains("没问题") || reply.contains("打开视频")) {
                startActivity(new Intent(this, VideoActivity_pi.class));  // ✅ 改为 VideoActivity_pi
            }
            awaitingVideoConfirm = false;
            return;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIat != null) { mIat.cancel(); mIat.destroy(); }
    }
}
