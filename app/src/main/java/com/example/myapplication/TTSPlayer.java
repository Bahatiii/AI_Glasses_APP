package com.example.myapplication;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;

public class TTSPlayer {

    private static TextToSpeech tts;
    private static boolean isReady = false;
    private static String pendingText = null;
    private static TTSListener listener = null;

    public interface TTSListener {
        void onTtsStart(String utteranceId);
        void onTtsDone(String utteranceId);
        void onTtsError(String utteranceId);
    }

    public static void registerListener(TTSListener l) {
        listener = l;
    }

    public static void unregisterListener() {
        listener = null;
    }

    public static void init(Context context) {
        if (tts == null) {
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int result = tts.setLanguage(Locale.CHINESE);
                    Log.d("TTSPlayer", "setLanguage result: " + result);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTSPlayer", "中文不支持，尝试英文");
                        result = tts.setLanguage(Locale.US);
                    }
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTSPlayer", "TTS语言不可用");
                        isReady = false;
                        pendingText = null;
                        return;
                    }
                    isReady = true;
                    // attach UtteranceProgressListener to notify start/done
                    try {
                        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                            @Override
                            public void onStart(String utteranceId) {
                                Log.d("TTSPlayer", "onStart utteranceId=" + utteranceId);
                                if (listener != null) listener.onTtsStart(utteranceId);
                            }

                            @Override
                            public void onDone(String utteranceId) {
                                Log.d("TTSPlayer", "onDone utteranceId=" + utteranceId);
                                if (listener != null) listener.onTtsDone(utteranceId);
                            }

                            @Override
                            public void onError(String utteranceId) {
                                Log.d("TTSPlayer", "onError utteranceId=" + utteranceId);
                                if (listener != null) listener.onTtsError(utteranceId);
                            }
                        });
                    } catch (Exception e) {
                        Log.w("TTSPlayer", "setOnUtteranceProgressListener failed: " + e.getMessage());
                    }
                    if (pendingText != null) {
                        String uid = UUID.randomUUID().toString();
                        tts.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, uid);
                        pendingText = null;
                    }
                } else {
                    Log.e("TTSPlayer", "TTS初始化失败");
                    isReady = false;
                    pendingText = null;
                }
            });
        }
    }

    public static void speak(String text) {
        Log.d("TTSPlayer", "speak called: " + text);
        if (tts != null && isReady) {
            String uid = UUID.randomUUID().toString();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
        } else {
            pendingText = text; // 还没初始化好，先存起来，等init完成后自动播报
        }
    }

    public static void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isReady = false;
            pendingText = null;
        }
    }
}
