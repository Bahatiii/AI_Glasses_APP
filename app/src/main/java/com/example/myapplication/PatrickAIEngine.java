package com.example.myapplication;

import android.content.Context;
import java.util.function.Consumer;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.SpeechUtility;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechConstant;
import android.util.Log; // ← 引入Log类
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Queue;
import java.util.LinkedList;
public class PatrickAIEngine {
    public enum State { READY, THINKING, WAIT_CONFIRM, SPEAKING }
    private State state = State.READY;
    private String pendingConfirmType = null; // "导航" or "视频"
    private Consumer<String> uiCallback; // 用于显示到UI
    private Context context;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private Queue<String> pendingInputs = new LinkedList<>();

    public PatrickAIEngine(Context context, Consumer<String> uiCallback) {
        this.context = context;
        this.uiCallback = uiCallback;
        Log.d("PatrickAIEngine", "初始化TTS和语音识别SDK");
        TTSHelper.init(context);

        SpeechUtility.createUtility(context, "appid=9be1e7dc");
        speechRecognizer = SpeechRecognizer.createRecognizer(context, null);
        speechRecognizer.setParameter(SpeechConstant.ASR_PTT, "0");
        speechRecognizer.setParameter(SpeechConstant.VAD_BOS, "4000");
        speechRecognizer.setParameter(SpeechConstant.VAD_EOS, "1200");
        speechRecognizer.setParameter(SpeechConstant.SAMPLE_RATE, "16000");
    }

    // 入口：收到语音文本
    public void onInput(String text) {
        Log.d("PatrickAIEngine", "onInput: 收到语音文本=" + text + ", 当前状态=" + state);
        uiCallback.accept("你: " + text);

        if (context instanceof NavigationActivity) {
            try {
                boolean handled = ((NavigationActivity) context).handleUserVoiceInput(text);
                if (handled) return;
                // if not handled by navigation, continue to AI processing
            } catch (Exception e) {
                Log.e("PatrickAIEngine", "调用 NavigationActivity.handleUserVoiceInput 异常: " + e.getMessage());
            }
        }

        if (state == State.THINKING) {
            Log.d("PatrickAIEngine", "AI正在思考，暂存新输入: " + text);
            pauseListening();
            new android.os.Handler().postDelayed(() -> {
                speak("我正在思考，请稍等...");
            }, 100); // 在speak本身的延时之外，再加一个保护性延时
            return;
        }

        if (state == State.SPEAKING) {
            Log.d("PatrickAIEngine", "AI正在播报，打断TTS并恢复识别，state=" + state);
            TTSHelper.stop();
            resumeListening();
        }
        if (state == State.WAIT_CONFIRM) {
            Log.d("PatrickAIEngine", "进入确认流程，state=" + state);
            handleConfirm(text);
            return;
        }
        if (isRepeat(text)) {
            Log.d("PatrickAIEngine", "重复输入，忽略，state=" + state);
            return;
        }
        if (text.contains("导航") || text.contains("路线") || text.contains("地图")) {
            Log.d("PatrickAIEngine", "检测到导航关键词，准备进入导航确认，state变更前=" + state);
            state = State.WAIT_CONFIRM;
            Log.d("PatrickAIEngine", "state变更后=" + state);
            pendingConfirmType = "导航";
            speak("你需要打开导航模式吗？请回答是或否。");
        } else if (text.contains("视频")) {
            Log.d("PatrickAIEngine", "检测到视频关键词，准备进入视频确认，state变更前=" + state);
            state = State.WAIT_CONFIRM;
            Log.d("PatrickAIEngine", "state变更后=" + state);
            pendingConfirmType = "视频";
            speak("你需要打开视频模式吗？请回答是或否。");
        } else {
            Log.d("PatrickAIEngine", "普通问答，调用AI接口，state变更前=" + state);
            state = State.THINKING;
            Log.d("PatrickAIEngine", "state变更后=" + state);
            callAI(text, result -> {
                Log.d("PatrickAIEngine", "AI回复=" + result + ", state=" + state);
                speak(result, () -> {
                    // 播报完毕后处理队列里的新输入
                    if (!pendingInputs.isEmpty()) {
                        String nextInput = pendingInputs.poll();
                        onInput(nextInput);
                    }
                });
            });
        }
    }

    // 处理确认流程
    private void handleConfirm(String text) {
        Log.d("PatrickAIEngine", "handleConfirm: text=" + text + ", pendingType=" + pendingConfirmType + ", state=" + state);
        if (isYes(text)) {
            if ("导航".equals(pendingConfirmType)) {
                Log.d("PatrickAIEngine", "用户确认导航，跳转NavigationActivity，state变更前=" + state);
                speak("已为你打开导航模式。", () -> openNavigation());
                state = State.READY;
                Log.d("PatrickAIEngine", "state变更后=" + state);
                pendingConfirmType = null;
                return;
            } else if ("视频".equals(pendingConfirmType)) {
                Log.d("PatrickAIEngine", "用户确认视频，跳转VideoActivity_pi，state变更前=" + state);
                speak("已为你打开视频模式。", () -> openVideo());
                state = State.READY;
                Log.d("PatrickAIEngine", "state变更后=" + state);
                pendingConfirmType = null;
                return;
            }
        } else if (isNo(text)) {
            Log.d("PatrickAIEngine", "用户拒绝，继续聊天，state变更前=" + state);
            speak("好的，继续聊天。");
            state = State.READY;
            Log.d("PatrickAIEngine", "state变更后=" + state);
            pendingConfirmType = null;
            return;
        } else {
            Log.d("PatrickAIEngine", "用户回答不明确，继续确认，state=" + state);
            speak("请明确回答是或否。");
            // 保持 WAIT_CONFIRM 状态
            return;
        }
    }

    // TTS播报，播报完毕后回到READY
    public void speak(String text) {
        Log.d("PatrickAIEngine", "speak: " + text + ", state=" + state);
        speak(text, null);
    }
    public void speak(String text, Runnable onDone) {
        Log.d("PatrickAIEngine", "speak: " + text + ", state=" + state);
        uiCallback.accept("Patrick: " + text);
        Log.d("PatrickAIEngine", "TTS播报前暂停语音识别，state=" + state);
        pauseListening();
        TTSHelper.speak(text, () -> {
            Log.d("PatrickAIEngine", "TTS播报完毕，恢复语音识别，state=" + state);
            // 只有普通问答播报后才设为 READY
            if (state == State.SPEAKING || state == State.THINKING) {
                Log.d("PatrickAIEngine", "TTS播报后，state变更前=" + state);
                state = State.READY;
                Log.d("PatrickAIEngine", "TTS播报后，state变更后=" + state);
            }
            resumeListening();
            if (onDone != null) onDone.run();
        });
    }

    // 打开导航
    private void openNavigation() {
        Log.d("PatrickAIEngine", "openNavigation: 跳转导航界面，暂停AI语音监听");
        pauseListening(); // 新增：暂停AI语音识别
        try {
            android.content.Context ctx = context;
            android.content.Intent intent = new android.content.Intent(ctx, NavigationActivity.class);
            if (!(ctx instanceof android.app.Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e("PatrickAIEngine", "openNavigation 启动 NavigationActivity 失败: " + e.getMessage());
        }
    }
    // 打开视频
    private void openVideo() {
        try {
            android.content.Context ctx = context;
            android.content.Intent intent = new android.content.Intent(ctx, VideoActivity_pi.class);
            if (!(ctx instanceof android.app.Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e("PatrickAIEngine", "openVideo 启动 VideoActivity_pi 失败: " + e.getMessage());
        }
    }

    // 智谱API调用
    public void callAI(String input, Consumer<String> callback) {
        // 新增：为AI设定角色和回复要求
        String prompt = "你是一个名为Patrick的AI眼镜助手。请以这个身份，用自然的语言回答以下问题，回答内容不要过长" + input;

        DeepSeekApiClient deepseekApi = new DeepSeekApiClient();
        // 使用包装后的 prompt 进行请求
        deepseekApi.chatCompletion(prompt, new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                callback.accept("AI请求失败：" + e.getMessage());
            }
            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                try {
                    String resp = response.body() != null ? response.body().string() : "";
                    String content = parseAIResponse(resp);
                    callback.accept(content);
                } catch (Exception ex) {
                    callback.accept("AI回复解析失败");
                }
            }
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

    // 简单重复过滤（可扩展为更复杂的缓存）
    private String lastInput = "";
    private boolean isRepeat(String text) {
        if (text.equals(lastInput)) return true;
        lastInput = text;
        return false;
    }

    // 判断确认
    private boolean isYes(String text) {
        String[] yesWords = {"是", "好的", "确定", "可以", "对", "嗯", "行", "OK", "没错", "可以的"};
        for (String w : yesWords) if (text.contains(w)) return true;
        return false;
    }
    private boolean isNo(String text) {
        String[] noWords = {"不", "不是", "不对", "错", "不是的", "错了", "下一个", "换一个","否"};
        for (String w : noWords) if (text.contains(w)) return true;
        return false;
    }

    // 新：确保识别器存在
    private void ensureRecognizer() {
        if (speechRecognizer == null) {
            Log.d("PatrickAIEngine", "ensureRecognizer: 创建 SpeechRecognizer");
            try {
                SpeechUtility.createUtility(context, "appid=9be1e7dc");
                speechRecognizer = SpeechRecognizer.createRecognizer(context, null);
                speechRecognizer.setParameter(SpeechConstant.ASR_PTT, "0");
                speechRecognizer.setParameter(SpeechConstant.VAD_BOS, "4000");
                speechRecognizer.setParameter(SpeechConstant.VAD_EOS, "1200");
                speechRecognizer.setParameter(SpeechConstant.SAMPLE_RATE, "16000");
            } catch (Exception e) {
                Log.e("PatrickAIEngine", "ensureRecognizer: 创建识别器失败: " + e.getMessage());
                speechRecognizer = null;
            }
        }
    }

    // 新：彻底销毁识别器与释放资源
    public void destroy() {
        Log.d("PatrickAIEngine", "destroy: 销毁语音识别资源");
        try {
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                } catch (Exception ignored) {}
                try {
                    speechRecognizer.destroy();
                } catch (Exception ignored) {}
                speechRecognizer = null;
            }
        } catch (Exception e) {
            Log.e("PatrickAIEngine", "destroy: 销毁异常: " + e.getMessage());
        }
        isListening = false;
    }

    // 启动语音识别（改造：缺失时重建）
    public void startListening() {
        Log.d("PatrickAIEngine", "startListening: 启动语音识别, isListening=" + isListening + ", speechRecognizer=" + speechRecognizer);
        ensureRecognizer();
        if (speechRecognizer != null && !isListening) {
            try {
                speechRecognizer.startListening(recognizerListener);
                isListening = true;
                Log.d("PatrickAIEngine", "startListening: 已调用startListening");
            } catch (Exception e) {
                Log.e("PatrickAIEngine", "startListening: 调用startListening失败: " + e.getMessage());
                isListening = false;
                // 若启动失败，销毁以便下一次重建
                try { speechRecognizer.destroy(); } catch (Exception ignored) {}
                speechRecognizer = null;
            }
        } else {
            Log.d("PatrickAIEngine", "startListening: 未启动，可能已在监听或speechRecognizer为null");
        }
    }

    // 停止语音识别（加强异常处理）
    public void stopListening() {
        Log.d("PatrickAIEngine", "stopListening: 停止语音识别, isListening=" + isListening + ", speechRecognizer=" + speechRecognizer);
        if (speechRecognizer != null && isListening) {
            try {
                speechRecognizer.cancel();
                Log.d("PatrickAIEngine", "stopListening: 已调用cancel");
            } catch (Exception e) {
                Log.e("PatrickAIEngine", "stopListening: cancel 异常: " + e.getMessage());
            }
            isListening = false;
            Log.d("PatrickAIEngine", "stopListening: 已调用cancel");
        } else {
            Log.d("PatrickAIEngine", "stopListening: 未停止，可能未在监听或speechRecognizer为null");
        }
    }

    // pause/resume 保持不销毁底层，destroy 在 Activity 切换时调用
    public void pauseListening() {
        Log.d("PatrickAIEngine", "pauseListening: 暂停语音识别, isListening=" + isListening);
        stopListening();
    }

    public void resumeListening() {
        Log.d("PatrickAIEngine", "resumeListening: 恢复语音识别, isListening=" + isListening + ", speechRecognizer=" + speechRecognizer);
        startListening();
    }

    public static String parseIflytekResult(String json) {
        try {
            JSONObject resultJson = new JSONObject(json);
            JSONArray wsArray = resultJson.getJSONArray("ws");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < wsArray.length(); i++) {
                JSONArray cwArray = wsArray.getJSONObject(i).getJSONArray("cw");
                sb.append(cwArray.getJSONObject(0).getString("w"));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e("PatrickAIEngine", "解析语音识别结果失败: " + e.getMessage());
            return "";
        }
    }

    private final RecognizerListener recognizerListener = new RecognizerListener() {
        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            String json = results.getResultString();
            Log.d("PatrickAIEngine", "RecognizerListener.onResult: " + json + ", isLast=" + isLast);
            String text = parseIflytekResult(json);
            Log.d("PatrickAIEngine", "RecognizerListener.onResult: 解析后文本: " + text);
            if (text != null && !text.trim().isEmpty()) {
                onInput(text);
            }
            if (isLast) {
                isListening = false;
                Log.d("PatrickAIEngine", "RecognizerListener: 会话结束，自动继续监听, isListening=" + isListening);
                startListening();
            }
        }
        @Override public void onError(SpeechError error) {
            Log.d("PatrickAIEngine", "RecognizerListener.onError: " + error.getPlainDescription(true));
            isListening = false;
            Log.d("PatrickAIEngine", "RecognizerListener.onError: 自动继续监听, isListening=" + isListening);
            startListening();
        }
        @Override public void onBeginOfSpeech() { Log.d("PatrickAIEngine", "RecognizerListener.onBeginOfSpeech"); }
        @Override public void onEndOfSpeech() { Log.d("PatrickAIEngine", "RecognizerListener.onEndOfSpeech"); }
        @Override public void onVolumeChanged(int volume, byte[] data) {}
        @Override public void onEvent(int eventType, int arg1, int arg2, android.os.Bundle obj) {}
    };
}