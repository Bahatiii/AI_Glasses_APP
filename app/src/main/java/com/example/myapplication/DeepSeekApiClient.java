package com.example.myapplication;

import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
public class DeepSeekApiClient {

    // 1. 替换为你的 DeepSeek API Key
    private static final String API_KEY = "sk-5b899b80e4e34c13bcf2bccbc0529007"; // 在这里填入你的 DeepSeek API Key

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    // --- 核心修改：创建一个带超时配置的 OkHttpClient ---
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // 连接超时
            .readTimeout(30, TimeUnit.SECONDS)    // 读取超时
            .writeTimeout(30, TimeUnit.SECONDS)   // 写入超时
            .build();

    public void chatCompletion(String prompt, Callback callback) {
        // 2. 构建符合 DeepSeek API 格式的 JSON 请求体
        String jsonBody = "{"
                + "\"model\": \"deepseek-chat\","
                + "\"messages\": ["
                + "    {\"role\": \"user\", \"content\": \"" + escapeJson(prompt) + "\"}"
                + "],"
                + "\"temperature\": 0.7,"
                + "\"stream\": false"
                + "}";

        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));

        // 3. 构建请求，包含认证头
        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        // 4. 发起异步网络请求
        client.newCall(request).enqueue(callback);
    }

    // 用于转义JSON字符串中的特殊字符
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
