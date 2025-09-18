package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

import com.amap.api.maps.AMap;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.PolylineOptions;
import android.graphics.Color;

import com.iflytek.cloud.*;

import java.util.*;

public class NavigationActivity extends AppCompatActivity implements AMapNaviListener {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private EditText etSearch;
    private ListView lvTips;
    private View searchBarContainer;
    private ArrayAdapter<String> tipsAdapter;
    private List<Tip> tipList = new ArrayList<>();
    private ImageButton btnVoice;

    private AMapLocationClient locationClient;
    private AMapNavi mAMapNavi;
    private AMapNaviView mAMapNaviView;
    private NaviLatLng mStartLatLng, mEndLatLng;

    private SpeechRecognizer mIat;
    private boolean isListening = false;
    private MapView mapView;

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
        searchBarContainer = findViewById(R.id.search_bar_container);
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
        // 监听软键盘的确认/搜索按钮
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                // 隐藏提示列表和整个搜索条
                lvTips.setVisibility(android.view.View.GONE);
                if (searchBarContainer != null) searchBarContainer.setVisibility(android.view.View.GONE);
                // 收起软键盘
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
    }

    private void setupTipsClick() {
        lvTips.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = tipList.get(position);
            etSearch.setText(tip.getName());
            lvTips.setVisibility(android.view.View.GONE);
            if (searchBarContainer != null) searchBarContainer.setVisibility(android.view.View.GONE);
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

        // 尝试拦截导航视图内置的退出/返回按钮，优先使用 SDK 提供的 listener（若存在），否则尝试查找常见的返回按钮 id 并设置点击事件
        try {
            // 反射尝试 setAMapNaviViewListener 方法（不同 SDK 版本名可能不同）
            java.lang.reflect.Method m = mAMapNaviView.getClass().getMethod("setAMapNaviViewListener", Class.forName("com.amap.api.navi.AMapNaviViewListener"));
            if (m != null) {
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        getClassLoader(),
                        new Class[]{Class.forName("com.amap.api.navi.AMapNaviViewListener")},
                        (proxyObj, method, args) -> {
                            if ("onNaviCancel".equals(method.getName()) || "onNaviBackClick".equals(method.getName())) {
                                runOnUiThread(() -> finish());
                                return null;
                            }
                            return null;
                        }
                );
                m.invoke(mAMapNaviView, proxy);
            }
        } catch (Exception ignored) {
            // 回退方案：尝试查找常见的返回按钮 id 并设置 onClick
            String[] candidateIds = new String[]{"amap_navi_view_back", "navi_view_back", "amap_navi_view_quit", "navi_quit"};
            for (String idName : candidateIds) {
                int id = getResources().getIdentifier(idName, "id", getPackageName());
                if (id != 0) {
                    View back = mAMapNaviView.findViewById(id);
                    if (back != null) {
                        back.setOnClickListener(v -> finish());
                        break;
                    }
                }
            }
            // 如果仍然没有找到，枚举 mAMapNaviView 的方法以便定位可用的 setXxxListener 方法（调试信息）
            try {
                java.lang.reflect.Method[] methods = mAMapNaviView.getClass().getMethods();
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (java.lang.reflect.Method method : methods) {
                    String name = method.getName();
                    if (name.toLowerCase().contains("listener") || name.toLowerCase().startsWith("set")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(name);
                        count++;
                        if (count >= 12) break;
                    }
                }
                final String toast = "mAMapNaviView methods (sample):\n" + (sb.length() > 0 ? sb.toString() : "(no candidate methods found)");
                final int toastCount = count;
                runOnUiThread(() -> Toast.makeText(this, toast, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 最后再尝试扫描整个窗口的视图树，查找可能的“退出/返回”按钮并附加监听（通过 id 名称、文本或 contentDescription）
        hookExitButtonByAttributes();
    }

    // 在整个 window decor view 中查找可能的退出按钮，并附加 finish() 行为
    private void hookExitButtonByAttributes() {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor == null) return;
        List<View> candidates = new ArrayList<>();
        Queue<View> q = new LinkedList<>();
        q.add(decor);
        while (!q.isEmpty()) {
            View v = q.poll();
            if (v == null) continue;
            // check id name
            try {
                int id = v.getId();
                if (id != View.NO_ID) {
                    String name = getResources().getResourceEntryName(id).toLowerCase();
                    if (name.contains("quit") || name.contains("exit") || name.contains("back") || name.contains("返回") || name.contains("退出") || name.contains("cancel")) {
                        candidates.add(v);
                    }
                }
            } catch (Exception ignored) {}
            // check contentDescription
            CharSequence cd = v.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase();
                if (s.contains("quit") || s.contains("exit") || s.contains("back") || s.contains("返回") || s.contains("退出") || s.contains("cancel")) {
                    candidates.add(v);
                }
            }
            // check text if TextView
            if (v instanceof TextView) {
                String txt = ((TextView) v).getText() != null ? ((TextView) v).getText().toString().toLowerCase() : "";
                if (txt.contains("退出") || txt.contains("返回") || txt.contains("quit") || txt.contains("exit") || txt.contains("back") || txt.contains("cancel")) {
                    candidates.add(v);
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) q.add(vg.getChildAt(i));
            }
        }

        for (View c : candidates) {
            // attach listener if clickable
            c.setClickable(true);
            c.setOnClickListener(v -> {
                Toast.makeText(this, "捕获到退出按钮（通过属性），已返回", Toast.LENGTH_SHORT).show();
                // 停止导航并返回
                try { if (mAMapNavi != null) mAMapNavi.stopNavi(); } catch (Exception ignored) {}
                finish();
            });
        }
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

    private List<com.amap.api.maps.model.Polyline> routeLines = new ArrayList<>();

    private void drawRoute(Map<Integer, AMapNaviPath> paths) {
        if (mAMapNaviView == null) return;
        AMap aMap = mAMapNaviView.getMap();

        // 移除旧路线
        for (com.amap.api.maps.model.Polyline line : routeLines) line.remove();
        routeLines.clear();

        for (AMapNaviPath path : paths.values()) {
            List<NaviLatLng> naviLatLngs = path.getCoordList();
            if (naviLatLngs == null) continue;

            PolylineOptions polylineOptions = new PolylineOptions();
            polylineOptions.addAll(convertToLatLng(naviLatLngs));
            polylineOptions.width(20f);
            polylineOptions.color(Color.BLUE);
            polylineOptions.setDottedLine(false);

            com.amap.api.maps.model.Polyline polyline = aMap.addPolyline(polylineOptions);
            routeLines.add(polyline);
        }
    }



    private List<LatLng> convertToLatLng(List<NaviLatLng> naviLatLngs) {
        List<LatLng> latLngs = new ArrayList<>();
        for (NaviLatLng naviLatLng : naviLatLngs) {
            latLngs.add(new LatLng(naviLatLng.getLatitude(), naviLatLng.getLongitude()));
        }
        return latLngs;
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
    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult result) {
        if (mAMapNavi != null) {
            Map<Integer, AMapNaviPath> paths = mAMapNavi.getNaviPaths();
            if (paths != null && paths.size() > 0) {
                drawRoute(paths);
            } else {
                Toast.makeText(this, "未获取到路径", Toast.LENGTH_SHORT).show();
            }
        }
    }


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