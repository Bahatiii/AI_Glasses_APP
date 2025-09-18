package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

public class AIChatActivity extends AppCompatActivity implements PatrickAIManager.PatrickCallback {

    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;

    private EditText etInput;
    private Button btnSend;
    private TextView tvChat;
    private ScrollView scrollChat;

    private PatrickAIManager patrickAI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        tvChat = findViewById(R.id.tv_chat);
        scrollChat = findViewById(R.id.scroll_chat);

        // 初始化Patrick AI管理器
        patrickAI = PatrickAIManager.getInstance(this);
        patrickAI.setCallback(this);

        // 权限检查
        if (!checkAudioPermission()) {
            requestAudioPermission();
        } else {
            startPatrickAI();
        }

        setupSendButton();

        // 延迟1.5秒后主动问候
        new Handler().postDelayed(() -> {
            showPatrickGreeting();
        }, 1500);
    }

    private void startPatrickAI() {
        patrickAI.startContinuousListening();
        Toast.makeText(this, "Patrick AI已启动，正在监听...", Toast.LENGTH_SHORT).show();
    }

    // Patrick主动问候
    private void showPatrickGreeting() {
        String greeting = "你好，我是Patrick，你的智能AI眼镜助手，我现在开始监听你的语音";
        tvChat.append("Patrick: " + greeting + "\n");
        patrickAI.pauseListening(); // 暂停监听避免自己说话被识别
        TTSPlayer.speak(greeting);
        scrollToBottom();
        // 3秒后恢复监听
        new Handler().postDelayed(() -> patrickAI.resumeListening(), 3000);
    }

    // --- Patrick回调接口实现 ---
    @Override
    public void onPatrickSpeak(String text) {
        runOnUiThread(() -> {
            tvChat.append("Patrick: " + text + "\n");
            scrollToBottom();
        });
    }

    @Override
    public void onUserSpeak(String text) {
        runOnUiThread(() -> {
            tvChat.append("你: " + text + "\n");
            scrollToBottom();
        });
    }

    @Override
    public void onNavigationRequest() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, NavigationActivity.class);
            startActivity(intent);
        });
    }

    @Override
    public void onVideoRequest() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, VideoActivity_pi.class);
            startActivity(intent);
        });
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
            startPatrickAI();
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用语音", Toast.LENGTH_SHORT).show();
        }
    }

    // --- 文本发送按钮（保留手动输入功能） ---
    private void setupSendButton() {
        btnSend.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);

            String userText = etInput.getText().toString().trim();
            if (userText.isEmpty()) return;

            etInput.setText("");

            // 手动输入也通过Patrick处理
            patrickAI.handleUserInput(userText);
        });
    }

    // --- 滚动 ---
    private void scrollToBottom() {
        scrollChat.post(() -> scrollChat.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (patrickAI != null) {
            patrickAI.setCallback(this);
            patrickAI.resumeListening();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (patrickAI != null) {
            patrickAI.pauseListening();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 不销毁PatrickAI，让它在其他Activity中继续工作
        if (patrickAI != null) {
            patrickAI.setCallback(null);
        }
    }
}
