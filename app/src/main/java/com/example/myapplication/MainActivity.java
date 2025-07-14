package com.example.myapplication;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.content.Intent;
import android.net.Uri;


public class MainActivity extends AppCompatActivity {
    Button btnRecognition, btnNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        btnRecognition = findViewById(R.id.btn_recognition);
        btnNavigation = findViewById(R.id.btn_navigation); 

        // 点击识图模式按钮
        btnRecognition.setOnClickListener(view -> {
            Toast.makeText(this, "进入识图模式", Toast.LENGTH_SHORT).show();
            // TODO：调用识图模式的逻辑
        });
        btnNavigation.setOnClickListener(view -> {
            Toast.makeText(this, "进入导航模式", Toast.LENGTH_SHORT).show();
            // TODO：调用导航模式的逻辑

            double latitude = 23.1085;
            double longitude = 113.3245;
            String destinationName = "广州塔";

            String uri = "https://uri.amap.com/navigation?to=" + longitude + "," + latitude + "," + destinationName + "&mode=walk";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            startActivity(intent);
            }
        );

    }
}