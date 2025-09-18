package com.example.myapplication;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class BaiduImageUploader {

    private static final String TAG = "BaiduUploader";

    // ✅ 直接使用你提供的 access_token
    private static final String ACCESS_TOKEN = "24.5449951b38fd692d9d667ae87d6fa949.2592000.1760790930.282335-119528581";

    // 百度图像识别 API（通用物体识别）
    private static final String API_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate";
    /**
     * 上传 bitmap 图像并识别
     */
    public static void uploadImage(Bitmap bitmap, UploadCallback callback) {
        new Thread(() -> {
            try {
                // 将 Bitmap 转为 Base64
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
                byte[] imageBytes = stream.toByteArray();
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                // 构造 POST 参数
                String params = "image=" + java.net.URLEncoder.encode(base64Image, "UTF-8");

                // 构造 URL
                URL url = new URL(API_URL + "?access_token=" + ACCESS_TOKEN);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);

                // 写入参数
                OutputStream os = conn.getOutputStream();
                os.write(params.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    java.util.Scanner scanner = new java.util.Scanner(conn.getInputStream()).useDelimiter("\\A");
                    String response = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    callback.onSuccess(response);
                } else {
                    callback.onError("HTTP错误码: " + responseCode);
                }

                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "上传失败", e);
                callback.onError("异常：" + e.getMessage());
            }
        }).start();
    }

    /**
     * 回调接口
     */
    public interface UploadCallback {
        void onSuccess(String resultJson);
        void onError(String errorMessage);
    }
}
