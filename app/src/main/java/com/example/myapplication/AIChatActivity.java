package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class AIChatActivity extends AppCompatActivity {
    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;
    private PatrickAIEngine engine;
    private TextView tvChat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("AIChatActivity", "onCreate: 进入AI聊天界面");
        setContentView(R.layout.activity_ai_chat);
        tvChat = findViewById(R.id.tv_chat);

        // 进入前先销毁旧的 engine（保险起见）
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
        engine = new PatrickAIEngine(this, text -> {
            Log.d("AIChatActivity", "uiCallback: " + text);
            runOnUiThread(() -> tvChat.append(text + "\n"));
        });
        Log.d("AIChatActivity", "engine实例化: " + engine);

        // 延迟播报欢迎语，确保TTS初始化完成
        new android.os.Handler().postDelayed(() -> {
            engine.speak("你好，我是Patrick，你的智能AI眼镜助手，我现在开始监听你的语音");
        }, 800);

        if (!checkAudioPermission()) {
            Log.d("AIChatActivity", "麦克风权限未通过，申请权限");
            requestAudioPermission();
        } else {
            Log.d("AIChatActivity", "麦克风权限已通过，启动语音识别");
            startEngineListening();
        }
    }

    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST_CODE);
    }

    private void startEngineListening() {
        engine.startListening();
        Toast.makeText(this, "Patrick AI已启动，正在监听...", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("AIChatActivity", "onResume: 重建语音引擎");
        // 每次进入都销毁重建
        if (engine != null) {
            engine.destroy();
        }
        engine = new PatrickAIEngine(this, text -> {
            Log.d("AIChatActivity", "uiCallback: " + text);
            runOnUiThread(() -> tvChat.append(text + "\n"));
        });
        engine.startListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d("AIChatActivity", "onPause: 销毁语音引擎");
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d("AIChatActivity", "onDestroy: 销毁Activity, engine=" + engine);
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE &&
                grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startEngineListening();
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用语音", Toast.LENGTH_SHORT).show();
        }
    }
}