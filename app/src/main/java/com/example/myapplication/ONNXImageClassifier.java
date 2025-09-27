package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ONNXImageClassifier {

    private static final String TAG = "ONNXImageClassifier";

    private OrtEnvironment env;
    private OrtSession session;
    private TextToSpeech tts;

    private final float CONF_THRESHOLD = 0.5f;  // 置信度阈值
    private final float NMS_THRESHOLD = 0.45f;  // NMS 阈值
    private final int INPUT_SIZE = 640;

    private static final String[] LABELS = {
            "Zebra_Cross", "R_Signal", "G_Signal"
    };

    private long lastSpeakTime = 0;
    private static final long MIN_INTERVAL_MS = 2000; // 2秒间隔播报

    public ONNXImageClassifier(Context context) throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(copyAssetToFile(context, "best.onnx").getAbsolutePath(),
                new OrtSession.SessionOptions());

        // 初始化 TTS
        tts = new TextToSpeech(context, status -> {
            if (status != TextToSpeech.ERROR) {
                tts.setLanguage(Locale.CHINESE);
                Log.d(TAG, "🗣 TTS 初始化成功");
            } else {
                Log.e(TAG, "❌ TTS 初始化失败");
            }
        });
    }

    // ================= 推理主函数 =================
    public void detectAndSpeak(Bitmap bitmap) {
        List<DetectionResult> results = detect(bitmap);
        List<String> messages = new ArrayList<>();

        for (DetectionResult r : results) {
            String label = (r.classId >= 0 && r.classId < LABELS.length) ? LABELS[r.classId] : null;
            if (label == null) continue;

            String msg = null;
            switch (label) {
                case "Zebra_Cross":
                    msg = "前方人行横道";
                    break;
                case "R_Signal":
                    msg = "现在是红灯";
                    break;
                case "G_Signal":
                    msg = "现在是绿灯";
                    break;
            }
            if (msg != null && !messages.contains(msg)) messages.add(msg);
        }

        if (!messages.isEmpty()) {
            String finalMsg = String.join("，", messages);
            maybeSpeak(finalMsg);
        }
    }

    // ================= ONNX 推理 =================
    private List<DetectionResult> detect(Bitmap bitmap) {
        List<DetectionResult> results = new ArrayList<>();
        try {
            float[][][][] input = preprocess(bitmap);
            OnnxTensor tensor = OnnxTensor.createTensor(env, input);

            OrtSession.Result ortResult = session.run(Collections.singletonMap(
                    session.getInputNames().iterator().next(), tensor));

            float[][] output = ((float[][]) ortResult.get(0).getValue());
            results = postprocess(output, bitmap.getWidth(), bitmap.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "ONNX 推理失败", e);
        }
        return results;
    }

    // ================= 预处理 =================
    private float[][][][] preprocess(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        float[][][][] input = new float[1][3][INPUT_SIZE][INPUT_SIZE];

        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int pixel = resized.getPixel(x, y);
                input[0][0][y][x] = ((pixel >> 16) & 0xFF) / 255.0f; // R
                input[0][1][y][x] = ((pixel >> 8) & 0xFF) / 255.0f;  // G
                input[0][2][y][x] = (pixel & 0xFF) / 255.0f;         // B
            }
        }
        return input;
    }

    // ================= 后处理 + NMS =================
    private List<DetectionResult> postprocess(float[][] output, int imgW, int imgH) {
        List<DetectionResult> results = new ArrayList<>();
        for (float[] det : output) {
            float objConf = det[4];
            if (objConf < CONF_THRESHOLD) continue;

            int classId = -1;
            float maxProb = 0f;
            for (int i = 5; i < det.length; i++) {
                if (det[i] > maxProb) {
                    maxProb = det[i];
                    classId = i - 5;
                }
            }
            float conf = objConf * maxProb;
            if (conf < CONF_THRESHOLD) continue;

            float cx = det[0] * imgW;
            float cy = det[1] * imgH;
            float w = det[2] * imgW;
            float h = det[3] * imgH;
            RectF box = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);

            results.add(new DetectionResult(classId, conf, box));
        }

        return nms(results, NMS_THRESHOLD);
    }

    private List<DetectionResult> nms(List<DetectionResult> list, float iouThreshold) {
        Collections.sort(list, (a, b) -> Float.compare(b.confidence, a.confidence));
        List<DetectionResult> keep = new ArrayList<>();
        boolean[] removed = new boolean[list.size()];

        for (int i = 0; i < list.size(); i++) {
            if (removed[i]) continue;
            keep.add(list.get(i));
            RectF a = list.get(i).box;
            for (int j = i + 1; j < list.size(); j++) {
                if (removed[j]) continue;
                RectF b = list.get(j).box;
                if (iou(a, b) > iouThreshold) removed[j] = true;
            }
        }
        return keep;
    }

    private float iou(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float interArea = Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);
        float unionArea = a.width() * a.height() + b.width() * b.height() - interArea;
        return interArea / unionArea;
    }

    // ================= TTS 播报 =================
    private void maybeSpeak(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastSpeakTime < MIN_INTERVAL_MS) return;
        if (tts != null && !tts.isSpeaking()) {
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null);
            lastSpeakTime = now;
        }
    }

    // ================= 结果类 =================
    public static class DetectionResult {
        public int classId;
        public float confidence;
        public RectF box;

        public DetectionResult(int classId, float confidence, RectF box) {
            this.classId = classId;
            this.confidence = confidence;
            this.box = box;
        }
    }

    // ================= 复制模型到本地 =================
    private File copyAssetToFile(Context context, String assetName) throws IOException {
        File outFile = new File(context.getFilesDir(), assetName);
        if (!outFile.exists()) {
            try (InputStream is = context.getAssets().open(assetName);
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            }
        }
        return outFile;
    }
}
