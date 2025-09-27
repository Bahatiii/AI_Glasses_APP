package com.example.myapplication;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.Toast;
import android.content.Intent;
import android.util.Log;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    Button btnAI, btnVideo, btnNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable((ComponentActivity) this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        btnAI = findViewById(R.id.btn_ai);
        btnVideo = findViewById(R.id.btn_video);
        btnNavigation = findViewById(R.id.btn_navigation);

        // 点击AI模式按钮
        btnAI.setOnClickListener(view -> {
            Toast.makeText(this, "进入AI模式", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, AIChatActivity.class);
            startActivity(intent);
        });

        // 点击视频模式按钮
        btnVideo.setOnClickListener(view -> {
            Toast.makeText(this, "进入视频模式", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, VideoActivity_pi.class);
            startActivity(intent);
        });

        // 点击导航模式按钮
        btnNavigation.setOnClickListener(view -> {
            Toast.makeText(this, "进入导航模式", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, NavigationActivity.class);
            startActivity(intent);
        });

        // 延迟1秒后自动进入AI模式
        new Handler().postDelayed(() -> {
            Toast.makeText(this, "自动进入AI模式", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, AIChatActivity.class);
            startActivity(intent);
        }, 1000);

        // -----------------------------
        // 新增：测试 ONNX 模型识别 test1.webp
        // -----------------------------
        new Handler().postDelayed(this::testOnnxImage, 1500); // 延迟1.5秒执行，避免阻塞 UI
    }

    /**
     * 测试 assets 中单张图片的识别
     */
    private void testOnnxImage() {
        try {
            // 初始化模型
            ONNXImageClassifier classifier = new ONNXImageClassifier(this);

            // 读取 assets 中的测试图片
            String testImageName = "test1.webp";
            InputStream is = getAssets().open(testImageName);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();

            // 调用模型识别并自动播报
            classifier.detectAndSpeak(bitmap);

            // 打印 Log
            Log.d("ONNX_TEST", "测试图片 " + testImageName + " 已处理");

        } catch (Exception e) {
            e.printStackTrace();
            Log.e("ONNX_TEST", "识别测试出错", e);
        }
    }
}
