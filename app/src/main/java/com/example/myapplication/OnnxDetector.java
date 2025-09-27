package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.speech.tts.TextToSpeech;

import ai.onnxruntime.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

public class OnnxDetector {

    private static final String TAG = "OnnxDetector";
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    private OrtEnvironment env;
    private OrtSession lightSession;
    private OrtSession vehicleSession;

    private TextToSpeech tts;
    private String lastSpokenResult = "";
    private long lastSpeakTime = 0;
    private static final long MIN_INTERVAL_MS = 3000;

    private Context context;
    private boolean isModelLoaded = false;

    public OnnxDetector(Context context) {
        this.context = context;
        Log.wtf(TAG, "🟢 OnnxDetector 构造函数开始执行");

        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();

            Log.wtf(TAG, "📂 开始拷贝 light 模型...");
            String lightModelPath = copyAssetToFile("best.ort");
            Log.wtf(TAG, "📂 light 模型拷贝完成: " + lightModelPath);

            Log.wtf(TAG, "📂 开始拷贝 vehicle 模型...");
            String vehicleModelPath = copyAssetToFile("vehicle_detect_simplified.ort");
            Log.wtf(TAG, "📂 vehicle 模型拷贝完成: " + vehicleModelPath);

            try {
                lightSession = env.createSession(lightModelPath, options);
                vehicleSession = env.createSession(vehicleModelPath, options);
                isModelLoaded = true;
                Log.wtf(TAG, "✅ 两个模型加载成功");
            } catch (OrtException e) {
                isModelLoaded = false;
                Log.e(TAG, "❌ ONNX 模型加载失败", e);
            }

            tts = new TextToSpeech(context, status -> {
                if (status != TextToSpeech.ERROR) {
                    tts.setLanguage(Locale.CHINESE);
                    Log.wtf(TAG, "🗣 TTS 初始化成功");
                } else {
                    Log.e(TAG, "❌ TTS 初始化失败");
                }
            });

        } catch (Exception e) {
            isModelLoaded = false;
            Log.e(TAG, "❌ OnnxDetector 初始化异常", e);
        }
    }

    private String copyAssetToFile(String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists()) {
            Log.wtf(TAG, "📂 模型文件已存在: " + file.getAbsolutePath());
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();
        }

        Log.wtf(TAG, "📂 模型文件拷贝完成: " + file.getAbsolutePath());
        return file.getAbsolutePath();
    }

    private float[][][][] preprocess(Bitmap bitmap, int inputSize) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        int width = resized.getWidth();
        int height = resized.getHeight();
        float[][][][] input = new float[1][3][height][width];
        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = pixels[y * width + x];
                float r = ((color >> 16) & 0xFF) / 255.f;
                float g = ((color >> 8) & 0xFF) / 255.f;
                float b = (color & 0xFF) / 255.f;
                input[0][0][y][x] = r;
                input[0][1][y][x] = g;
                input[0][2][y][x] = b;
            }
        }
        return input;
    }

    public boolean isModelLoaded() {
        return isModelLoaded;
    }

    public String detect(Bitmap bitmap) {
        if (!isModelLoaded || lightSession == null || vehicleSession == null) {
            Log.wtf(TAG, "⚠️ 模型未加载，检测跳过");
            return null;
        }

        Set<String> allDetectedClasses = new HashSet<>();

        try {
            float[][][][] inputLight = preprocess(bitmap, INPUT_SIZE);
            float[][][][] inputVehicle = preprocess(bitmap, INPUT_SIZE);

            try (OnnxTensor inputTensorLight = OnnxTensor.createTensor(env, inputLight);
                 OnnxTensor inputTensorVehicle = OnnxTensor.createTensor(env, inputVehicle)) {

                try (OrtSession.Result results = lightSession.run(
                        java.util.Collections.singletonMap(lightSession.getInputNames().iterator().next(), inputTensorLight))) {
                    float[][][] outputArray = (float[][][]) results.get(0).getValue();
                    allDetectedClasses.addAll(parseOutput(outputArray, true));
                }

                try (OrtSession.Result results = vehicleSession.run(
                        java.util.Collections.singletonMap(vehicleSession.getInputNames().iterator().next(), inputTensorVehicle))) {
                    float[][][] outputArray = (float[][][]) results.get(0).getValue();
                    allDetectedClasses.addAll(parseOutput(outputArray, false));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 推理出错", e);
            return null;
        }

        if (allDetectedClasses.isEmpty()) return null;

        // 转换为播报内容
        String speechText = mapToSpeech(allDetectedClasses);

        if (speechText != null && !speechText.isEmpty()) {
            maybeSpeak(speechText);
        }

        return speechText;
    }

    private Set<String> parseOutput(float[][][] outputArray, boolean isLightModel) {
        Set<String> detected = new HashSet<>();
        for (float[] detection : outputArray[0]) {
            float objConf = detection[4];
            float maxClassConf = -1f;
            int classId = -1;
            for (int i = 5; i < detection.length; i++) {
                if (detection[i] > maxClassConf) {
                    maxClassConf = detection[i];
                    classId = i - 5;
                }
            }
            float conf = objConf * maxClassConf;
            if (conf > CONFIDENCE_THRESHOLD) {
                String name = isLightModel ? getLightClassName(classId) : getVehicleClassName(classId);
                if (name != null) detected.add(name);
            }
        }
        return detected;
    }

    private String getLightClassName(int classId) {
        switch (classId) {
            case 0: return "人行横道";
            case 1: return "红灯";
            case 2: return "绿灯";
            default: return null;
        }
    }

    private String getVehicleClassName(int classId) {
        switch (classId) {
            case 0: return "小汽车";
            case 1: return "卡车";
            case 2: return "公交车";
            case 3: return "摩托车";
            case 4: return "自行车";
            default: return null;
        }
    }

    // 将检测结果映射为播报文字
    private String mapToSpeech(Set<String> detected) {
        if (detected.contains("人行横道")) {
            return "前方人行横道";
        }
        if (detected.contains("红灯")) {
            return "现在是红灯";
        }
        if (detected.contains("绿灯")) {
            return "现在是绿灯";
        }
        // 只要检测到任何车辆类，就统一播报“前方有来车”
        for (String cls : detected) {
            if (cls.equals("小汽车") || cls.equals("卡车") || cls.equals("公交车")
                    || cls.equals("摩托车") || cls.equals("自行车")) {
                return "前方有来车";
            }
        }
        return null;
    }

    private void speak(String text) {
        if (tts != null && !tts.isSpeaking()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private void maybeSpeak(String result) {
        long now = System.currentTimeMillis();
        if (result == null || result.isEmpty()) return;
        if (result.equals(lastSpokenResult) && now - lastSpeakTime < MIN_INTERVAL_MS) return;

        speak(result);
        lastSpokenResult = result;
        lastSpeakTime = now;
    }
}
