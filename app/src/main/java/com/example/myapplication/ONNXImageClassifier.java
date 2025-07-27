package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import java.util.Collections;


import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Arrays;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public class ONNXImageClassifier {

    private OrtEnvironment env;
    private OrtSession session;
    private static final String TAG = "ONNXImageClassifier";

    public ONNXImageClassifier(Context context) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();

        // 加载 assets 中的模型文件
        InputStream is = context.getAssets().open("best.ort");
        byte[] modelBytes = new byte[is.available()];
        is.read(modelBytes);
        is.close();

        session = env.createSession(modelBytes, options);
        Log.d(TAG, "模型加载成功！");
    }

    public String classify(Bitmap bitmap) throws Exception {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true);

        float[] inputData = preprocessImage(resized);
        OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(inputData),
                new long[]{1, 3, 640, 640});

        OrtSession.Result results = session.run(Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor));
        float[][][] output = (float[][][]) results.get(0).getValue();

        // 简单示例：获取置信度最高的类别
        float maxConfidence = -1f;
        int bestClass = -1;
        for (float[] box : output[0]) {
            float confidence = box[4]; // 通常第5个是目标置信度
            if (confidence > maxConfidence) {
                maxConfidence = confidence;
                bestClass = (int) box[5]; // 通常第6个是类别
            }
        }

        return "识别结果类别: " + bestClass + "，置信度：" + maxConfidence;
    }

    private float[] preprocessImage(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float[] result = new float[3 * width * height];
        int pixelCount = width * height;

        // R通道数据
        for (int i = 0; i < pixelCount; i++) {
            int x = i % width;
            int y = i / width;
            int color = bitmap.getPixel(x, y);
            result[i] = Color.red(color) / 255.0f;
        }
        // G通道数据
        for (int i = 0; i < pixelCount; i++) {
            int x = i % width;
            int y = i / width;
            int color = bitmap.getPixel(x, y);
            result[pixelCount + i] = Color.green(color) / 255.0f;
        }
        // B通道数据
        for (int i = 0; i < pixelCount; i++) {
            int x = i % width;
            int y = i / width;
            int color = bitmap.getPixel(x, y);
            result[2 * pixelCount + i] = Color.blue(color) / 255.0f;
        }

        return result;
    }
}

