package com.example.myapplication;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import android.content.Intent;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


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
            Intent intent = new Intent(MainActivity.this, VideoActivity.class);
            startActivity(intent);
        });

        // 点击导航模式按钮
        btnNavigation.setOnClickListener(view -> {
            Toast.makeText(this, "进入导航模式", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(MainActivity.this, NavigationActivity.class);
            startActivity(intent);
                }
        );

    }
}