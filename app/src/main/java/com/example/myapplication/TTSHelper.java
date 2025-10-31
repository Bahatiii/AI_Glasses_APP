package com.example.myapplication;

import android.content.Context;
import android.util.Log;

public class TTSHelper {

    public static void init(Context context) {
        TTSPlayer.init(context);
    }

    public static void speak(String text, Runnable onDone) {
        Log.d("TTSHelper", "speak: " + text);
        if (!TTSPlayer.isReady()) {
            Log.e("TTSHelper", "TTS未初始化，直接回调，不播报");
            if (onDone != null) onDone.run();
            return;
        }
        TTSPlayer.registerListener(new TTSPlayer.TTSListener() {
            @Override
            public void onTtsStart(String utteranceId) {
                Log.d("TTSHelper", "onTtsStart: " + utteranceId);
            }

            @Override
            public void onTtsDone(String utteranceId) {
                Log.d("TTSHelper", "onTtsDone: " + utteranceId);
                TTSPlayer.unregisterListener();
                if (onDone != null) onDone.run();
            }

            @Override
            public void onTtsError(String utteranceId) {
                Log.d("TTSHelper", "onTtsError: " + utteranceId);
                TTSPlayer.unregisterListener();
                if (onDone != null) onDone.run();
            }
        });
        TTSPlayer.speak(text);
    }

    public static void stop() {
        TTSPlayer.stop();
    }
}