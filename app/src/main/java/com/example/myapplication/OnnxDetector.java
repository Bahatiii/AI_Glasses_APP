package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import ai.onnxruntime.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class OnnxDetector {

    private static final String TAG = "OnnxDetector";
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    private OrtEnvironment env;
    private OrtSession lightSession;
    private OrtSession vehicleSession;

    private Context context;

    public OnnxDetector(Context context) {
        this.context = context;
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();

            // 拷贝模型文件到内部存储，返回文件绝对路径
            String lightModelPath = copyAssetToFile("best.ort");
            String vehicleModelPath = copyAssetToFile("vehicle_detect_simplified.ort");

            lightSession = env.createSession(lightModelPath, options);
            vehicleSession = env.createSession(vehicleModelPath, options);

            Log.i(TAG, "两个模型加载成功");
        } catch (Exception e) {
            Log.e(TAG, "加载模型失败", e);
        }
    }

    private String copyAssetToFile(String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists()) {
            Log.i(TAG, "模型文件已存在: " + file.getAbsolutePath());
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

        Log.i(TAG, "模型文件拷贝完成: " + file.getAbsolutePath());
        return file.getAbsolutePath();
    }

    // 预处理，转成 float[1][3][H][W] 格式（注意模型输入尺寸要求）
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

    public String detect(Bitmap bitmap) {
        if (lightSession == null || vehicleSession == null) {
            return "模型未加载";
        }

        Set<String> allDetectedClasses = new HashSet<>();

        try {
            // 红绿灯模型输入大小一般是640x640
            float[][][][] inputLight = preprocess(bitmap, 640);
            // 车辆模型如果尺寸不同，可调整；此处也用640x640做示例
            float[][][][] inputVehicle = preprocess(bitmap, 640);

            try (OnnxTensor inputTensorLight = OnnxTensor.createTensor(env, inputLight);
                 OnnxTensor inputTensorVehicle = OnnxTensor.createTensor(env, inputVehicle)) {

                // 红绿灯模型推理
                try (OrtSession.Result results = lightSession.run(
                        java.util.Collections.singletonMap(lightSession.getInputNames().iterator().next(), inputTensorLight))) {
                    float[][][] outputArray = (float[][][]) results.get(0).getValue();
                    allDetectedClasses.addAll(parseOutput(outputArray, true));
                }

                // 车辆模型推理
                try (OrtSession.Result results = vehicleSession.run(
                        java.util.Collections.singletonMap(vehicleSession.getInputNames().iterator().next(), inputTensorVehicle))) {
                    float[][][] outputArray = (float[][][]) results.get(0).getValue();
                    allDetectedClasses.addAll(parseOutput(outputArray, false));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "推理出错", e);
            return "推理错误";
        }

        if (allDetectedClasses.isEmpty()) {
            return "未检测到目标";
        }

        StringBuilder sb = new StringBuilder();
        for (String cls : allDetectedClasses) {
            sb.append(cls).append("，");
        }
        sb.setLength(sb.length() - 1); // 去掉最后一个逗号

        return sb.toString();
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
                if (name != null) {
                    detected.add(name);
                }
            }
        }

        return detected;
    }

    private String getLightClassName(int classId) {
        switch (classId) {
            case 0:
                return "人行横道";
            case 1:
                return "红灯";
            case 2:
                return "绿灯";
            default:
                return null;
        }
    }

    private String getVehicleClassName(int classId) {
        switch (classId) {
            case 0:
                return "小汽车";
            case 1:
                return "卡车";
            case 2:
                return "公交车";
            case 3:
                return "摩托车";
            case 4:
                return "自行车";
            default:
                return null;
        }
    }
}
