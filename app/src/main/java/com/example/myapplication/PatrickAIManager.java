package com.example.myapplication;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

    // 扩充的确认/拒绝词库（可根据需要继续扩展）
    private static final String[] POSITIVE_CONFIRMATIONS = new String[] {
        "是", "好的", "确定", "可以", "是的", "嗯", "行", "OK", "可以的", "没错",
        "好", "去吧", "开始", "走", "马上", "就去", "好的，去", "去吧，帮我导航","要"
    };

    private static final String[] NEGATIVE_CONFIRMATIONS = new String[] {
        "不", "不要", "别", "不用", "取消", "算了", "不需要", "不用了", "不行"
    };

    // 确认状态
    private boolean awaitingNavigationConfirm = false;
    private boolean awaitingVideoConfirm = false;

    // 思考和说话状态
    private boolean isThinking = false;
    private boolean isSpeaking = false;
    private Handler thinkingHandler = new Handler(Looper.getMainLooper());
    private Runnable thinkingRunnable;
    private android.media.AudioManager audioManager;

    // 回调接口
    public interface PatrickCallback {
        void onPatrickSpeak(String text);
        void onUserSpeak(String text);
        void onNavigationRequest();
        void onVideoRequest();
    }

    private PatrickCallback callback;

    // Pending deliveries when UI callback is temporarily null
    private final java.util.List<String> pendingPatrickMessages = new java.util.ArrayList<>();
    private final java.util.List<String> pendingUserMessages = new java.util.ArrayList<>();
    private boolean pendingNavigationRequest = false;
    private boolean pendingVideoRequest = false;

    // Safe callback dispatch helpers to ensure UI thread delivery
    private void deliverPatrickSpeakToCallback(String text) {
        if (callback != null) {
            try { callback.onPatrickSpeak(text); } catch (Exception e) { Log.w("PatrickAI", "deliverPatrickSpeak failed: " + e.getMessage()); }
        } else {
            Log.w("PatrickAI", "deliverPatrickSpeak: callback is null");
        }
    }

    private void notifyPatrickSpeak(String text) {
        if (callback == null) {
            synchronized (pendingPatrickMessages) { pendingPatrickMessages.add(text); }
            Log.d("PatrickAI", "notifyPatrickSpeak: queued message as callback is null");
            return;
        }
        if (mainHandler != null) mainHandler.post(() -> deliverPatrickSpeakToCallback(text));
        else deliverPatrickSpeakToCallback(text);
    }

    private void deliverUserSpeakToCallback(String text) {
        if (callback != null) {
            try { callback.onUserSpeak(text); } catch (Exception e) { Log.w("PatrickAI", "deliverUserSpeak failed: " + e.getMessage()); }
        } else {
            Log.w("PatrickAI", "deliverUserSpeak: callback is null");
        }
    }

    private void notifyUserSpeak(String text) {
        if (callback == null) {
            synchronized (pendingUserMessages) { pendingUserMessages.add(text); }
            Log.d("PatrickAI", "notifyUserSpeak: queued user message as callback is null");
            return;
        }
        if (mainHandler != null) mainHandler.post(() -> deliverUserSpeakToCallback(text));
        else deliverUserSpeakToCallback(text);
    }

    private void deliverNavigationRequestToCallback() {
        if (callback != null) {
            try { callback.onNavigationRequest(); } catch (Exception e) { Log.w("PatrickAI", "deliverNavigationRequest failed: " + e.getMessage()); }
        } else {
            Log.w("PatrickAI", "deliverNavigationRequest: callback is null");
        }
    }

    private void notifyNavigationRequest() {
        if (callback == null) {
            pendingNavigationRequest = true;
            Log.d("PatrickAI", "notifyNavigationRequest: queued navigation request as callback is null");
            return;
        }
        if (mainHandler != null) mainHandler.post(this::deliverNavigationRequestToCallback);
        else deliverNavigationRequestToCallback();
    }

    private void deliverVideoRequestToCallback() {
        if (callback != null) {
            try { callback.onVideoRequest(); } catch (Exception e) { Log.w("PatrickAI", "deliverVideoRequest failed: " + e.getMessage()); }
        } else {
            Log.w("PatrickAI", "deliverVideoRequest: callback is null");
        }
    }

    private void notifyVideoRequest() {
        if (callback == null) {
            pendingVideoRequest = true;
            Log.d("PatrickAI", "notifyVideoRequest: queued video request as callback is null");
            return;
        }
        if (mainHandler != null) mainHandler.post(this::deliverVideoRequestToCallback);
        else deliverVideoRequestToCallback();
    }

    private PatrickAIManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.apiClient = new GLMApiClient();
        initSpeechRecognizer();
        TTSPlayer.init(this.context);
        try {
            audioManager = (android.media.AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        } catch (Exception e) {
            Log.w("PatrickAI", "audioManager init failed: " + e.getMessage());
            audioManager = null;
        }
    }

    public static PatrickAIManager getInstance(Context context) {
        if (instance == null) {
            instance = new PatrickAIManager(context);
        }
        return instance;
    }

    public void setCallback(PatrickCallback callback) {
        this.callback = callback;
        // If a UI callback was just attached, flush any pending messages/requests
        if (this.callback != null) {
            Log.d("PatrickAI", "setCallback: flushing pending messages/requests");
            // flush Patrick messages on main thread
            if (mainHandler != null) {
                mainHandler.post(() -> {
                    synchronized (pendingPatrickMessages) {
                        for (String msg : pendingPatrickMessages) {
                            try { callback.onPatrickSpeak(msg); } catch (Exception e) { Log.w("PatrickAI", "flushPatrickSpeak failed: " + e.getMessage()); }
                        }
                        pendingPatrickMessages.clear();
                    }

                    synchronized (pendingUserMessages) {
                        for (String u : pendingUserMessages) {
                            try { callback.onUserSpeak(u); } catch (Exception e) { Log.w("PatrickAI", "flushUserSpeak failed: " + e.getMessage()); }
                        }
                        pendingUserMessages.clear();
                    }

                    if (pendingNavigationRequest) {
                        try { callback.onNavigationRequest(); } catch (Exception e) { Log.w("PatrickAI", "flushNavigation failed: " + e.getMessage()); }
                        pendingNavigationRequest = false;
                    }
                    if (pendingVideoRequest) {
                        try { callback.onVideoRequest(); } catch (Exception e) { Log.w("PatrickAI", "flushVideo failed: " + e.getMessage()); }
                        pendingVideoRequest = false;
                    }
                });
            } else {
                synchronized (pendingPatrickMessages) {
                    for (String msg : pendingPatrickMessages) {
                        try { callback.onPatrickSpeak(msg); } catch (Exception e) { Log.w("PatrickAI", "flushPatrickSpeak failed: " + e.getMessage()); }
                    }
                    pendingPatrickMessages.clear();
                }
                synchronized (pendingUserMessages) {
                    for (String u : pendingUserMessages) {
                        try { callback.onUserSpeak(u); } catch (Exception e) { Log.w("PatrickAI", "flushUserSpeak failed: " + e.getMessage()); }
                    }
                    pendingUserMessages.clear();
                }
                if (pendingNavigationRequest) {
                    try { callback.onNavigationRequest(); } catch (Exception e) { Log.w("PatrickAI", "flushNavigation failed: " + e.getMessage()); }
                    pendingNavigationRequest = false;
                }
                if (pendingVideoRequest) {
                    try { callback.onVideoRequest(); } catch (Exception e) { Log.w("PatrickAI", "flushVideo failed: " + e.getMessage()); }
                    pendingVideoRequest = false;
                }
            }
        }
    }

    /**
     * 立即（如果在主线程）或尽快将 pending 队列刷新到当前 callback 上。
     * 在 Activity 恢复/返回时可调用以保证 UI 立刻收到未显示的信息。
     */
    public void flushPendingToCallback() {
        if (this.callback == null) return;
        Runnable flush = () -> {
            synchronized (pendingPatrickMessages) {
                for (String msg : pendingPatrickMessages) {
                    try { callback.onPatrickSpeak(msg); } catch (Exception e) { Log.w("PatrickAI", "flushPatrickSpeak failed: " + e.getMessage()); }
                }
                pendingPatrickMessages.clear();
            }
            synchronized (pendingUserMessages) {
                for (String u : pendingUserMessages) {
                    try { callback.onUserSpeak(u); } catch (Exception e) { Log.w("PatrickAI", "flushUserSpeak failed: " + e.getMessage()); }
                }
                pendingUserMessages.clear();
            }
            if (pendingNavigationRequest) {
                try { callback.onNavigationRequest(); } catch (Exception e) { Log.w("PatrickAI", "flushNavigation failed: " + e.getMessage()); }
                pendingNavigationRequest = false;
            }
            if (pendingVideoRequest) {
                try { callback.onVideoRequest(); } catch (Exception e) { Log.w("PatrickAI", "flushVideo failed: " + e.getMessage()); }
                pendingVideoRequest = false;
            }
        };

        // 始终通过 mainHandler 异步投递到主线程以确保 UI 完成当前生命周期/渲染操作后再刷新
        if (mainHandler != null) {
            mainHandler.post(flush);
        } else {
            // 如果没有 mainHandler（极少见），回退到直接执行
            flush.run();
        }
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
                    // register a TTS listener to keep recognizer paused during thinking TTS
                    TTSPlayer.registerListener(new com.example.myapplication.TTSPlayer.TTSListener() {
                        @Override
                        public void onTtsStart(String utteranceId) {
                            Log.d("PatrickAI", "thinking TTS started: " + utteranceId);
                            forceStopListening();
                            muteMic();
                        }

                        @Override
                        public void onTtsDone(String utteranceId) {
                            Log.d("PatrickAI", "thinking TTS done: " + utteranceId);
                            isSpeaking = false;
                            unmuteMic();
                            TTSPlayer.unregisterListener();
                        }

                        @Override
                        public void onTtsError(String utteranceId) {
                            Log.d("PatrickAI", "thinking TTS error: " + utteranceId);
                            isSpeaking = false;
                            unmuteMic();
                            TTSPlayer.unregisterListener();
                        }
                    });

                    TTSPlayer.speak("Patrick智商不高，正在思考");
                    // 始终调用 notify，内部会在 callback 为 null 时排队消息，防止返回后消息丢失
                    notifyPatrickSpeak("Patrick智商不高，正在思考");
                    thinkingHandler.postDelayed(this, 4000);

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

    // 静音/取消静音麦克风，防止 TTS 被识别
    private void muteMic() {
        try {
            if (audioManager != null) {
                audioManager.setMicrophoneMute(true);
                Log.d("PatrickAI", "mic muted");
            } else {
                Log.w("PatrickAI", "audioManager is null, cannot mute mic");
            }
        } catch (Exception e) {
            Log.w("PatrickAI", "muteMic failed: " + e.getMessage());
        }
    }

    private void unmuteMic() {
        try {
            if (audioManager != null) {
                audioManager.setMicrophoneMute(false);
                Log.d("PatrickAI", "mic unmuted");
            } else {
                Log.w("PatrickAI", "audioManager is null, cannot unmute mic");
            }
        } catch (Exception e) {
            Log.w("PatrickAI", "unmuteMic failed: " + e.getMessage());
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

        // **关键修改1：先尝试发送用户说话事件，让UI有机会展示或处理（若callback为null会排队）**
        Log.d("PatrickAI", "调用notifyUserSpeak (或排队): " + userText);
        notifyUserSpeak(userText);

        // 立即停止语音识别
        forceStopListening();
        isEnabled = false;

        // **关键修改2：在导航模式下，优先让NavigationActivity处理所有输入，缩短等待时间**
        if (isInNavigationMode()) {
            Log.d("PatrickAI", "导航模式下，优先交给NavigationActivity处理，PatrickAI不干预");
            // 不调用AI处理，缩短等待时间
            mainHandler.postDelayed(() -> {
                Log.d("PatrickAI", "导航相关输入处理完毕，恢复监听");
                isEnabled = true;
                resumeListening();
            }, 600); // **从800缩短到600ms**
            return;
        }

        // **以下只在非导航模式下执行**

        // 处理确认状态 —— 使用扩充的关键词库做同步判断（立即跳转）
        if (awaitingNavigationConfirm) {
            Log.d("PatrickAI", "处理导航确认状态（关键词判断），用户输入: " + userText);
            boolean agree = false;
            boolean reject = false;
            for (String p : POSITIVE_CONFIRMATIONS) {
                if (userText.contains(p)) { agree = true; break; }
            }
            for (String n : NEGATIVE_CONFIRMATIONS) {
                if (userText.contains(n)) { reject = true; break; }
            }

            if (agree && !reject) {
                Log.d("PatrickAI", "确认导航请求 (keywords)");
                String reply = "好的，我来为你打开导航模式";
                speakAndCallback(reply);
                // 直接notify，内部会在callback为null时进行排队
                notifyNavigationRequest();
            } else if (reject && !agree) {
                Log.d("PatrickAI", "拒绝导航请求 (keywords)");
                String reply = "好的，那我们继续聊天吧";
                speakAndCallback(reply);
            } else {
                Log.d("PatrickAI", "未能从关键词判断出明确意图，默认执行导航");
                // 如果既没有明显拒绝也没有明显同意，为了更好体验直接跳转（按你要求直接跳转）
                String reply = "好的，我来为你打开导航模式";
                speakAndCallback(reply);
                // 直接notify，内部会在callback为null时进行排队
                notifyNavigationRequest();
            }

            awaitingNavigationConfirm = false;
            return;
        }

        if (awaitingVideoConfirm) {
            Log.d("PatrickAI", "处理视频确认状态（快速判断+AI备用），用户输入: " + userText);
            boolean quickAgreeV = userText.contains("是") || userText.contains("好的") || userText.contains("确定") ||
                    userText.contains("可以") || userText.contains("是的") || userText.contains("嗯") ||
                    userText.contains("行") || userText.contains("OK") || userText.contains("可以的") ||
                    userText.contains("没错")||userText.contains("要")||userText.contains("对");
            boolean quickRejectV = userText.contains("不") || userText.contains("不要") || userText.contains("别") || userText.contains("不用");
            if (quickAgreeV) {
                Log.d("PatrickAI", "确认视频请求 (quickAgree)");
                String reply = "好的，我来为你打开视频模式";
                speakAndCallback(reply);
                // 直接notify，内部会在callback为null时进行排队
                notifyVideoRequest();
                awaitingVideoConfirm = false;
                return;
            } else if (quickRejectV) {
                Log.d("PatrickAI", "拒绝视频请求 (quickReject)");
                String reply = "好的，那我们继续聊天吧";
                speakAndCallback(reply);
                awaitingVideoConfirm = false;
                return;
            }

            String judgePromptV = "你是一个判断器，只判断用户的回复是否表示同意打开视频模式。用户回复：\"" + userText + "\" 如果表示同意，输出 YES；否则输出 NO。不要输出其他内容。";
            awaitingVideoConfirm = false;
            final boolean[] aiRespondedV = {false};
            final Runnable vidFallback = () -> {
                if (!aiRespondedV[0]) {
                    Log.d("PatrickAI", "AI视频确认超时，使用关键词回退判断");
                    boolean agree = userText.contains("是") || userText.contains("好的") || userText.contains("确定") ||
                            userText.contains("可以") || userText.contains("是的") || userText.contains("嗯") ||
                            userText.contains("行") || userText.contains("OK") || userText.contains("可以的") ||
                            userText.contains("没错");
                    if (agree) {
                        Log.d("PatrickAI", "确认视频请求 (timeout-fallback)");
                        String reply = "好的，我来为你打开视频模式";
                        speakAndCallback(reply);
                        // 直接notify，内部会在callback为null时进行排队
                        notifyVideoRequest();
                    } else {
                        Log.d("PatrickAI", "拒绝视频请求 (timeout-fallback)");
                        String reply = "好的，那我们继续聊天吧";
                        speakAndCallback(reply);
                    }
                }
            };
            mainHandler.postDelayed(vidFallback, 800);

            apiClient.chatCompletion(judgePromptV, new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    aiRespondedV[0] = true;
                    mainHandler.removeCallbacks(vidFallback);
                    Log.e("PatrickAI", "视频确认判定AI请求失败，回退到关键词判断: " + e.getMessage());
                    String reply = "抱歉，我没听清你的回答，请再说一遍。";
                    speakAndCallback(reply);
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) {
                    aiRespondedV[0] = true;
                    mainHandler.removeCallbacks(vidFallback);
                    try {
                        String resp = response.body() != null ? response.body().string() : "";
                        Log.d("PatrickAI", "AI视频判断原始回复: " + resp);
                        String lower = resp.toLowerCase();
                        boolean agree = lower.contains("yes") || lower.contains("y") || lower.contains("同意") || lower.contains("是");
                        if (agree) {
                            Log.d("PatrickAI", "AI判断为同意，执行视频");
                            String reply = "好的，我来为你打开视频模式";
                            speakAndCallback(reply);
                            // 直接notify，内部会在callback为null时进行排队
                            notifyVideoRequest();
                        } else {
                            Log.d("PatrickAI", "AI判断为不同意或不确定，取消视频");
                            String reply = "好的，那我们继续聊天吧";
                            speakAndCallback(reply);
                        }
                    } catch (Exception ex) {
                        Log.e("PatrickAI", "解析 AI 视频判断回复失败: " + ex.getMessage());
                        String reply = "抱歉，处理你的回答时出错，请再说一遍。";
                        speakAndCallback(reply);
                    }
                }
            });

            return;
        }

        // 本地关键词识别（只在非导航模式下触发模式切换）
        if (userText.contains("导航") || userText.contains("地图") || userText.contains("路线")) {
            Log.d("PatrickAI", "检测到导航关键词，询问确认");
            String reply = "我听到你提到了导航，要打开导航模式吗？";
            speakAndCallback(reply);
            awaitingNavigationConfirm = true;
            return;
        }

        if (userText.contains("视频") || userText.contains("摄像") || userText.contains("拍摄")) {
            Log.d("PatrickAI", "检测到视频关键词，询问确认");
            String reply = "我听到你提到了视频，要打开视频模式吗？";
            speakAndCallback(reply);
            awaitingVideoConfirm = true;
            return;
        }

        if (awaitingVideoConfirm) {
            Log.d("PatrickAI", "处理视频确认状态（关键词判断），用户输入: " + userText);
            boolean agreeV = false;
            boolean rejectV = false;
            for (String p : POSITIVE_CONFIRMATIONS) {
                if (userText.contains(p)) { agreeV = true; break; }
            }
            for (String n : NEGATIVE_CONFIRMATIONS) {
                if (userText.contains(n)) { rejectV = true; break; }
            }

            if (agreeV && !rejectV) {
                Log.d("PatrickAI", "确认视频请求 (keywords)");
                String reply = "好的，我来为你打开视频模式";
                speakAndCallback(reply);
                // 直接notify，内部会在callback为null时进行排队
                notifyVideoRequest();
            } else if (rejectV && !agreeV) {
                Log.d("PatrickAI", "拒绝视频请求 (keywords)");
                String reply = "好的，那我们继续聊天吧";
                speakAndCallback(reply);
            } else {
                Log.d("PatrickAI", "未能从关键词判断出明确意图，默认执行视频" );
                String reply = "好的，我来为你打开视频模式";
                speakAndCallback(reply);
                // 直接notify，内部会在callback为null时进行排队
                notifyVideoRequest();
            }

            awaitingVideoConfirm = false;
            return;
        }

    }

    private RecognizerListener recognizerListener = new RecognizerListener() {
        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            String text = "";
            try {
                text = results != null && results.getResultString() != null ? results.getResultString() : "";
            } catch (Exception e) {
                Log.w("PatrickAI", "解析识别结果失败: " + e.getMessage());
                text = "";
            }

            // 简化处理：当是最终结果时转给 handleUserInput（由 handleUserInput 内部决定后续逻辑）
            if (isLast) {
                if (!text.trim().isEmpty()) {
                    handleUserInput(text);
                } else {
                    if (isEnabled && !isThinking && !isSpeaking) {
                        mainHandler.postDelayed(() -> startContinuousListening(), 1000);
                    }
                }
            }
        }

        @Override
        public void onError(SpeechError error) {
            Log.e("PatrickAI", "语音识别错误: " + (error != null ? error.getPlainDescription(true) + " 错误码: " + error.getErrorCode() : "null"));

            if (error != null && error.getErrorCode() == 10118) {
                Log.d("PatrickAI", "未检测到语音，重新开始监听");
                if (isEnabled && !isThinking && !isSpeaking) {
                    mainHandler.postDelayed(() -> startContinuousListening(), 1000);
                }
            } else {
                Log.d("PatrickAI", "其他错误，延迟重新监听");
                if (isEnabled && !isThinking && !isSpeaking) {
                    mainHandler.postDelayed(() -> startContinuousListening(), 3000);
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
        public void onBeginOfSpeech() {
            speechStartTime = System.currentTimeMillis();
            Log.d("PatrickAI", "语音开始");
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

        // Register a TTS listener to reliably pause/resume recognizer during TTS playback
        TTSPlayer.registerListener(new com.example.myapplication.TTSPlayer.TTSListener() {
            @Override
            public void onTtsStart(String utteranceId) {
                Log.d("PatrickAI", "TTS started: " + utteranceId);
                // ensure recognizer is stopped while TTS plays
                forceStopListening();
                muteMic();
            }

            @Override
            public void onTtsDone(String utteranceId) {
                Log.d("PatrickAI", "TTS done: " + utteranceId);
                isSpeaking = false;
                isEnabled = true;
                unmuteMic();
                TTSPlayer.unregisterListener();
                mainHandler.postDelayed(() -> {
                    if (!isThinking && !isSpeaking) startContinuousListening();
                }, 400);
            }

            @Override
            public void onTtsError(String utteranceId) {
                Log.d("PatrickAI", "TTS error: " + utteranceId);
                isSpeaking = false;
                isEnabled = true;
                unmuteMic();
                TTSPlayer.unregisterListener();
                mainHandler.postDelayed(() -> {
                    if (!isThinking && !isSpeaking) startContinuousListening();
                }, 400);
            }
        });

        mainHandler.post(() -> {
            TTSPlayer.speak(text);
            // 无条件通知，内部会在 callback 为 null 时排队消息
            notifyPatrickSpeak(text);
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
        // ensure mic state restored
        try {
            unmuteMic();
        } catch (Exception e) {
            Log.w("PatrickAI", "unmute on destroy failed: " + e.getMessage());
        }
        instance = null;
    }
}
