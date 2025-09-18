package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.iflytek.cloud.*;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class PatrickAIManager {
    private static PatrickAIManager instance;
    private Context context;
    private SpeechRecognizer speechRecognizer;
    private GLMApiClient apiClient;
    private boolean isListening = false;
    private boolean isEnabled = true;
    private Handler mainHandler;

    // 加强杂音过滤
    private static final int MIN_VOLUME_THRESHOLD = 3; // 提高音量阈值，从1改为3
    private static final int MIN_SPEECH_LENGTH = 500; // 提高最小语音长度，从200改为500ms
    private static final int MIN_TEXT_LENGTH = 1;
    private long speechStartTime = 0;

    // 确认状态
    private boolean awaitingNavigationConfirm = false;
    private boolean awaitingVideoConfirm = false;

    // 思考和说话状态
    private boolean isThinking = false;
    private boolean isSpeaking = false; // 新增：Patrick是否在说话
    private Handler thinkingHandler = new Handler(Looper.getMainLooper());
    private Runnable thinkingRunnable;

    // 回调接口
    public interface PatrickCallback {
        void onPatrickSpeak(String text);
        void onUserSpeak(String text);
        void onNavigationRequest();
        void onVideoRequest();
    }

    private PatrickCallback callback;

    private PatrickAIManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.apiClient = new GLMApiClient();
        initSpeechRecognizer();
        TTSPlayer.init(this.context);
    }

    public static PatrickAIManager getInstance(Context context) {
        if (instance == null) {
            instance = new PatrickAIManager(context);
        }
        return instance;
    }

    public void setCallback(PatrickCallback callback) {
        this.callback = callback;
    }

    private void initSpeechRecognizer() {
        try {
            SpeechUtility.createUtility(context, "appid=9be1e7dc");
            speechRecognizer = SpeechRecognizer.createRecognizer(context, null);
        } catch (Exception e) {
            Log.e("PatrickAI", "语音识别初始化失败: " + e.getMessage());
        }
    }

    // 开始思考提示
    private void startThinkingPrompt() {
        if (isThinking) return;
        isThinking = true;

        // 思考时立即停止语音识别
        forceStopListening();

        thinkingRunnable = new Runnable() {
            @Override
            public void run() {
                if (isThinking) {
                    isSpeaking = true; // 标记Patrick在说话
                    TTSPlayer.speak("Patrick智商不高，正在思考");
                    if (callback != null) {
                        callback.onPatrickSpeak("Patrick智商不高，正在思考");
                    }
                    // 每4秒重复一次，增加间隔
                    thinkingHandler.postDelayed(this, 4000);

                    // 说话完毕后重置说话状态，但不恢复监听（因为还在思考）
                    mainHandler.postDelayed(() -> {
                        isSpeaking = false;
                    }, 2000);
                }
            }
        };

        // 2秒后开始第一次思考提示
        thinkingHandler.postDelayed(thinkingRunnable, 2000);
    }

    // 停止思考提示
    private void stopThinkingPrompt() {
        isThinking = false;
        if (thinkingRunnable != null) {
            thinkingHandler.removeCallbacks(thinkingRunnable);
        }
    }

    // 强制停止语音识别
    private void forceStopListening() {
        if (speechRecognizer != null && isListening) {
            speechRecognizer.cancel(); // 使用cancel而不是stopListening，更彻底
            isListening = false;
        }
    }

    // 开始持续监听（只有在不思考和不说话时才真正开始）
    public void startContinuousListening() {
        if (!isEnabled || speechRecognizer == null || isThinking || isSpeaking) {
            Log.d("PatrickAI", "跳过启动监听：enabled=" + isEnabled + ", thinking=" + isThinking + ", speaking=" + isSpeaking);
            return;
        }

        Log.d("PatrickAI", "真正开始语音监听");

        speechRecognizer.setParameter(SpeechConstant.LANGUAGE, "zh_cn");
        speechRecognizer.setParameter(SpeechConstant.ACCENT, "mandarin");
        speechRecognizer.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD);
        speechRecognizer.setParameter(SpeechConstant.RESULT_TYPE, "plain");
        speechRecognizer.setParameter(SpeechConstant.VAD_BOS, "2000");
        speechRecognizer.setParameter(SpeechConstant.VAD_EOS, "800");
        speechRecognizer.setParameter(SpeechConstant.ASR_PTT, "0");
        speechRecognizer.setParameter(SpeechConstant.ASR_AUDIO_PATH, "");

        speechRecognizer.startListening(recognizerListener);
        isListening = true;
    }

    public void stopListening() {
        forceStopListening();
    }

    public void pauseListening() {
        isEnabled = false;
        forceStopListening();
    }

    public void resumeListening() {
        isEnabled = true;
        // 延迟一点再开始监听，确保Patrick说完话
        mainHandler.postDelayed(() -> {
            if (!isThinking && !isSpeaking) {
                startContinuousListening();
            }
        }, 500);
    }

    // 添加公共方法供手动输入调用
    public void handleUserInput(String userText) {
        if (callback != null) {
            callback.onUserSpeak(userText);
        }

        // 立即停止语音识别
        forceStopListening();
        isEnabled = false;

        // 处理确认状态
        if (awaitingNavigationConfirm) {
            if (userText.contains("是") || userText.contains("好的") || userText.contains("确定") || userText.contains("可以")) {
                String reply = "好的，我来为你打开导航模式";
                speakAndCallback(reply);
                if (callback != null) {
                    callback.onNavigationRequest();
                }
            } else {
                String reply = "好的，那我们继续聊天吧";
                speakAndCallback(reply);
            }
            awaitingNavigationConfirm = false;
            return;
        }

        if (awaitingVideoConfirm) {
            if (userText.contains("是") || userText.contains("好的") || userText.contains("确定") || userText.contains("可以")) {
                String reply = "好的，我来为你打开视频模式";
                speakAndCallback(reply);
                if (callback != null) {
                    callback.onVideoRequest();
                }
            } else {
                String reply = "好的，那我们继续聊天吧";
                speakAndCallback(reply);
            }
            awaitingVideoConfirm = false;
            return;
        }

        // 本地关键词识别
        if (userText.contains("导航") || userText.contains("地图") || userText.contains("路线")) {
            String reply = "我听到你提到了导航，要打开导航模式吗？";
            speakAndCallback(reply);
            awaitingNavigationConfirm = true;
            return;
        }

        if (userText.contains("视频") || userText.contains("摄像") || userText.contains("拍摄")) {
            String reply = "我听到你提到了视频，要打开视频模式吗？";
            speakAndCallback(reply);
            awaitingVideoConfirm = true;
            return;
        }

        // 开始思考提示
        startThinkingPrompt();

        // 其他问题发送给AI
        String prompt = "你是Patrick，一个智能AI眼镜助手。请简洁地回复用户的问题，回复不要超过30字。用户问题：" + userText;

        apiClient.chatCompletion(prompt, new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                stopThinkingPrompt(); // 停止思考提示
                String errorMsg = "我遇到了一些问题，请稍后再试";
                speakAndCallback(errorMsg);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                stopThinkingPrompt(); // 停止思考提示
                try {
                    String resp = response.body() != null ? response.body().string() : "";
                    String content = parseAIResponse(resp);
                    String replyText = content != null && !content.trim().isEmpty() ?
                            content : "我暂时无法理解你的意思";
                    speakAndCallback(replyText);
                } catch (Exception e) {
                    String errorMsg = "处理回复时出现问题";
                    speakAndCallback(errorMsg);
                }
            }
        });
    }

    private final RecognizerListener recognizerListener = new RecognizerListener() {
        @Override
        public void onBeginOfSpeech() {
            speechStartTime = System.currentTimeMillis();
            Log.d("PatrickAI", "检测到开始说话");
        }

        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            String text = results.getResultString();
            long speechDuration = System.currentTimeMillis() - speechStartTime;

            Log.d("PatrickAI", "原始识别结果: " + text + ", 时长: " + speechDuration + "ms");

            // 加强过滤条件
            if (text != null && !text.trim().isEmpty() &&
                    speechDuration > MIN_SPEECH_LENGTH &&
                    text.length() >= MIN_TEXT_LENGTH &&
                    !text.matches(".*[啊呀哦嗯嗯呢噢].*")) { // 过滤语气词
                Log.d("PatrickAI", "识别到有效语音: " + text);
                handleUserInput(text);
            } else {
                Log.d("PatrickAI", "过滤的语音: " + text + " (时长:" + speechDuration + "ms, 长度:" + (text != null ? text.length() : 0) + ")");
                // 被过滤后延迟重新监听
                if (isLast && isEnabled && !isThinking && !isSpeaking) {
                    mainHandler.postDelayed(() -> {
                        if (isEnabled && !isThinking && !isSpeaking) {
                            startContinuousListening();
                        }
                    }, 1000);
                }
            }
        }

        @Override
        public void onError(SpeechError error) {
            Log.e("PatrickAI", "语音识别错误: " + error.getPlainDescription(true) + " 错误码: " + error.getErrorCode());

            if (error.getErrorCode() == 10118) {
                Log.d("PatrickAI", "未检测到语音");
                if (isEnabled && !isThinking && !isSpeaking) {
                    mainHandler.postDelayed(() -> {
                        if (isEnabled && !isThinking && !isSpeaking) {
                            startContinuousListening();
                        }
                    }, 1000);
                }
            } else {
                if (isEnabled && !isThinking && !isSpeaking) {
                    mainHandler.postDelayed(() -> {
                        if (isEnabled && !isThinking && !isSpeaking) {
                            startContinuousListening();
                        }
                    }, 3000);
                }
            }
        }

        @Override
        public void onVolumeChanged(int volume, byte[] data) {
            // 只记录较高音量
            if (volume > MIN_VOLUME_THRESHOLD) {
                Log.d("PatrickAI", "检测到有效音量变化: " + volume);
            }
        }

        @Override
        public void onEndOfSpeech() {
            Log.d("PatrickAI", "语音结束");
        }

        @Override
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
            Log.d("PatrickAI", "语音事件: " + eventType);
        }
    };

    private void speakAndCallback(String text) {
        isSpeaking = true; // 标记Patrick开始说话
        forceStopListening(); // 立即停止语音识别

        mainHandler.post(() -> {
            TTSPlayer.speak(text);
            if (callback != null) {
                callback.onPatrickSpeak(text);
            }

            // 根据文本长度动态计算说话时间
            int speakDuration = Math.max(2000, text.length() * 100); // 每个字100ms，最少2秒

            mainHandler.postDelayed(() -> {
                isSpeaking = false; // Patrick说完了
                isEnabled = true; // 重新启用监听

                // 再延迟一点确保完全说完
                mainHandler.postDelayed(() -> {
                    if (!isThinking && !isSpeaking) {
                        startContinuousListening();
                    }
                }, 500);
            }, speakDuration);
        });
    }

    private String parseAIResponse(String json) {
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

    // Patrick说话的公共方法
    public void patrickSpeak(String text) {
        speakAndCallback(text);
    }

    public void destroy() {
        isEnabled = false;
        isSpeaking = false;
        stopThinkingPrompt(); // 停止思考提示
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        }
        TTSPlayer.shutdown();
        instance = null;
    }
}