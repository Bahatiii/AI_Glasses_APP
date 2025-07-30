package com.example.myapplication;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ScrollView;
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
    private ScrollView scrollChat; // 新增
    private GLMApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        etInput = findViewById(R.id.et_input);
        btnSend = findViewById(R.id.btn_send);
        tvChat = findViewById(R.id.tv_chat);
        scrollChat = findViewById(R.id.scroll_chat); // 新增

        apiClient = new GLMApiClient();

        btnSend.setOnClickListener(v -> {
            // 隐藏输入法键盘
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
            }
            String userText = etInput.getText().toString().trim();
            if (userText.isEmpty()) return;
            tvChat.append("你: " + userText + "\n");
            etInput.setText(""); // 自动清空输入框
            btnSend.setEnabled(false);
            scrollToBottom(); // 自动滚动

            // 本地处理“导航”或“视频”关键词，不走接口
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
                        System.out.println("GLM原始响应: " + resp);
                        String content = GLMResponseParser.parseContent(resp);
                        runOnUiThread(() -> {
                            if (content != null && !content.trim().isEmpty()) {
                                tvChat.append("AI: " + content + "\n");
                                handleAIIntent(content);
                            } else {
                                tvChat.append("AI: 暂无回复内容\n");
                            }
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

    // 自动滚动到底部
    private void scrollToBottom() {
        scrollChat.post(() -> scrollChat.fullScroll(ScrollView.FOCUS_DOWN));
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
    private boolean awaitingNavigationConfirm = false;
    private boolean awaitingVideoConfirm = false;

    private void handleAIIntent(String reply) {
        if (awaitingNavigationConfirm) {
            // 用户正在确认是否打开导航
            if (reply.contains("是") || reply.contains("好的") || reply.contains("确定") || reply.contains("可以")) {
                Intent intent = new Intent(this, NavigationActivity.class);
                startActivity(intent);
                awaitingNavigationConfirm = false;
            } else if (reply.contains("否") || reply.contains("不用") || reply.contains("不用了")) {
                tvChat.append("AI: 好的，不打开导航模式。\n");
                awaitingNavigationConfirm = false;
            }
            return;
        }

        if (awaitingVideoConfirm) {
            // 用户正在确认是否打开视频
            if (reply.contains("是") || reply.contains("好的") || reply.contains("确定") || reply.contains("可以")) {
                Intent intent = new Intent(this, VideoActivity.class);
                startActivity(intent);
                awaitingVideoConfirm = false;
            } else if (reply.contains("否") || reply.contains("不用") || reply.contains("不用了")) {
                tvChat.append("AI: 好的，不打开视频模式。\n");
                awaitingVideoConfirm = false;
            }
        }


    }
}

