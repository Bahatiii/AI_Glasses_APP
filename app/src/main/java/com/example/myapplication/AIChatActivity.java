package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

public class AIChatActivity extends AppCompatActivity implements PatrickAIManager.PatrickCallback {

    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;
    private static final int REQUEST_NAVIGATION = 3001;
    private static final int REQUEST_VIDEO = 3002;

    private EditText etInput;
    private Button btnSend;
    private TextView tvChat;
    private ScrollView scrollChat;

    private PatrickAIManager patrickAI;
    // 当从子 Activity（导航/视频）返回时，使用此标志避免 onResume 重复立即 resumeListening
    private boolean suppressAutoResumeOnNextResume = false;

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

        // **修改：检查是否从导航返回，如果是则不播放问候语**
        boolean isFromNavigation = getIntent().getBooleanExtra("from_navigation", false);
        if (!isFromNavigation) {
            // 延迟1.5秒后主动问候
            new Handler().postDelayed(() -> {
                showPatrickGreeting();
            }, 1500);
        } else {
            // 从导航返回，显示简短的确认信息
            new Handler().postDelayed(() -> {
                tvChat.append("已返回AI聊天模式\n");
                scrollToBottom();
            }, 500);
        }
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
            Log.d("AIChatActivity", "Patrick说话显示在对话框: " + text);
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
            // start for result so we can reliably detect returning from navigation and update UI
            startActivityForResult(intent, REQUEST_NAVIGATION);
        });
    }

    @Override
    public void onVideoRequest() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, VideoActivity_pi.class);
            startActivityForResult(intent, REQUEST_VIDEO);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_NAVIGATION || requestCode == REQUEST_VIDEO) {
            runOnUiThread(() -> {
                tvChat.append("\u5df2\u8fd4\u56deAI\u804a\u5929\u6a21\u5f0f\n");
                scrollToBottom();
                if (patrickAI != null) {
                    patrickAI.setCallback(this);
                    patrickAI.flushPendingToCallback();
                    patrickAI.pauseListening();
                    suppressAutoResumeOnNextResume = true;
                    showPatrickGreeting();
                }
            });
        }
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
        Log.d("AIChatActivity", "onResume - 重新设置Patrick回调");
        if (patrickAI != null) {
            // **关键修改：确保回调正确设置**
            patrickAI.setCallback(this);
            // 立即刷新 pending 到 UI，保证 onResume 时能立刻显示之前的消息
            patrickAI.flushPendingToCallback();
            // 如果刚才我们在 onActivityResult 已经安排了 showPatrickGreeting（suppress 标志被设置），
            // 则不要在这里重复立即 resumeListening，等待 showPatrickGreeting 安排恢复。
            if (!suppressAutoResumeOnNextResume) {
                patrickAI.resumeListening();
            } else {
                // 清除一次性标志，以便后续正常 resume
                suppressAutoResumeOnNextResume = false;
            }
            Log.d("AIChatActivity", "Patrick回调已重新设置为AIChatActivity");
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

    // **新增：处理从其他Activity返回时的Intent**
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean isFromNavigation = intent.getBooleanExtra("from_navigation", false);
        if (isFromNavigation) {
            runOnUiThread(() -> {
                tvChat.append("\u5df2\u8fd4\u56deAI\u804a\u5929\u6a21\u5f0f\n");
                scrollToBottom();
                if (patrickAI != null) {
                    patrickAI.setCallback(this);
                    patrickAI.flushPendingToCallback();
                    patrickAI.pauseListening();
                    suppressAutoResumeOnNextResume = true;
                    showPatrickGreeting();
                }
            });
        }
    }
}
