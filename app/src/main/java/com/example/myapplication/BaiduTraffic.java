package com.example.myapplication;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.json.JSONObject;

public class BaiduTraffic {

    private static final String TAG = "BaiduTraffic";
    private static final String ACCESS_TOKEN = "24.d45a730ea9aad7256c15aa403556a409.2592000.1764416725.282335-119504263";
    private static final String API_URL =
            "https://aip.baidubce.com/rest/2.0/image-classify/v1/vehicle_detect";

    public static void detectTraffic(Bitmap bitmap, TrafficCallback callback) {
        new Thread(() -> {
            try {
                // 图片转 Base64
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                byte[] imageBytes = baos.toByteArray();
                String imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                // 拼接请求 URL
                String url = API_URL + "?access_token=" + ACCESS_TOKEN;

                // 构造请求体
                String param = "image=" + java.net.URLEncoder.encode(imageBase64, "UTF-8");

                // 发起 POST 请求
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                OutputStream os = conn.getOutputStream();
                os.write(param.getBytes());
                os.flush();
                os.close();

                // 获取响应
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                conn.disconnect();

                // 直接返回完整 JSON，保证 vehicle_num 可用
                String response = sb.toString();
                Log.d(TAG, "识别结果: " + response);

                if (callback != null) callback.onSuccess(response);

            } catch (Exception e) {
                Log.e(TAG, "识别出错: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }

    // 回调接口
    public interface TrafficCallback {
        void onSuccess(String result);
        void onError(String error);
    }
}
