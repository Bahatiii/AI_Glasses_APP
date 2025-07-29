package com.example.myapplication;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class AIChatActivity extends AppCompatActivity {
    private EditText etInput;
    private Button btnSend;
    private TextView tvChat;
    private GLMApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        tvChat = findViewById(R.id.tv_chat);

        apiClient = new GLMApiClient();

        btnSend.setOnClickListener(v -> {
            String userText = etInput.getText().toString().trim();
            if (userText.isEmpty()) return;
            tvChat.append("你: " + userText + "\n");
            btnSend.setEnabled(false);

            apiClient.chatCompletion(userText, new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    runOnUiThread(() -> {
                        tvChat.append("AI: 网络错误\n");
                        btnSend.setEnabled(true);
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
                                handleAIIntent(content);
                            } else {
                                tvChat.append("AI: 暂无回复内容\n");
                            }
                            btnSend.setEnabled(true);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            tvChat.append("AI: 回复解析失败\n");
                            btnSend.setEnabled(true);
                        });
                    }
                }
            });
        });
    }

    // 解析GLM响应内容
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

    // 简单意图解析并自动操作App
    private void handleAIIntent(String reply) {
        if (reply.contains("导航")) {
            Intent intent = new Intent(this, NavigationActivity.class);
            startActivity(intent);
        } else if (reply.contains("视频")) {
            Intent intent = new Intent(this, VideoActivity.class);
            startActivity(intent);
        }
    }
}

