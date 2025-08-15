package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.location.*;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.navi.*;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.*;
import com.amap.api.services.help.*;

import com.iflytek.cloud.*;

import java.util.*;

public class NavigationActivity extends AppCompatActivity implements AMapNaviListener {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private EditText etSearch;
    private ListView lvTips;
    private ArrayAdapter<String> tipsAdapter;
    private List<Tip> tipList = new ArrayList<>();
    private ImageButton btnVoice;

    private AMapLocationClient locationClient;
    private AMapNavi mAMapNavi;
    private AMapNaviView mAMapNaviView;
    private NaviLatLng mStartLatLng, mEndLatLng;

    private SpeechRecognizer mIat;
    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SpeechUtility.createUtility(this, "appid=9be1e7dc");
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        setContentView(R.layout.activity_navigation);

        // 找到布局控件
        mAMapNaviView = findViewById(R.id.navi_view);
        mAMapNaviView.onCreate(savedInstanceState);

        etSearch = findViewById(R.id.et_search);
        lvTips = findViewById(R.id.lv_tips);
        btnVoice = findViewById(R.id.btn_voice);

        tipsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvTips.setAdapter(tipsAdapter);

        if (!checkPermissions()) {
            requestPermissions();
        } else {
            initNavi();
            initSpeechRecognizer();
            startLocation();
        }

        setupSearchListener();
        setupTipsClick();
        setupVoiceButton();
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
        }, LOCATION_PERMISSION_REQUEST_CODE);
    }

    private void setupSearchListener() {
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(android.text.Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) searchByKeyword(s.toString());
                else lvTips.setVisibility(android.view.View.GONE);
            }
        });
    }

    private void setupTipsClick() {
        lvTips.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = tipList.get(position);
            etSearch.setText(tip.getName());
            lvTips.setVisibility(android.view.View.GONE);
            if (tip.getPoint() != null) {
                mEndLatLng = new NaviLatLng(tip.getPoint().getLatitude(), tip.getPoint().getLongitude());
                Toast.makeText(this, "已选终点: " + tip.getName(), Toast.LENGTH_SHORT).show();
                startWalkNavigation();
            } else {
                Toast.makeText(this, "该地点暂无坐标", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupVoiceButton() {
        btnVoice.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Toast.makeText(this, "开始语音输入", Toast.LENGTH_SHORT).show();
                startIat();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                Toast.makeText(this, "停止语音输入", Toast.LENGTH_SHORT).show();
                stopIat();
                v.performClick();
                return true;
            }
            return false;
        });
    }

    private void initSpeechRecognizer() {
        mIat = SpeechRecognizer.createRecognizer(this, null);
    }

    private void startIat() {
        if (mIat == null) return;
        mIat.setParameter(SpeechConstant.LANGUAGE, "zh_cn");
        mIat.setParameter(SpeechConstant.ACCENT, "mandarin");
        mIat.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD);
        mIat.setParameter(SpeechConstant.RESULT_TYPE, "plain");
        mIat.startListening(mRecognizerListener);
        isListening = true;
    }

    private void stopIat() {
        if (mIat != null && isListening) {
            mIat.stopListening();
            isListening = false;
        }
    }

    private final RecognizerListener mRecognizerListener = new RecognizerListener() {
        public void onBeginOfSpeech() { Toast.makeText(NavigationActivity.this, "开始说话", Toast.LENGTH_SHORT).show(); }
        public void onError(SpeechError error) { Toast.makeText(NavigationActivity.this, "识别错误: " + error.getPlainDescription(true), Toast.LENGTH_SHORT).show(); }
        public void onEndOfSpeech() { Toast.makeText(NavigationActivity.this, "说话结束", Toast.LENGTH_SHORT).show(); }
        public void onResult(RecognizerResult results, boolean isLast) {
            String text = results.getResultString();
            if (text != null && !text.trim().isEmpty()) {
                etSearch.setText(text);
                etSearch.setSelection(text.length());
                searchByKeyword(text);
            }
        }
        public void onVolumeChanged(int volume, byte[] data) {}
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {}
    };

    private void searchByKeyword(String keyword) {
        Inputtips inputTips = new Inputtips(this, new InputtipsQuery(keyword, ""));
        inputTips.setInputtipsListener((list, rCode) -> {
            if (rCode == 1000 && list != null) {
                tipList = list;
                List<String> names = new ArrayList<>();
                for (Tip tip : list) names.add(tip.getName());
                tipsAdapter.clear();
                tipsAdapter.addAll(names);
                tipsAdapter.notifyDataSetChanged();
                lvTips.setVisibility(android.view.View.VISIBLE);
            } else lvTips.setVisibility(android.view.View.GONE);
        });
        inputTips.requestInputtipsAsyn();
    }

    private void initNavi() {
        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
            mAMapNavi.addAMapNaviListener(this);
            mAMapNavi.setUseInnerVoice(true);
        } catch (Exception e) {
            Toast.makeText(this, "导航初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // 设置导航界面可见
        AMapNaviViewOptions options = mAMapNaviView.getViewOptions();
        options.setLayoutVisible(true);
        mAMapNaviView.setViewOptions(options);
    }

    private void startLocation() {
        try {
            locationClient = new AMapLocationClient(getApplicationContext());
            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setOnceLocation(true);
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            locationClient.setLocationOption(option);
            locationClient.setLocationListener(location -> {
                if (location != null && location.getErrorCode() == 0) {
                    mStartLatLng = new NaviLatLng(location.getLatitude(), location.getLongitude());
                } else Toast.makeText(this, "定位失败", Toast.LENGTH_SHORT).show();
            });
            locationClient.startLocation();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startWalkNavigation() {
        if (mStartLatLng == null || mEndLatLng == null) {
            Toast.makeText(this, "起点或终点为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mAMapNavi.calculateWalkRoute(mStartLatLng, mEndLatLng))
            Toast.makeText(this, "路径规划失败", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() { super.onResume(); if (mAMapNaviView != null) mAMapNaviView.onResume(); }
    @Override
    protected void onPause() { super.onPause(); if (mAMapNaviView != null) mAMapNaviView.onPause(); }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAMapNavi != null) { mAMapNavi.stopNavi(); mAMapNavi.destroy(); }
        if (mAMapNaviView != null) mAMapNaviView.onDestroy();
        if (locationClient != null) { locationClient.stopLocation(); locationClient.onDestroy(); }
        if (mIat != null) { mIat.cancel(); mIat.destroy(); }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && Arrays.stream(grantResults).allMatch(r -> r == PackageManager.PERMISSION_GRANTED)) {
            initNavi();
            initSpeechRecognizer();
            startLocation();
        } else Toast.makeText(this, "需要全部权限以开始导航", Toast.LENGTH_SHORT).show();
    }

    // --- AMapNaviListener ---
    @Override public void onCalculateRouteSuccess(int[] ints) { mAMapNavi.startNavi(NaviType.GPS); }
    @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) { if (mAMapNavi != null) mAMapNavi.startNavi(NaviType.GPS); }
    @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) { Toast.makeText(this, "路径规划失败：" + result.getErrorCode(), Toast.LENGTH_LONG).show(); }

    @Override
    public void onNaviRouteNotify(AMapNaviRouteNotifyData aMapNaviRouteNotifyData) {

    }

    // --- 其他空方法保持不变 ---
    @Override public void notifyParallelRoad(int i) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {}
    @Override public void updateAimlessModeStatistics(AimLessModeStat aimLessModeStat) {}
    @Override public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo aimLessModeCongestionInfo) {}
    @Override public void onPlayRing(int i) {}
    @Override public void onGpsSignalWeak(boolean b) {}
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
    @Override public void updateCameraInfo(AMapNaviCameraInfo[] aMapNaviCameraInfos) {}
    @Override public void updateIntervalCameraInfo(AMapNaviCameraInfo aMapNaviCameraInfo, AMapNaviCameraInfo aMapNaviCameraInfo1, int i) {}
    @Override public void onServiceAreaUpdate(AMapServiceAreaInfo[] aMapServiceAreaInfos) {}
    @Override public void showCross(AMapNaviCross aMapNaviCross) {}
    @Override public void hideCross() {}
    @Override public void showModeCross(AMapModelCross aMapModelCross) {}
    @Override public void hideModeCross() {}
    @Override public void showLaneInfo(AMapLaneInfo[] aMapLaneInfos, byte[] bytes, byte[] bytes1) {}
    @Override public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {}
    @Override public void hideLaneInfo() {}
}
