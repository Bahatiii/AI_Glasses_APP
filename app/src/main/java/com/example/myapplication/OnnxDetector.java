package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtException;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;

public class OnnxDetector {

    private static final String TAG = "OnnxDetector";
    private static final int INPUT_SIZE = 640; // 根据你模型输入尺寸调整
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    private OrtEnvironment env;
    private OrtSession session;

    private Context context;

    public OnnxDetector(Context context) {
        this.context = context;
        try {
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(loadModel(), new OrtSession.SessionOptions());
            Log.i(TAG, "模型加载成功");
        } catch (Exception e) {
            Log.e(TAG, "加载模型失败", e);
        }
    }

    private byte[] loadModel() throws Exception {
        InputStream is = context.getAssets().open("best.ort");
        byte[] model = new byte[is.available()];
        int read = is.read(model);
        is.close();
        return model;
    }

    // 预处理 Bitmap 转为 float[][][] (1,3,INPUT_SIZE,INPUT_SIZE) 输入tensor
    private float[][][][] preprocess(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        int width = resized.getWidth();
        int height = resized.getHeight();

        float[][][][] input = new float[1][3][INPUT_SIZE][INPUT_SIZE];

        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        // RGB归一化到0~1，YOLOv5输入格式是 [1,3,640,640]
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

    /**
     * 调用ONNX模型推理，输入Bitmap，返回可播报的检测结果文字
     */
    public String detect(Bitmap bitmap) {
        if (session == null) {
            return "模型未加载";
        }
        try {
            float[][][][] inputData = preprocess(bitmap);

            // try-with-resources 自动关闭 inputTensor 和 results
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputData);
                 OrtSession.Result results = session.run(
                         java.util.Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor))) {

                float[][][] outputArray = (float[][][]) results.get(0).getValue();

                Set<String> detectedClasses = new HashSet<>();

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
                        String name = getClassName(classId);
                        if (name != null) {
                            detectedClasses.add(name);
                        }
                    }
                }

                if (detectedClasses.isEmpty()) {
                    return "未检测到目标";
                }

                StringBuilder sb = new StringBuilder("前方检测到：");
                for (String cls : detectedClasses) {
                    sb.append(cls).append("，");
                }
                sb.setLength(sb.length() - 1); // 去掉最后一个逗号

                return sb.toString();
            }
        } catch (OrtException e) {
            Log.e(TAG, "ONNX推理出错", e);
            return "推理错误";
        }
    }


    private String getClassName(int classId) {
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
}

