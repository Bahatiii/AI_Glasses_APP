package com.example.myapplication;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;

import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Poi;
import com.amap.api.navi.AmapNaviPage;
import com.amap.api.navi.AmapNaviParams;
import com.amap.api.navi.AmapNaviType;
import com.example.myapplication.MyINaviInfoCallback;

import java.util.ArrayList;
import java.util.List;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.widget.Toast;
import androidx.annotation.NonNull;
import android.util.Log;

public class NavigationActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private AMapLocationClient locationClient;
    //杭州几个地点坐标
    LatLng p1 = new LatLng(30.256522, 120.141043); // 杭州岳王庙
    LatLng p2 = new LatLng(30.230173, 120.150681); // 杭州雷峰塔
    LatLng p3 = new LatLng(30.258262, 120.142961); // 杭州北山街
    LatLng p4 = new LatLng(30.245555, 120.185944); // 杭州大剧院


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.READ_PHONE_STATE
                    },
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            startLocation();
        }
    }

    private void startLocation() {
        try {
            locationClient = new AMapLocationClient(getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setOnceLocation(true);
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            locationClient.setLocationOption(option);

            locationClient.setLocationListener(new AMapLocationListener() {
                @Override
                public void onLocationChanged(AMapLocation location) {
                    if (location != null) {
                        int errorCode = location.getErrorCode();
                        if (errorCode == 0) {
                            LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                            startNavigation(currentLatLng);
                        } else {
                            String errorInfo = location.getErrorInfo();
                            Toast.makeText(getApplicationContext(),
                                    "定位失败，错误码：" + errorCode + "，错误信息：" + errorInfo,
                                    Toast.LENGTH_LONG).show();
                            Log.e("AMapLocationError", "错误码：" + errorCode + ", 错误信息：" + errorInfo);
                        }
                    } else {
                        Toast.makeText(getApplicationContext(), "定位结果为空", Toast.LENGTH_SHORT).show();
                        Log.e("AMapLocationError", "定位结果为空");
                    }
                }
            });

            locationClient.startLocation();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("AMapLocationError", "定位启动异常：" + e.getMessage());
        }
    }

    private void startNavigation(LatLng currentLatLng) {
        Poi start = new Poi("当前位置", currentLatLng, "");
        List<Poi> poiList = new ArrayList<>();
        poiList.add(new Poi("杭州岳王庙", p1, ""));
        poiList.add(new Poi("杭州雷峰塔", p2, ""));
        poiList.add(new Poi("杭州北山街", p3, ""));
        Poi end = new Poi("杭州大剧院", p4, "");
        AmapNaviParams naviParams = new AmapNaviParams(start, poiList, end, AmapNaviType.DRIVER);
        naviParams.setUseInnerVoice(true); // 使用高德内置语音播报
        AmapNaviPage.getInstance().showRouteActivity(this, naviParams, new MyINaviInfoCallback());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startLocation();
            } else {
                Toast.makeText(this, "需要全部权限以开始导航", Toast.LENGTH_SHORT).show();
            }
        }
    }
}