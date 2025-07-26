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

import android.widget.EditText;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.text.TextWatcher;
import android.text.Editable;

import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.GeocodeQuery;
import com.amap.api.services.geocoder.GeocodeAddress;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener;
import com.amap.api.services.core.LatLonPoint;
import java.util.List;
import java.util.ArrayList;
import android.view.View;

public class NavigationActivity extends AppCompatActivity implements AMapNaviListener{

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    private AMapLocationClient locationClient;
    private AMapNavi mAMapNavi;
    private AMapNaviView mAMapNaviView;

    private NaviLatLng mStartLatLng;
    private NaviLatLng mEndLatLng = new NaviLatLng(23.00523804, 113.374652);

    private EditText etSearch;
    private ListView lvTips;
    private ArrayAdapter<String> tipsAdapter;
    private List<Tip> tipList = new ArrayList<>();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 先调用隐私合规接口
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);

        setContentView(R.layout.activity_navigation);
        // 初始化TTS并播报提示
        TTSPlayer.init(this);
        TTSPlayer.speak("请选择您要去哪里？");

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

        // 搜索终点相关初始化
        etSearch = findViewById(R.id.et_search);
        lvTips = findViewById(R.id.lv_tips);
        tipsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvTips.setAdapter(tipsAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) {
                    InputtipsQuery query = new InputtipsQuery(s.toString(), "");
                    Inputtips inputTips = new Inputtips(NavigationActivity.this, query);
                    inputTips.setInputtipsListener((list, rCode) -> {
                        if (rCode == 1000 && list != null) {
                            tipList = list;
                            List<String> names = new ArrayList<>();
                            for (Tip tip : list) {
                                names.add(tip.getName());
                            }
                            tipsAdapter.clear();
                            tipsAdapter.addAll(names);
                            tipsAdapter.notifyDataSetChanged();
                            lvTips.setVisibility(View.VISIBLE);
                        } else {
                            lvTips.setVisibility(View.GONE);
                        }
                    });
                    inputTips.requestInputtipsAsyn();
                } else {
                    lvTips.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        lvTips.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = tipList.get(position);
            etSearch.setText(tip.getName());
            lvTips.setVisibility(View.GONE);

            // 优先用Tip自带坐标
            if (tip.getPoint() != null) {
                mEndLatLng = new NaviLatLng(tip.getPoint().getLatitude(), tip.getPoint().getLongitude());
                Toast.makeText(this, "已选终点: " + tip.getName(), Toast.LENGTH_SHORT).show();
                startWalkNavigation();
            } else {
                // 若无坐标，用地理编码查找
                try {
                    GeocodeSearch geocodeSearch = new GeocodeSearch(this);
                    geocodeSearch.setOnGeocodeSearchListener(new OnGeocodeSearchListener() {
                        @Override
                        public void onGeocodeSearched(GeocodeResult result, int rCode) {
                            if (rCode == 1000 && result != null && result.getGeocodeAddressList().size() > 0) {
                                GeocodeAddress address = result.getGeocodeAddressList().get(0);
                                mEndLatLng = new NaviLatLng(address.getLatLonPoint().getLatitude(), address.getLatLonPoint().getLongitude());
                                Toast.makeText(NavigationActivity.this, "已选终点: " + tip.getName(), Toast.LENGTH_SHORT).show();
                                startWalkNavigation();
                            } else {
                                Toast.makeText(NavigationActivity.this, "未找到终点坐标", Toast.LENGTH_SHORT).show();
                            }
                        }
                        @Override
                        public void onRegeocodeSearched(com.amap.api.services.geocoder.RegeocodeResult regeocodeResult, int i) {}
                    });
                    geocodeSearch.getFromLocationNameAsyn(new GeocodeQuery(tip.getName(), ""));
                } catch (com.amap.api.services.core.AMapException e) {
                    e.printStackTrace();
                    Toast.makeText(NavigationActivity.this, "地理编码初始化失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
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
                        Log.d("导航调试", "定位成功，lat=" + location.getLatitude() + ", lon=" + location.getLongitude());
                        mStartLatLng = new NaviLatLng(location.getLatitude(), location.getLongitude());
                        //startWalkNavigation();
                    } else {
                        Log.e("导航调试", "定位失败，errorCode=" + (location != null ? location.getErrorCode() : "null")
                                + ", errorInfo=" + (location != null ? location.getErrorInfo() : "null"));
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
            Log.e("导航调试", "起点或终点为空，mStartLatLng=" + mStartLatLng + ", mEndLatLng=" + mEndLatLng);
            Toast.makeText(this, "起点或终点为空", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("导航调试", "开始路径规划，起点: " + mStartLatLng.getLatitude() + "," + mStartLatLng.getLongitude()
                + " 终点: " + mEndLatLng.getLatitude() + "," + mEndLatLng.getLongitude());
        boolean result = mAMapNavi.calculateWalkRoute(mStartLatLng, mEndLatLng);
        Log.d("导航调试", "calculateWalkRoute返回值: " + result);
        if (!result) {
            Log.e("导航调试", "calculateWalkRoute方法直接返回false，说明参数有误或导航未初始化");
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
        Log.e("导航调试", "路径规划失败回调，错误码：" + result.getErrorCode() + "，info=" + result.getErrorDescription());
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
        TTSPlayer.shutdown();
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
