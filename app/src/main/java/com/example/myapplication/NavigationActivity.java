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

import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.AMapNaviListener;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.*;
import com.amap.api.services.geocoder.*;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;

import android.widget.EditText;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.text.TextWatcher;
import android.text.Editable;

import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.SpeechUtility;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class NavigationActivity extends AppCompatActivity implements AMapNaviListener {

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

    // ===== 语音识别 =====
    private SpeechRecognizer mIat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SpeechUtility.createUtility(this, SpeechConstant.APPID + "=8591e1f6");

        // 隐私合规
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);

        setContentView(R.layout.activity_navigation);

        // 初始化TTS并播报提示
        TTSPlayer.init(this);
        TTSPlayer.speak("请选择您要去哪里？");

        // 权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_PHONE_STATE
                    },
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            initNavi(savedInstanceState);
            startLocation();
        }

        // 初始化输入框和提示列表
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
                            lvTips.setVisibility(ListView.VISIBLE);
                        } else {
                            lvTips.setVisibility(ListView.GONE);
                        }
                    });
                    inputTips.requestInputtipsAsyn();
                } else {
                    lvTips.setVisibility(ListView.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        lvTips.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = tipList.get(position);
            etSearch.setText(tip.getName());
            lvTips.setVisibility(ListView.GONE);

            // 优先用Tip自带坐标
            if (tip.getPoint() != null) {
                mEndLatLng = new NaviLatLng(tip.getPoint().getLatitude(), tip.getPoint().getLongitude());
                Toast.makeText(this, "已选终点: " + tip.getName(), Toast.LENGTH_SHORT).show();
                startWalkNavigation();
            } else {
                // 地理编码查找
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
                        @Override
                        public void onRegeocodeSearched(RegeocodeResult regeocodeResult, int i) {}
                    });
                    geocodeSearch.getFromLocationNameAsyn(new GeocodeQuery(tip.getName(), ""));
                } catch (com.amap.api.services.core.AMapException e) {
                    e.printStackTrace();
                    Toast.makeText(NavigationActivity.this, "地理编码初始化失败", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ==== 初始化语音识别 ====
        initSpeechRecognizer();

        // 点击语音按钮触发语音识别
        findViewById(R.id.btn_voice).setOnClickListener(v -> {
            if (mIat == null) {
                Log.e("SpeechRecognizer", "语音识别实例未初始化，尝试重新初始化");
                initSpeechRecognizer();
                Toast.makeText(this, "语音识别正在初始化，请稍后再试", Toast.LENGTH_SHORT).show();
                return;
            }
            mIat.setParameter(SpeechConstant.RESULT_TYPE, "json");
            int ret = mIat.startListening(mRecognizerListener);
            Log.d("SpeechRecognizer", "startListening 返回值: " + ret);
            TTSPlayer.speak("请说出目的地");
        });
    }

    private void initSpeechRecognizer() {
        Log.d("SpeechRecognizer", "开始初始化语音识别");
        AtomicBoolean mIatReady = new AtomicBoolean(false);
        mIat = SpeechRecognizer.createRecognizer(this, code -> {
            Log.d("SpeechRecognizer", "初始化回调 code = " + code);
            if (code != 0) {
                Log.e("SpeechRecognizer", "初始化失败，错误码：" + code);
                runOnUiThread(() -> Toast.makeText(NavigationActivity.this,
                        "语音识别初始化失败，错误码：" + code, Toast.LENGTH_LONG).show());
                mIat = null;
            } else {
                Log.d("SpeechRecognizer", "语音识别初始化成功");
                mIatReady.set(true);
            }
        });
    }


    private RecognizerListener mRecognizerListener = new RecognizerListener() {
        @Override
        public void onBeginOfSpeech() {}

        @Override
        public void onVolumeChanged(int volume, byte[] data) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            String text = JsonParser.parseIatResult(results.getResultString()); // 需提供 JsonParser 工具
            if (!isLast) {
                etSearch.setText(text);
                etSearch.setSelection(text.length());
            }
        }

        @Override
        public void onError(SpeechError error) {
            Toast.makeText(NavigationActivity.this, "识别失败：" + error.getPlainDescription(true), Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {}
    };

    private void initNavi(Bundle savedInstanceState) {
        mAMapNaviView = new AMapNaviView(this);
        mAMapNaviView.onCreate(savedInstanceState);
        FrameLayout container = findViewById(R.id.navi_container);
        container.addView(mAMapNaviView);

        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
            if (mAMapNavi == null) {
                Toast.makeText(this, "导航实例初始化失败", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导航初始化异常：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        mAMapNavi.addAMapNaviListener(this);
        mAMapNavi.setUseInnerVoice(true);
        mAMapNavi.setIsNaviTravelView(true);
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
                    Toast.makeText(getApplicationContext(),
                            "定位失败：" + (location != null ? location.getErrorInfo() : "null"),
                            Toast.LENGTH_LONG).show();
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
        if (!result) {
            Toast.makeText(this, "路径规划失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== AMapNaviListener 必须方法 ====================
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
    @Override public void updateIntervalCameraInfo(AMapNaviCameraInfo a, AMapNaviCameraInfo b, int c) {}
    @Override public void onServiceAreaUpdate(AMapServiceAreaInfo[] aMapServiceAreaInfos) {}
    @Override public void showCross(AMapNaviCross aMapNaviCross) {}
    @Override public void hideCross() {}
    @Override public void showModeCross(AMapModelCross aMapModelCross) {}
    @Override public void hideModeCross() {}
    @Override public void showLaneInfo(AMapLaneInfo[] aMapLaneInfos, byte[] bytes, byte[] bytes1) {}
    @Override public void showLaneInfo(AMapLaneInfo aMapLaneInfo) {}
    @Override public void hideLaneInfo() {}
    @Override public void onCalculateRouteSuccess(int[] ints) {}
    @Override public void notifyParallelRoad(int i) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] infos) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo info) {}
    @Override public void updateAimlessModeStatistics(AimLessModeStat stat) {}
    @Override public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo info) {}
    @Override public void onPlayRing(int i) {}
    @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) {
        mAMapNavi.startNavi(NaviType.GPS);
    }
    @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) {
        Toast.makeText(this, "路径规划失败:" + result.getErrorDescription(), Toast.LENGTH_LONG).show();
    }
    @Override public void onNaviRouteNotify(AMapNaviRouteNotifyData data) {}
    @Override public void onGpsSignalWeak(boolean b) {}

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
        if (mIat != null) {
            mIat.cancel();
            mIat.destroy();
            mIat = null;
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
}
