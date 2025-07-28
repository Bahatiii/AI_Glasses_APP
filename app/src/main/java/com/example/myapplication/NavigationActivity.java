package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.MapsInitializer;
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
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.GeocodeQuery;
import com.amap.api.services.geocoder.GeocodeAddress;
import com.amap.api.services.geocoder.GeocodeResult;

import com.baidu.aip.asrwakeup3.core.recog.MyRecognizer;
import com.baidu.aip.asrwakeup3.core.recog.listener.MessageStatusRecogListener;
import com.baidu.aip.asrwakeup3.core.recog.listener.OnChangeLisener;
import com.baidu.aip.asrwakeup3.uiasr.params.OnlineRecogParams;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public class NavigationActivity extends AppCompatActivity implements AMapNaviListener {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    // 高德地图导航
    private AMapLocationClient locationClient;
    private AMapNavi mAMapNavi;
    private AMapNaviView mAMapNaviView;
    private NaviLatLng mStartLatLng;
    private NaviLatLng mEndLatLng = new NaviLatLng(23.00523804, 113.374652);

    // 搜索栏
    private EditText etSearch;
    private ListView lvTips;
    private ArrayAdapter<String> tipsAdapter;
    private List<Tip> tipList = new ArrayList<>();

    // 语音识别
    private MyRecognizer myRecognizer;
    private OnlineRecogParams apiParams;
    private Handler handler;
    private OnChangeLisener onChangeLisener;
    private ImageButton btnVoice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        setContentView(R.layout.activity_navigation);

        // 权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.RECORD_AUDIO
                    },
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            initNavi(savedInstanceState);
            initBaiduVoice();
            startLocation();
        }

        // 搜索栏初始化
        etSearch = findViewById(R.id.et_search);
        lvTips = findViewById(R.id.lv_tips);
        tipsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvTips.setAdapter(tipsAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
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
            @Override public void afterTextChanged(Editable s) {}
        });

        lvTips.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = tipList.get(position);
            etSearch.setText(tip.getName());
            lvTips.setVisibility(View.GONE);

            if (tip.getPoint() != null) {
                mEndLatLng = new NaviLatLng(tip.getPoint().getLatitude(), tip.getPoint().getLongitude());
                Toast.makeText(this, "已选终点: " + tip.getName(), Toast.LENGTH_SHORT).show();
                startWalkNavigation();
            } else {
                try {
                    GeocodeSearch geocodeSearch = new GeocodeSearch(this);
                    geocodeSearch.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
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
                        @Override public void onRegeocodeSearched(com.amap.api.services.geocoder.RegeocodeResult regeocodeResult, int i) {}
                    });
                    geocodeSearch.getFromLocationNameAsyn(new GeocodeQuery(tip.getName(), ""));
                } catch (com.amap.api.services.core.AMapException e) {
                    e.printStackTrace();
                    Toast.makeText(NavigationActivity.this, "地理编码初始化失败", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 语音按钮
        btnVoice = findViewById(R.id.btn_voice);
        btnVoice.setOnClickListener(v -> startVoiceRecognize());
    }

    private void initNavi(Bundle savedInstanceState) {
        mAMapNaviView = new AMapNaviView(this);
        mAMapNaviView.onCreate(savedInstanceState);
        FrameLayout container = findViewById(R.id.navi_container);
        container.addView(mAMapNaviView);

        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导航初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        mAMapNavi.addAMapNaviListener(this);
        mAMapNavi.setUseInnerVoice(true);
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
        }
    }

    private void startWalkNavigation() {
        if (mStartLatLng == null || mEndLatLng == null) {
            Toast.makeText(this, "起点或终点为空", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean result = mAMapNavi.calculateWalkRoute(mStartLatLng, mEndLatLng);
        if (!result) Toast.makeText(this, "路径规划失败", Toast.LENGTH_SHORT).show();
    }

    /** 百度语音识别初始化 */
    private void initBaiduVoice() {
        apiParams = new OnlineRecogParams();
        handler = new Handler((Message msg) -> true);

        onChangeLisener = text -> {
            Log.i("语音识别结果", text);
            runOnUiThread(() -> {
                etSearch.setText(text);
                etSearch.setSelection(text.length());
            });
        };

        MessageStatusRecogListener listener = new MessageStatusRecogListener(handler);
        listener.setOnChangeLisener(onChangeLisener);
        myRecognizer = new MyRecognizer(this, listener);
    }

    private void startVoiceRecognize() {
        Map<String, Object> params = apiParams.fetch(
                PreferenceManager.getDefaultSharedPreferences(this)
        );
        myRecognizer.start(params);
    }

    private void stopVoiceRecognize() {
        if (myRecognizer != null) myRecognizer.stop();
    }

    @Override protected void onPause() { super.onPause(); stopVoiceRecognize(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (mAMapNavi != null) { mAMapNavi.stopNavi(); mAMapNavi.destroy(); }
        if (mAMapNaviView != null) mAMapNaviView.onDestroy();
        if (locationClient != null) { locationClient.stopLocation(); locationClient.onDestroy(); }
        if (myRecognizer != null) myRecognizer.release();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            if (granted) { initNavi(null); initBaiduVoice(); startLocation(); }
            else Toast.makeText(this, "权限被拒绝，无法使用导航和语音识别", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- AMapNaviListener ------------
    @Override public void onInitNaviFailure() {}
    @Override public void onInitNaviSuccess() {}
    @Override public void onStartNavi(int i) {}
    @Override public void onTrafficStatusUpdate() {}
    @Override public void onLocationChange(AMapNaviLocation aMapNaviLocation) {}
    @Override public void onGetNavigationText(int i, String s) {}
    @Override public void onGetNavigationText(String s) {}
    @Override public void onEndEmulatorNavi() {}
    @Override public void onArriveDestination() {}
    @Override public void onCalculateRouteFailure(int i) {}
    @Override public void onReCalculateRouteForYaw() {}
    @Override public void onReCalculateRouteForTrafficJam() {}
    @Override public void onArrivedWayPoint(int i) {}
    @Override public void onGpsOpenStatus(boolean b) {}
    @Override public void onNaviInfoUpdate(NaviInfo naviInfo) {}
    @Override public void updateCameraInfo(AMapNaviCameraInfo[] infos) {}
    @Override public void updateIntervalCameraInfo(AMapNaviCameraInfo a, AMapNaviCameraInfo b, int i) {}
    @Override public void onServiceAreaUpdate(AMapServiceAreaInfo[] infos) {}
    @Override public void showCross(AMapNaviCross cross) {}
    @Override public void hideCross() {}
    @Override public void showModeCross(AMapModelCross cross) {}
    @Override public void hideModeCross() {}
    @Override public void showLaneInfo(AMapLaneInfo[] infos, byte[] bytes, byte[] bytes1) {}
    @Override public void showLaneInfo(AMapLaneInfo info) {}
    @Override public void hideLaneInfo() {}
    @Override public void onCalculateRouteSuccess(int[] ints) {}
    @Override public void notifyParallelRoad(int i) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] infos) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo info) {}
    @Override public void updateAimlessModeStatistics(AimLessModeStat stat) {}
    @Override public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo info) {}
    @Override public void onPlayRing(int i) {}
    @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) { mAMapNavi.startNavi(NaviType.GPS); }
    @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) { Toast.makeText(this, "路径规划失败：" + result.getErrorCode(), Toast.LENGTH_LONG).show(); }
    @Override public void onNaviRouteNotify(AMapNaviRouteNotifyData data) {}
    @Override public void onGpsSignalWeak(boolean b) {}
}
