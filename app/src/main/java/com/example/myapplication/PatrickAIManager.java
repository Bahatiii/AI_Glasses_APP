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
    private static final int MIN_VOLUME_THRESHOLD = 3;
    private static final int MIN_SPEECH_LENGTH = 500;
    private static final int MIN_TEXT_LENGTH = 1;
    private long speechStartTime = 0;

    // 确认状态
    private boolean awaitingNavigationConfirm = false;
    private boolean awaitingVideoConfirm = false;

    // 思考和说话状态
    private boolean isThinking = false;
    private boolean isSpeaking = false;
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

    // 判断当前是否在导航模式
    private boolean isInNavigationMode() {
        boolean result = callback != null && callback.getClass().getSimpleName().equals("NavigationActivity");
        Log.d("PatrickAI", "isInNavigationMode: " + result);
        return result;
    }

    // 判断是否是导航相关输入
    private boolean isNavigationRelatedInput(String text) {
        Log.d("PatrickAI", "判断是否导航相关输入: " + text);
        String[] navigationKeywords = {
                "去", "到", "找", "导航", "路线", "地址", "位置",
                "我要去", "我想去", "带我去", "怎么走", "在哪里", "怎么去"
        };

        for (String keyword : navigationKeywords) {
            if (text.contains(keyword)) {
                Log.d("PatrickAI", "匹配到导航关键词: " + keyword);
                return true;
            }
        }

        // 检查是否可能是地名（长度大于2且不包含明显的非地名词汇）
        if (text.length() > 2) {
            String[] nonLocationWords = {"你好", "再见", "谢谢", "天气", "时间", "今天", "明天", "什么", "怎么", "为什么", "视频", "拍摄", "摄像"};
            for (String word : nonLocationWords) {
                if (text.contains(word)) {
                    Log.d("PatrickAI", "包含非地名词汇: " + word);
                    return false;
                }
            }
            Log.d("PatrickAI", "可能是地名（长度>2且无非地名词汇）");
            return true; // 可能是地名
        }

        Log.d("PatrickAI", "不是导航相关输入");
        return false;
    }

    // 开始思考提示
    public void startThinkingPrompt() {
        if (isThinking) return;
        isThinking = true;
        Log.d("PatrickAI", "开始思考提示");

        // 思考时立即停止语音识别
        forceStopListening();

        thinkingRunnable = new Runnable() {
            @Override
            public void run() {
                if (isThinking) {
                    isSpeaking = true;
                    TTSPlayer.speak("Patrick智商不高，正在思考");
                    if (callback != null) {
                        callback.onPatrickSpeak("Patrick智商不高，正在思考");
                    }
                    thinkingHandler.postDelayed(this, 4000);

                    mainHandler.postDelayed(() -> {
                        isSpeaking = false;
                    }, 2000);
                }
            }
        };

        thinkingHandler.postDelayed(thinkingRunnable, 2000);
    }

    // 停止思考提示
    public void stopThinkingPrompt() {
        isThinking = false;
        Log.d("PatrickAI", "停止思考提示");
        if (thinkingRunnable != null) {
            thinkingHandler.removeCallbacks(thinkingRunnable);
        }
    }

    // 强制停止语音识别
    private void forceStopListening() {
        if (speechRecognizer != null && isListening) {
            speechRecognizer.cancel();
            isListening = false;
        }
    }

    // 开始持续监听
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
        mainHandler.postDelayed(() -> {
            if (!isThinking && !isSpeaking) {
                startContinuousListening();
            }
        }, 500);
    }

    // 添加公共方法供手动输入调用
    public void handleUserInput(String userText) {
        Log.d("PatrickAI", "=== handleUserInput 开始 ===");
        Log.d("PatrickAI", "接收到用户输入: " + userText);
        Log.d("PatrickAI", "当前状态 - isInNavigationMode: " + isInNavigationMode());
        Log.d("PatrickAI", "当前状态 - awaitingNavigationConfirm: " + awaitingNavigationConfirm);
        Log.d("PatrickAI", "当前状态 - awaitingVideoConfirm: " + awaitingVideoConfirm);

        if (callback != null) {
            Log.d("PatrickAI", "调用callback.onUserSpeak: " + userText);
            callback.onUserSpeak(userText);
        } else {
            Log.w("PatrickAI", "callback为null，无法调用onUserSpeak");
        }

        // 立即停止语音识别
        forceStopListening();
        isEnabled = false;

        // **关键修改：在导航模式下，如果是导航相关输入或确认词，直接返回让NavigationActivity处理**
        if (isInNavigationMode() && (isNavigationRelatedInput(userText) || isConfirmationWord(userText))) {
            Log.d("PatrickAI", "导航模式下的导航相关输入或确认词，交给NavigationActivity处理: " + userText);
            // 不调用AI处理，直接恢复监听
            mainHandler.postDelayed(() -> {
                Log.d("PatrickAI", "导航相关输入处理完毕，恢复监听");
                isEnabled = true;
                resumeListening();
            }, 800);
            return;
        }

        // 处理确认状态
        if (awaitingNavigationConfirm) {
            Log.d("PatrickAI", "处理导航确认状态，用户输入: " + userText);
            if (userText.contains("是") || userText.contains("好的") || userText.contains("确定") ||
                    userText.contains("可以") || userText.contains("是的") || userText.contains("嗯") ||
                    userText.contains("行") || userText.contains("OK") || userText.contains("可以的") ||
                    userText.contains("没错")) {
                Log.d("PatrickAI", "确认导航请求");
                String reply = "好的，我来为你打开导航模式";
                speakAndCallback(reply);
                if (callback != null) {
                    callback.onNavigationRequest();
                }
            } else {
                Log.d("PatrickAI", "拒绝导航请求");
                String reply = "好的，那我们继续聊天吧";
                speakAndCallback(reply);
            }
            awaitingNavigationConfirm = false;
            return;
        }

        if (awaitingVideoConfirm) {
            Log.d("PatrickAI", "处理视频确认状态，用户输入: " + userText);
            if (userText.contains("是") || userText.contains("好的") || userText.contains("确定") ||
                    userText.contains("可以") || userText.contains("是的") || userText.contains("嗯") ||
                    userText.contains("行") || userText.contains("OK") || userText.contains("可以的") ||
                    userText.contains("没错")) {
                Log.d("PatrickAI", "确认视频请求");
                String reply = "好的，我来为你打开视频模式";
                speakAndCallback(reply);
                if (callback != null) {
                    callback.onVideoRequest();
                }
            } else {
                Log.d("PatrickAI", "拒绝视频请求");
                String reply = "好的，那我们继续聊天吧";
                speakAndCallback(reply);
            }
            awaitingVideoConfirm = false;
            return;
        }

        // 本地关键词识别（只在非导航模式下触发模式切换）
        if (!isInNavigationMode()) {
            if (userText.contains("导航") || userText.contains("地图") || userText.contains("路线")) {
                Log.d("PatrickAI", "检测到导航关键词，询问确认");
                String reply = "我听到你提到了导航，要打开导航模式吗？";
                speakAndCallback(reply);
                awaitingNavigationConfirm = true;
                return;
            }
        }

        if (userText.contains("视频") || userText.contains("摄像") || userText.contains("拍摄")) {
            Log.d("PatrickAI", "检测到视频关键词，询问确认");
            String reply = "我听到你提到了视频，要打开视频模式吗？";
            speakAndCallback(reply);
            awaitingVideoConfirm = true;
            return;
        }

        Log.d("PatrickAI", "开始AI思考处理");
        // 开始思考提示
        startThinkingPrompt();

        // 其他问题发送给AI
        String prompt = "你是Patrick，一个智能AI眼镜助手。请简洁地回复用户的问题，回复不要超过30字。用户问题：" + userText;

        apiClient.chatCompletion(prompt, new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                Log.e("PatrickAI", "AI请求失败: " + e.getMessage());
                stopThinkingPrompt();
                String errorMsg = "我遇到了一些问题，请稍后再试";
                speakAndCallback(errorMsg);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                Log.d("PatrickAI", "AI请求成功");
                stopThinkingPrompt();
                try {
                    String resp = response.body() != null ? response.body().string() : "";
                    Log.d("PatrickAI", "AI原始回复: " + resp);
                    String content = parseAIResponse(resp);
                    Log.d("PatrickAI", "AI解析后内容: " + content);
                    String replyText = content != null && !content.trim().isEmpty() ?
                            content : "我暂时无法理解你的意思";
                    speakAndCallback(replyText);
                } catch (Exception e) {
                    Log.e("PatrickAI", "AI回复解析失败: " + e.getMessage());
                    String errorMsg = "处理回复时出现问题";
                    speakAndCallback(errorMsg);
                }
            }
        });
    }

    // 判断是否是确认类的短语音
    private boolean isConfirmationWord(String text) {
        Log.d("PatrickAI", "判断是否确认词: " + text);
        String[] confirmWords = {"是", "是的", "好的", "确定", "可以", "对", "嗯", "行", "OK", "没错", "可以的"};
        String[] rejectWords = {"不", "不是", "不对", "错", "不是的", "错了", "下一个", "换一个"};

        for (String word : confirmWords) {
            if (text.contains(word)) {
                Log.d("PatrickAI", "匹配到确认词: " + word);
                return true;
            }
        }
        for (String word : rejectWords) {
            if (text.contains(word)) {
                Log.d("PatrickAI", "匹配到拒绝词: " + word);
                return true;
            }
        }
        Log.d("PatrickAI", "不是确认/拒绝词");
        return false;
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

            Log.d("PatrickAI", "=== 语音识别结果 ===");
            Log.d("PatrickAI", "原始识别结果: '" + text + "'");
            Log.d("PatrickAI", "语音时长: " + speechDuration + "ms");
            Log.d("PatrickAI", "文本长度: " + (text != null ? text.length() : 0));
            Log.d("PatrickAI", "isLast: " + isLast);

            // 对于确认类词汇，放宽过滤条件
            boolean isConfirmation = isConfirmationWord(text);
            int minLength = isConfirmation ? 300 : MIN_SPEECH_LENGTH; // 确认词最少300ms
            int minTextLen = isConfirmation ? 1 : MIN_TEXT_LENGTH; // 确认词最少1个字符

            Log.d("PatrickAI", "isConfirmation: " + isConfirmation);
            Log.d("PatrickAI", "minLength: " + minLength);
            Log.d("PatrickAI", "minTextLen: " + minTextLen);

            // 加强过滤条件，但对确认词放宽
            if (text != null && !text.trim().isEmpty() &&
                    speechDuration > minLength &&
                    text.length() >= minTextLen &&
                    (isConfirmation || !text.matches(".*[啊呀哦嗯嗯呢噢].*"))) {
                Log.d("PatrickAI", "✓ 语音通过过滤，准备处理: " + text + (isConfirmation ? " (确认词)" : ""));
                handleUserInput(text);
            } else {
                Log.d("PatrickAI", "✗ 语音被过滤: " + text);
                Log.d("PatrickAI", "过滤原因 - 时长:" + speechDuration + "ms(需要>" + minLength + "), 长度:" +
                        (text != null ? text.length() : 0) + "(需要>=" + minTextLen + ")");
                if (isLast && isEnabled && !isThinking && !isSpeaking) {
                    Log.d("PatrickAI", "重新开始监听");
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
                Log.d("PatrickAI", "未检测到语音，重新开始监听");
                if (isEnabled && !isThinking && !isSpeaking) {
                    mainHandler.postDelayed(() -> {
                        if (isEnabled && !isThinking && !isSpeaking) {
                            startContinuousListening();
                        }
                    }, 1000);
                }
            } else {
                Log.d("PatrickAI", "其他错误，延迟重新监听");
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
        isSpeaking = true;
        forceStopListening();

        mainHandler.post(() -> {
            TTSPlayer.speak(text);
            if (callback != null) {
                callback.onPatrickSpeak(text);
            }

            int speakDuration = Math.max(2000, text.length() * 100);

            mainHandler.postDelayed(() -> {
                isSpeaking = false;
                isEnabled = true;

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
        stopThinkingPrompt();
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        }
        TTSPlayer.shutdown();
        instance = null;
    }
}