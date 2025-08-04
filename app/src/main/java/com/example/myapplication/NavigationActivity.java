package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.AMapNaviRouteNotifyData;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviLatLng;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;

import java.util.ArrayList;
import java.util.List;

import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.SpeechUtility;

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

    private NaviLatLng mStartLatLng;
    private NaviLatLng mEndLatLng;

    // 讯飞语音
    private SpeechRecognizer mIat;
    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化讯飞SDK（只初始化一次，可放到Application）
        SpeechUtility.createUtility(this, "appid=9be1e7dc"); // 替换为你的appid

        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        setContentView(R.layout.activity_navigation);

        etSearch = findViewById(R.id.et_search);
        lvTips = findViewById(R.id.lv_tips);
        btnVoice = findViewById(R.id.btn_voice);

        tipsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvTips.setAdapter(tipsAdapter);

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
            initSpeechRecognizer();
            startLocation();
        }

        // 输入提示
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
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
                            lvTips.setVisibility(android.view.View.VISIBLE);
                        } else {
                            lvTips.setVisibility(android.view.View.GONE);
                        }
                    });
                    inputTips.requestInputtipsAsyn();
                } else {
                    lvTips.setVisibility(android.view.View.GONE);
                }
            }
        });

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

        // 语音按钮触摸事件
        btnVoice.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    Toast.makeText(this, "开始语音输入", Toast.LENGTH_SHORT).show();
                    startIat();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Toast.makeText(this, "停止语音输入", Toast.LENGTH_SHORT).show();
                    stopIat();
                    v.performClick();
                    return true;
            }
            return false;
        });

    }

    /** 初始化讯飞语音识别 */
    private void initSpeechRecognizer() {
        mIat = SpeechRecognizer.createRecognizer(this, code -> {
            // code==0表示初始化成功
        });
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

    private RecognizerListener mRecognizerListener = new RecognizerListener() {
        @Override
        public void onBeginOfSpeech() {
            Toast.makeText(NavigationActivity.this, "开始说话", Toast.LENGTH_SHORT).show();
        }
        @Override
        public void onError(SpeechError error) {
            Toast.makeText(NavigationActivity.this, "识别错误: " + error.getPlainDescription(true), Toast.LENGTH_SHORT).show();
        }
        @Override
        public void onEndOfSpeech() {
            Toast.makeText(NavigationActivity.this, "说话结束", Toast.LENGTH_SHORT).show();
        }
        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            String text = results.getResultString();
            if (text != null && !text.trim().isEmpty()) {
                runOnUiThread(() -> {
                    etSearch.setText(text);
                    etSearch.setSelection(text.length());
                    searchByKeyword(text);
                });
            }
        }
        @Override public void onVolumeChanged(int volume, byte[] data) {}
        @Override public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {}
    };

    private void searchByKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        InputtipsQuery query = new InputtipsQuery(keyword, "");
        Inputtips inputTips = new Inputtips(this, query);
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
                lvTips.setVisibility(android.view.View.VISIBLE);
            } else {
                lvTips.setVisibility(android.view.View.GONE);
            }
        });
        inputTips.requestInputtipsAsyn();
    }

    private void initNavi(Bundle savedInstanceState) {
        mAMapNaviView = new AMapNaviView(this);
        mAMapNaviView.onCreate(savedInstanceState);
        FrameLayout container = (FrameLayout) findViewById(R.id.navi_container);
        container.addView(mAMapNaviView);

        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导航初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        mAMapNavi.addAMapNaviListener(this);
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
                } else {
                    Toast.makeText(this, "定位失败", Toast.LENGTH_SHORT).show();
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
        if (!mAMapNavi.calculateWalkRoute(mStartLatLng, mEndLatLng)) {
            Toast.makeText(this, "路径规划失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onResume() {super.onResume();if (mAMapNaviView != null) mAMapNaviView.onResume();}
    @Override protected void onPause() {super.onPause();if (mAMapNaviView != null) mAMapNaviView.onPause();}
    @Override protected void onDestroy() {
        super.onDestroy();
        if (mAMapNavi != null) {mAMapNavi.stopNavi();mAMapNavi.destroy();}
        if (mAMapNaviView != null) mAMapNaviView.onDestroy();
        if (locationClient != null) {locationClient.stopLocation();locationClient.onDestroy();}
        if (mIat != null) {mIat.cancel(); mIat.destroy();}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {allGranted = false;break;}
            }
            if (allGranted) {
                initNavi(null);
                initSpeechRecognizer();
                startLocation();
            } else {
                Toast.makeText(this, "需要全部权限以开始导航", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------ AMapNaviListener ------------
    @Override public void onInitNaviFailure() {}
    @Override public void onInitNaviSuccess() {}
    @Override public void onStartNavi(int i) {}
    @Override public void onTrafficStatusUpdate() {}
    @Override public void onLocationChange(com.amap.api.navi.model.AMapNaviLocation aMapNaviLocation) {}
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
    @Override public void updateCameraInfo(com.amap.api.navi.model.AMapNaviCameraInfo[] infos) {}
    @Override public void updateIntervalCameraInfo(com.amap.api.navi.model.AMapNaviCameraInfo info, com.amap.api.navi.model.AMapNaviCameraInfo info1, int i) {}
    @Override public void onServiceAreaUpdate(com.amap.api.navi.model.AMapServiceAreaInfo[] infos) {}
    @Override public void showCross(com.amap.api.navi.model.AMapNaviCross cross) {}
    @Override public void hideCross() {}
    @Override public void showModeCross(com.amap.api.navi.model.AMapModelCross modelCross) {}
    @Override public void hideModeCross() {}
    @Override public void showLaneInfo(com.amap.api.navi.model.AMapLaneInfo[] infos, byte[] bytes, byte[] bytes1) {}
    @Override public void showLaneInfo(com.amap.api.navi.model.AMapLaneInfo info) {}
    @Override public void hideLaneInfo() {}
    @Override public void onCalculateRouteSuccess(int[] ints) {mAMapNavi.startNavi(NaviType.GPS);}
    @Override public void notifyParallelRoad(int i) {}
    @Override public void OnUpdateTrafficFacility(com.amap.api.navi.model.AMapNaviTrafficFacilityInfo[] infos) {}
    @Override public void OnUpdateTrafficFacility(com.amap.api.navi.model.AMapNaviTrafficFacilityInfo info) {}
    @Override public void updateAimlessModeStatistics(com.amap.api.navi.model.AimLessModeStat stat) {}
    @Override public void updateAimlessModeCongestionInfo(com.amap.api.navi.model.AimLessModeCongestionInfo info) {}
    @Override public void onPlayRing(int i) {}
    @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) {mAMapNavi.startNavi(NaviType.GPS);}
    @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) {
        Toast.makeText(this, "路径规划失败，错误码：" + result.getErrorCode(), Toast.LENGTH_LONG).show();
    }
    @Override public void onNaviRouteNotify(AMapNaviRouteNotifyData data) {}
    @Override public void onGpsSignalWeak(boolean b) {}
}