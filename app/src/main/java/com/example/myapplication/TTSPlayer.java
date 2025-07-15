package com.example.myapplication;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class TTSPlayer {

    private static TextToSpeech tts;

    public static void init(Context context) {
        if (tts == null) {
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                if (status != TextToSpeech.ERROR) {
                    int result = tts.setLanguage(Locale.CHINESE);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTSPlayer", "语言不支持");
                    }
                }
            });
        }
    }

    public static void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    public static void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
