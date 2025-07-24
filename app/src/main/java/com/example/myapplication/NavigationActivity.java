package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMapException;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapModelCross;
import com.amap.api.navi.model.AMapNaviCameraInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapNaviLocation;
import com.amap.api.navi.model.AMapNaviRouteNotifyData;
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo;
import com.amap.api.navi.model.AMapServiceAreaInfo;
import com.amap.api.navi.model.AimLessModeCongestionInfo;
import com.amap.api.navi.model.AimLessModeStat;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.maps.MapsInitializer;


public class NavigationActivity extends AppCompatActivity implements AMapNaviListener{

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    private AMapLocationClient locationClient;
    private AMapNavi mAMapNavi;
    private AMapNaviView mAMapNaviView;

    private NaviLatLng mStartLatLng;
    private NaviLatLng mEndLatLng = new NaviLatLng(30.245555, 120.185944); // 杭州大剧院

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 先调用隐私合规接口
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);

        setContentView(R.layout.activity_navigation);

        // 权限检查及后续逻辑
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
            initNavi(savedInstanceState);
            startLocation();
        }
    }


    private void initNavi(Bundle savedInstanceState) {
        // 1. 创建导航视图
        mAMapNaviView = new AMapNaviView(this);
        mAMapNaviView.onCreate(savedInstanceState);

        // 2. 把导航视图添加到布局容器
        FrameLayout container = findViewById(R.id.navi_container);
        container.addView(mAMapNaviView);

        // 3. 初始化导航实例
        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
            if (mAMapNavi == null) {
                Log.e("NaviInit", "AMapNavi.getInstance returned null");
                Toast.makeText(this, "导航实例初始化失败", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导航初始化异常：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        mAMapNavi.addAMapNaviListener(this);
        mAMapNavi.setUseInnerVoice(true);       // 启用语音
        mAMapNavi.setIsNaviTravelView(true);    // 步行导航 UI 适配
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
                    if (location != null && location.getErrorCode() == 0) {
                        mStartLatLng = new NaviLatLng(location.getLatitude(), location.getLongitude());
                        startWalkNavigation();
                    } else {
                        Toast.makeText(getApplicationContext(),
                                "定位失败：" + (location != null ? location.getErrorInfo() : "null"),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });

            locationClient.startLocation();
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("AMapLocationError", "定位启动异常：" + e.getMessage());
        }
    }

    private void startWalkNavigation() {
        if (mStartLatLng == null || mEndLatLng == null) {
            Toast.makeText(this, "起点或终点为空", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean result = mAMapNavi.calculateWalkRoute(mStartLatLng, mEndLatLng);
        if (!result) {
            Toast.makeText(this, "路径规划失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onInitNaviFailure() {

    }

    @Override
    public void onInitNaviSuccess() {

    }

    @Override
    public void onStartNavi(int i) {

    }

    @Override
    public void onTrafficStatusUpdate() {

    }

    @Override
    public void onLocationChange(AMapNaviLocation aMapNaviLocation) {

    }

    @Override
    public void onGetNavigationText(int i, String s) {

    }

    @Override
    public void onGetNavigationText(String s) {

    }

    @Override
    public void onEndEmulatorNavi() {

    }

    @Override
    public void onArriveDestination() {

    }

    @Override
    public void onCalculateRouteFailure(int i) {

    }

    @Override
    public void onReCalculateRouteForYaw() {

    }

    @Override
    public void onReCalculateRouteForTrafficJam() {

    }

    @Override
    public void onArrivedWayPoint(int i) {

    }

    @Override
    public void onGpsOpenStatus(boolean b) {

    }

    @Override
    public void onNaviInfoUpdate(NaviInfo naviInfo) {

    }

    @Override
    public void updateCameraInfo(AMapNaviCameraInfo[] aMapNaviCameraInfos) {

    }

    @Override
    public void updateIntervalCameraInfo(AMapNaviCameraInfo aMapNaviCameraInfo, AMapNaviCameraInfo aMapNaviCameraInfo1, int i) {

    }

    @Override
    public void onServiceAreaUpdate(AMapServiceAreaInfo[] aMapServiceAreaInfos) {

    }

    @Override
    public void showCross(AMapNaviCross aMapNaviCross) {

    }

    @Override
    public void hideCross() {

    }

    @Override
    public void showModeCross(AMapModelCross aMapModelCross) {

    }

    @Override
    public void hideModeCross() {

    }

    @Override
    public void showLaneInfo(AMapLaneInfo[] aMapLaneInfos, byte[] bytes, byte[] bytes1) {

    }

    @Override
    public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {

    }

    @Override
    public void hideLaneInfo() {

    }

    @Override
    public void onCalculateRouteSuccess(int[] ints) {

    }

    @Override
    public void notifyParallelRoad(int i) {

    }

    @Override
    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {

    }

    @Override
    public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {

    }

    @Override
    public void updateAimlessModeStatistics(AimLessModeStat aimLessModeStat) {

    }

    @Override
    public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo aimLessModeCongestionInfo) {

    }

    @Override
    public void onPlayRing(int i) {

    }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult result) {
        // 路径规划成功后开始语音导航
        mAMapNavi.startNavi(NaviType.GPS);
    }

    @Override
    public void onCalculateRouteFailure(AMapCalcRouteResult result) {
        Toast.makeText(this, "路径规划失败，错误码：" + result.getErrorCode(), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onNaviRouteNotify(AMapNaviRouteNotifyData aMapNaviRouteNotifyData) {

    }

    @Override
    public void onGpsSignalWeak(boolean b) {

    }

    // 生命周期同步
    @Override
    protected void onResume() {
        super.onResume();
        if (mAMapNaviView != null) mAMapNaviView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAMapNaviView != null) mAMapNaviView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAMapNavi != null) {
            mAMapNavi.stopNavi();
            mAMapNavi.destroy();
        }
        if (mAMapNaviView != null) mAMapNaviView.onDestroy();
        if (locationClient != null) {
            locationClient.stopLocation();
            locationClient.onDestroy();
        }
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
                initNavi(null);
                startLocation();
            } else {
                Toast.makeText(this, "需要全部权限以开始导航", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
    }
}
