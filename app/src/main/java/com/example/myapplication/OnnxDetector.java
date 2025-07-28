package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import ai.onnxruntime.*;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class OnnxDetector {

    private static final String TAG = "OnnxDetector";
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    private OrtEnvironment env;
    private OrtSession lightSession;
    private OrtSession carSession;

    private Context context;

    public OnnxDetector(Context context) {
        this.context = context;
        try {
            env = OrtEnvironment.getEnvironment();
            lightSession = env.createSession(loadModel("best.ort"), new OrtSession.SessionOptions());
            carSession = env.createSession(loadModel("vehicle_detect.onnx"), new OrtSession.SessionOptions());
            Log.i(TAG, "两个模型加载成功");
        } catch (Exception e) {
            Log.e(TAG, "加载模型失败", e);
        }
    }

    private byte[] loadModel(String fileName) throws Exception {
        InputStream is = context.getAssets().open(fileName);
        byte[] model = new byte[is.available()];
        int read = is.read(model);
        is.close();
        return model;
    }

    private float[][][][] preprocess(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float[][][][] input = new float[1][3][height][width];
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

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
        if (lightSession == null || carSession == null) {
            return "模型未加载";
        }

        Set<String> allDetectedClasses = new HashSet<>();
        try {
            // 预处理两个模型的输入，注意如果两个模型输入尺寸不同，可以分别resize Bitmap后调用preprocess
            Bitmap resizedForLight = Bitmap.createScaledBitmap(bitmap, 640, 640, true);   // 例：红绿灯模型输入640x640
            Bitmap resizedForCar = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth(), bitmap.getHeight(), true); // 车辆模型保持原尺寸或也可自定义尺寸

            float[][][][] inputLight = preprocess(resizedForLight);
            float[][][][] inputCar = preprocess(resizedForCar);

            try (OnnxTensor inputTensorLight = OnnxTensor.createTensor(env, inputLight);
                 OnnxTensor inputTensorCar = OnnxTensor.createTensor(env, inputCar)) {

                // 红绿灯模型推理
                try (OrtSession.Result results = lightSession.run(
                        java.util.Collections.singletonMap(lightSession.getInputNames().iterator().next(), inputTensorLight))) {
                    float[][][] outputArray = (float[][][]) results.get(0).getValue();
                    allDetectedClasses.addAll(parseOutput(outputArray, true));
                }

                // 车辆模型推理
                try (OrtSession.Result results = carSession.run(
                        java.util.Collections.singletonMap(carSession.getInputNames().iterator().next(), inputTensorCar))) {
                    float[][][] outputArray = (float[][][]) results.get(0).getValue();
                    allDetectedClasses.addAll(parseOutput(outputArray, false));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ONNX 推理出错", e);
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
}
