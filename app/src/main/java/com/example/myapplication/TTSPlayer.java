package com.example.myapplication;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class TTSPlayer {

    private static TextToSpeech tts;
    private static boolean isReady = false;
    private static String pendingText = null;

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
                    if (pendingText != null) {
                        tts.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, null);
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
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
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
