package com.example.myapplication;

import okhttp3.*;
import com.google.gson.Gson;
import java.io.IOException;

public class GLMApiClient {
    private static final String BASE_URL = "https://open.bigmodel.cn/api/paas/v4/";
    private static final String API_KEY = "1601ed501add40c2b8ee563b9ec2fc01.amUHrdMCNjeXweUt"; // 替换为你的API Key

    private OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private Gson gson = new Gson();

    public void chatCompletion(String userMessage, Callback callback) {
        ChatRequest request = new ChatRequest();
        request.model = "glm-4.5";
        request.messages = new Message[]{
                new Message("user", userMessage)
        };

        String json = gson.toJson(request);

        RequestBody body = RequestBody.create(
                json, MediaType.get("application/json; charset=utf-8")
        );

        Request httpRequest = new Request.Builder()
                .url(BASE_URL + "chat/completions")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        client.newCall(httpRequest).enqueue(callback);
    }

    static class ChatRequest {
        String model;
        Message[] messages;
    }

    static class Message {
        String role;
        String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}