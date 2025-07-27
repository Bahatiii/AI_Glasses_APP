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

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Arrays;

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


      public class PermissionHandlerActivity extends AppCompatActivity {
        // 位置权限请求码（需与发起请求时的代码保持一致）
        private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;


    @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // 此处可添加布局加载等初始化操作，例如：
            // setContentView(R.layout.activity_main);
        }

        /**
         * 权限请求结果回调方法
         * @param requestCode 请求码（用于区分不同的权限请求）
         * @param permissions 请求的权限数组
         * @param grantResults 权限授予结果数组（与permissions一一对应）
         */
    @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                               @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);

            // 打印调试信息：确认请求码是否匹配
            Log.d("PermissionDebug", "当前请求码：" + requestCode +
                    "，目标请求码：" + LOCATION_PERMISSION_REQUEST_CODE);

            // 仅处理位置权限的回调
            if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
                // 打印请求的权限和对应的结果
                Log.d("PermissionDebug", "请求的权限：" + Arrays.toString(permissions));
                Log.d("PermissionDebug", "权限结果：" + Arrays.toString(grantResults));

                // 校验权限数组和结果数组长度是否一致（避免数组越界）
                if (permissions.length != grantResults.length) {
                    Log.e("PermissionDebug", "权限数组与结果数组长度不匹配，处理失败");
                    return;
                }

                // 判断是否所有请求的权限都已授予
                boolean allPermissionsGranted = true;
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allPermissionsGranted = false;
                        break;
                    }
                }

                if (allPermissionsGranted) {
                    // 所有权限授予：执行初始化操作
                    Log.d("PermissionDebug", "所有位置权限已授予，开始初始化导航");
                    try {
                        initNavigation(null);
                        startLocationTracking();
                    } catch (Exception e) {
                        Log.e("PermissionDebug", "导航初始化失败：" + e.getMessage());
                        Toast.makeText(this, "初始化导航出错，请重试", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    // 存在未授予的权限：判断是否需要引导至设置页
                    boolean needShowRationale = false;
                    for (String permission : permissions) {
                        // 检查是否需要向用户解释为什么需要该权限
                        if (shouldShowRequestPermissionRationale(permission)) {
                            needShowRationale = true;
                            break;
                        }
                    }

                    // 确保Activity处于有效状态再显示Toast
                    if (isFinishing() || isDestroyed()) {
                        Log.d("PermissionDebug", "Activity已销毁，不显示提示");
                        return;
                    }

                    if (!needShowRationale) {
                        // 用户勾选了"不再询问"，引导至应用设置页
                        Toast.makeText(this, "请在设置中开启位置权限以使用导航", Toast.LENGTH_LONG).show();
                        Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        settingsIntent.setData(uri);
                        startActivity(settingsIntent);
                    } else {
                        // 未勾选"不再询问"，提示需要权限
                        Toast.makeText(this, "需要所有位置权限才能启动导航", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }

        /**
         * 初始化导航功能
         * @param param 可选参数（根据实际需求定义）
         */
        private void initNavigation(Object param) {
            // 导航初始化逻辑（例如初始化地图引擎、路线规划器等）
            Log.d("Navigation", "导航初始化完成");
        }

        /**
         * 开始位置跟踪
         */
        private void startLocationTracking() {
            // 位置跟踪逻辑（例如启动定位服务、监听位置变化等）
            Log.d("Location", "开始位置跟踪");
        }
    }
    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
    }
}
