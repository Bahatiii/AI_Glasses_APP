package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.util.Log;
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

    // 保留原有的语音识别（手动按钮）
    private SpeechRecognizer mIat;
    private boolean isListening = false;
    private MapView mapView;

    // 用新的 PatrickAIEngine
    private PatrickAIEngine patrickAI;
    private boolean isWaitingForDestination = false; // 等待用户确认目的地
    private String pendingDestination = null; // 待确认的目的地

    // 搜索结果选择逻辑
    private boolean isAskingForDestinationChoice = false; // 正在询问搜索结果
    private int currentAskingIndex = 0; // 当前询问的索引
    private List<Tip> currentSearchResults = new ArrayList<>(); // 当前搜索结果

    // 防止重复搜索的标志
    private boolean isSearchingProgrammatically = false;

    // TTS协调管理
    private boolean isPatrickSpeaking = false;
    private boolean isNaviSpeaking = false;
    private Handler ttsHandler = new Handler();
    private Queue<String> naviVoiceQueue = new LinkedList<>(); // 导航语音队列

    // 导航阶段状态枚举
    public enum NavigationPhase {
        IDLE,                    // 空闲状态
        SEARCHING_DESTINATION,   // 搜索目的地中
        ASKING_DESTINATION,      // 询问目的地确认中
        CHOOSING_FROM_RESULTS,   // 从搜索结果中选择
        ROUTE_PLANNING,          // 路线规划中
        NAVIGATION_STARTED       // 导航已开始
    }

    private NavigationPhase currentPhase = NavigationPhase.IDLE;

    // 防止重复处理同一条导航语音
    private String lastNaviText = "";
    private long lastNaviTextTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 进入前先销毁旧的 engine（保险起见）
        if (patrickAI != null) {
            patrickAI.destroy();
            patrickAI = null;
        }
        SpeechUtility.createUtility(this, "appid=9be1e7dc");
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        setContentView(R.layout.activity_navigation);

        // 初始化 PatrickAIEngine
        patrickAI = new PatrickAIEngine(this, text -> runOnUiThread(() -> showPatrickSpeak(text)));

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

        // Patrick进入导航模式的欢迎语
        new Handler().postDelayed(() -> {
            patrickAI.speak("已进入导航模式，请告诉我你要去哪里，或者你可以直接在搜索框输入目的地。如需退出导航，可以说退出导航");
        }, 1000);
    }

    // 展示 Patrick 语音
    private void showPatrickSpeak(String text) {
        Log.d("NavigationActivity", "showPatrickSpeak: " + text + ", isPatrickSpeaking=" + isPatrickSpeaking);
        isPatrickSpeaking = true;
        Toast.makeText(this, "Patrick: " + text, Toast.LENGTH_SHORT).show();

        // 导航语音播报时暂停语音识别更长时间
        boolean isNaviVoice = text.startsWith("导航提示：");
        int baseTime = isNaviVoice ? 150 : 100;
        int speakDuration = Math.max(2000, text.length() * baseTime);

        ttsHandler.postDelayed(() -> {
            isPatrickSpeaking = false;
            // 播放积压的导航语音
            if (!naviVoiceQueue.isEmpty()) {
                String nextNaviText = naviVoiceQueue.poll();
                playNavigationVoice(nextNaviText);
            }
        }, speakDuration);
    }

    // 处理用户语音输入的导航相关逻辑
    public void handleUserVoiceInput(String text) {
        Log.d("NavigationActivity", "handleUserVoiceInput: 收到语音文本=" + text + ", currentPhase=" + currentPhase + ", isWaitingForDestination=" + isWaitingForDestination + ", isAskingForDestinationChoice=" + isAskingForDestinationChoice);
        // 检查退出导航命令
        if (isExitNavigationCommand(text)) {
            handleExitNavigation();
            return;
        }

        // 多轮目的地选择
        if (isAskingForDestinationChoice) {
            handleDestinationChoiceResponse(text);
            return;
        }

        // 目的地确认
        if (isWaitingForDestination) {
            if (isYes(text)) {
                // 用户确认目的地
                if (pendingDestination != null) {
                    currentPhase = NavigationPhase.SEARCHING_DESTINATION;
                    isSearchingProgrammatically = true;
                    etSearch.setText(pendingDestination);
                    isSearchingProgrammatically = false;
                    patrickAI.speak("好的，正在搜索" + pendingDestination);
                    searchByKeyword(pendingDestination);
                    isWaitingForDestination = false;
                    pendingDestination = null;
                }
            } else if (isNo(text)) {
                // 用户拒绝目的地
                currentPhase = NavigationPhase.IDLE;
                patrickAI.speak("好的，请重新告诉我你要去哪里");
                isWaitingForDestination = false;
                pendingDestination = null;
            } else {
                // 用户说了新的目的地
                processPotentialDestination(text);
            }
            return;
        }

        // 正常状态，检查是否为目的地输入
        processPotentialDestination(text);
    }

    // 判断确认
    private boolean isYes(String text) {
        String[] yesWords = {"是", "好的", "确定", "可以", "对", "嗯", "行", "OK", "没错", "可以的"};
        for (String w : yesWords) if (text.contains(w)) return true;
        return false;
    }
    private boolean isNo(String text) {
        String[] noWords = {"不", "不是", "不对", "错", "不是的", "错了", "下一个", "换一个","否"};
        for (String w : noWords) if (text.contains(w)) return true;
        return false;
    }

    // 处理搜索结果选择的回应
    private void handleDestinationChoiceResponse(String text) {
        Log.d("NavigationActivity", "handleDestinationChoiceResponse: text=" + text + ", currentAskingIndex=" + currentAskingIndex + ", currentSearchResults.size=" + currentSearchResults.size());
        if (isYes(text)) {
            if (currentAskingIndex < currentSearchResults.size()) {
                Tip selectedTip = currentSearchResults.get(currentAskingIndex);
                selectDestination(selectedTip);
            }
            isAskingForDestinationChoice = false;
        } else if (isNo(text)) {
            askNextDestination();
        } else {
            // 用户输入无法识别，重新询问当前选项
            if (currentAskingIndex < currentSearchResults.size()) {
                Tip currentTip = currentSearchResults.get(currentAskingIndex);
                patrickAI.speak("请确认，你要去" + currentTip.getName() + "吗？");
            }
        }
    }

    // 询问下一个目的地
    private void askNextDestination() {
        Log.d("NavigationActivity", "askNextDestination: currentAskingIndex=" + currentAskingIndex + ", currentSearchResults.size=" + currentSearchResults.size());
        currentAskingIndex++;
        if (currentAskingIndex < currentSearchResults.size()) {
            Tip nextTip = currentSearchResults.get(currentAskingIndex);
            patrickAI.speak("那你要去" + nextTip.getName() + "吗？");
        } else {
            if (currentSearchResults.size() > 0) {
                Tip firstTip = currentSearchResults.get(0);
                patrickAI.speak("好的，我为你自动选择第一个选项：" + firstTip.getName());
                selectDestination(firstTip);
            } else {
                currentPhase = NavigationPhase.IDLE;
                patrickAI.speak("没有找到合适的地点，请重新搜索");
            }
            isAskingForDestinationChoice = false;
        }
    }

    // 选择目的地并开始导航
    private void selectDestination(Tip selectedTip) {
        Log.d("NavigationActivity", "selectDestination: " + (selectedTip != null ? selectedTip.getName() : "null") + ", currentPhase变更前=" + currentPhase);
        currentPhase = NavigationPhase.ROUTE_PLANNING;
        Log.d("NavigationActivity", "selectDestination: currentPhase变更后=" + currentPhase);
        isSearchingProgrammatically = true;
        etSearch.setText(selectedTip.getName());
        isSearchingProgrammatically = false;
        lvTips.setVisibility(View.GONE);
        if (searchBarContainer != null) searchBarContainer.setVisibility(View.GONE);

        if (selectedTip.getPoint() != null) {
            mEndLatLng = new NaviLatLng(selectedTip.getPoint().getLatitude(), selectedTip.getPoint().getLongitude());
            patrickAI.speak("好的，我为你规划到" + selectedTip.getName() + "的路线");
            Toast.makeText(this, "已选终点: " + selectedTip.getName(), Toast.LENGTH_SHORT).show();
            startWalkNavigation();
        } else {
            currentPhase = NavigationPhase.IDLE;
            Toast.makeText(this, "该地点暂无坐标", Toast.LENGTH_SHORT).show();
            patrickAI.speak("抱歉，该地点无法导航，请重新选择");
        }
    }

    // 处理可能的目的地输入
    private void processPotentialDestination(String text) {
        Log.d("NavigationActivity", "processPotentialDestination: text=" + text);
        String[] nonLocationWords = {"你好", "再见", "谢谢", "天气", "时间", "今天", "明天", "什么", "怎么", "为什么"};
        for (String word : nonLocationWords) {
            if (text.contains(word)) return;
        }
        if (text.contains("去") || text.contains("到") || text.contains("找") ||
                text.contains("导航") || text.contains("路线") ||
                text.length() > 2) {
            String destination = extractDestination(text);
            if (destination != null && !destination.trim().isEmpty()) {
                Log.d("NavigationActivity", "processPotentialDestination: 检测到目的地=" + destination + ", currentPhase变更前=" + currentPhase);
                currentPhase = NavigationPhase.ASKING_DESTINATION;
                Log.d("NavigationActivity", "processPotentialDestination: currentPhase变更后=" + currentPhase);
                pendingDestination = destination;
                isWaitingForDestination = true;
                patrickAI.speak("你是要去" + destination + "吗？");
            }
        }
    }

    // 从用户输入中提取目的地
    private String extractDestination(String text) {
        String destination = text.replaceAll("我要去|我想去|去|到|找|导航到|路线到", "").trim();
        destination = destination.replaceAll("怎么走|在哪里|怎么去", "").trim();
        return destination;
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
                if (isSearchingProgrammatically) {
                    return;
                }
                if (s.length() > 0) {
                    searchByKeyword(s.toString());
                } else {
                    lvTips.setVisibility(android.view.View.GONE);
                }
            }
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                lvTips.setVisibility(android.view.View.GONE);
                if (searchBarContainer != null) searchBarContainer.setVisibility(android.view.View.GONE);
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
            if (position < tipList.size()) {
                selectDestination(tipList.get(position));
                isAskingForDestinationChoice = false;
            }
        });
    }

    private void setupVoiceButton() {
        btnVoice.setOnClickListener(v -> {
            Toast.makeText(this, "Patrick正在持续监听，你可以直接说话。这个按钮用于手动语音输入", Toast.LENGTH_LONG).show();
        });

        btnVoice.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Toast.makeText(this, "开始手动语音输入", Toast.LENGTH_SHORT).show();
                startIat();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                Toast.makeText(this, "停止手动语音输入", Toast.LENGTH_SHORT).show();
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
                handleUserVoiceInput(text);
            }
        }
        public void onVolumeChanged(int volume, byte[] data) {}
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {}
    };

    private void searchByKeyword(String keyword) {
        Log.d("NavigationActivity", "searchByKeyword: keyword=" + keyword);
        Inputtips inputTips = new Inputtips(this, new InputtipsQuery(keyword, ""));
        inputTips.setInputtipsListener((list, rCode) -> {
            Log.d("NavigationActivity", "searchByKeyword: InputtipsListener rCode=" + rCode + ", list.size=" + (list != null ? list.size() : 0));
            if (rCode == 1000 && list != null) {
                tipList = list;
                currentSearchResults = new ArrayList<>(list);
                List<String> names = new ArrayList<>();
                for (Tip tip : list) names.add(tip.getName());
                tipsAdapter.clear();
                tipsAdapter.addAll(names);
                tipsAdapter.notifyDataSetChanged();
                lvTips.setVisibility(android.view.View.VISIBLE);

                if (names.size() > 0) {
                    Log.d("NavigationActivity", "searchByKeyword: 找到地点数量=" + names.size());
                    currentPhase = NavigationPhase.CHOOSING_FROM_RESULTS;
                    Log.d("NavigationActivity", "searchByKeyword: currentPhase变更后=" + currentPhase);
                    currentAskingIndex = 0;
                    isAskingForDestinationChoice = true; // ← 新增
                    String firstLocation = currentSearchResults.get(0).getName();
                    if (names.size() == 1) {
                        patrickAI.speak("找到了" + firstLocation + "，确认去这里吗？");
                    } else {
                        patrickAI.speak("找到了" + names.size() + "个地点，第一个是" + firstLocation + "，去这里吗？");
                    }
                } else {
                    Log.d("NavigationActivity", "searchByKeyword: 没有找到相关地点");
                    currentPhase = NavigationPhase.IDLE;
                    Log.d("NavigationActivity", "searchByKeyword: currentPhase变更后=" + currentPhase);
                    patrickAI.speak("没有找到相关地点，请重新输入");
                }
            } else {
                currentPhase = NavigationPhase.IDLE;
                lvTips.setVisibility(android.view.View.GONE);
                patrickAI.speak("搜索出现问题，请重试");
            }
        });
        inputTips.requestInputtipsAsyn();
    }

    private void initNavi() {
        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
            mAMapNavi.addAMapNaviListener(this);
            mAMapNavi.setUseInnerVoice(false); // 禁用内置语音
        } catch (Exception e) {
            Toast.makeText(this, "导航初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        AMapNaviViewOptions options = mAMapNaviView.getViewOptions();
        options.setLayoutVisible(true);
        mAMapNaviView.setViewOptions(options);
        hookExitButtonByAttributes();
    }

    private void hookExitButtonByAttributes() {
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        if (decor == null) return;
        List<View> candidates = new ArrayList<>();
        Queue<View> q = new LinkedList<>();
        q.add(decor);
        while (!q.isEmpty()) {
            View v = q.poll();
            if (v == null) continue;
            try {
                int id = v.getId();
                if (id != View.NO_ID) {
                    String name = getResources().getResourceEntryName(id).toLowerCase();
                    if (name.contains("quit") || name.contains("exit") || name.contains("back") || name.contains("返回") || name.contains("退出") || name.contains("cancel")) {
                        candidates.add(v);
                    }
                }
            } catch (Exception ignored) {}
            CharSequence cd = v.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase();
                if (s.contains("quit") || s.contains("exit") || s.contains("back") || s.contains("返回") || s.contains("退出") || s.contains("cancel")) {
                    candidates.add(v);
                }
            }
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
            c.setClickable(true);
            c.setOnClickListener(v -> {
                Toast.makeText(this, "捕获到退出按钮（通过属性），已返回", Toast.LENGTH_SHORT).show();
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
        Log.d("NavigationActivity", "startWalkNavigation: mStartLatLng=" + mStartLatLng + ", mEndLatLng=" + mEndLatLng);
        if (mStartLatLng == null || mEndLatLng == null) {
            Toast.makeText(this, "起点或终点为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mAMapNavi.calculateWalkRoute(mStartLatLng, mEndLatLng)) {
            Toast.makeText(this, "路径规划失败", Toast.LENGTH_SHORT).show();
        } else {
            Log.d("NavigationActivity", "开始路线规划");
        }
    }

    private List<com.amap.api.maps.model.Polyline> routeLines = new ArrayList<>();

    private void drawRoute(Map<Integer, AMapNaviPath> paths) {
        if (mAMapNaviView == null) return;
        AMap aMap = mAMapNaviView.getMap();
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
    protected void onResume() {
        super.onResume();
        if (mAMapNaviView != null) mAMapNaviView.onResume();
        // 每次进入都销毁重建
        if (patrickAI != null) {
            patrickAI.destroy();
        }
        patrickAI = new PatrickAIEngine(this, text -> runOnUiThread(() -> showPatrickSpeak(text)));
        patrickAI.startListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAMapNaviView != null) mAMapNaviView.onPause();
        if (patrickAI != null) {
            patrickAI.destroy();
            patrickAI = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAMapNavi != null) { mAMapNavi.stopNavi(); mAMapNavi.destroy(); }
        if (mAMapNaviView != null) mAMapNaviView.onDestroy();
        if (locationClient != null) { locationClient.stopLocation(); locationClient.onDestroy(); }
        if (mIat != null) { mIat.cancel(); mIat.destroy(); }
        if (patrickAI != null) {
            patrickAI.destroy();
            patrickAI = null;
        }
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

    // === AMapNaviListener 实现 ===
    @Override public void onCalculateRouteSuccess(int[] ints) { mAMapNavi.startNavi(NaviType.GPS); }

    @Override
    public void onCalculateRouteSuccess(AMapCalcRouteResult result) {
        Log.d("NavigationActivity", "onCalculateRouteSuccess: 路线规划成功, currentPhase变更前=" + currentPhase);
        if (mAMapNavi != null) {
            Map<Integer, AMapNaviPath> paths = mAMapNavi.getNaviPaths();
            if (paths != null && paths.size() > 0) {
                drawRoute(paths);
                currentPhase = NavigationPhase.NAVIGATION_STARTED;
                Log.d("NavigationActivity", "onCalculateRouteSuccess: currentPhase变更后=" + currentPhase);
                patrickAI.speak("路线规划成功，导航开始");
            } else {
                currentPhase = NavigationPhase.IDLE;
                Toast.makeText(this, "未获取到路径", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onCalculateRouteFailure(AMapCalcRouteResult result) {
        Log.d("NavigationActivity", "onCalculateRouteFailure: 路线规划失败, currentPhase变更前=" + currentPhase);
        currentPhase = NavigationPhase.IDLE;
        Log.d("NavigationActivity", "onCalculateRouteFailure: currentPhase变更后=" + currentPhase);
        Toast.makeText(this, "路径规划失败：" + result.getErrorCode(), Toast.LENGTH_LONG).show();
        patrickAI.speak("路径规划失败，请重新选择目的地");
    }

    @Override public void onNaviRouteNotify(AMapNaviRouteNotifyData aMapNaviRouteNotifyData) {}
    @Override public void notifyParallelRoad(int i) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {}
    @Override public void updateAimlessModeStatistics(AimLessModeStat aimLessModeStat) {}
    @Override public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo aimLessModeCongestionInfo) {}
    @Override public void onPlayRing(int i) {}

    @Override
    public void onGetNavigationText(int type, String naviText) {
        handleNavigationVoice(naviText);
    }
    @Override
    public void onGetNavigationText(String naviText) {
        handleNavigationVoice(naviText);
    }

    @Override
    public void onGpsSignalWeak(boolean isWeak) {
        if (isWeak && currentPhase == NavigationPhase.NAVIGATION_STARTED) {
            patrickAI.speak("GPS信号较弱，请注意路况");
        }
    }

    @Override public void onInitNaviFailure() {}
    @Override public void onInitNaviSuccess() {}
    @Override public void onStartNavi(int i) {}
    @Override public void onTrafficStatusUpdate() {}
    @Override public void onLocationChange(AMapNaviLocation aMapNaviLocation) {}
    @Override public void onEndEmulatorNavi() {}

    @Override
    public void onArriveDestination() {
        Log.d("NavigationActivity", "onArriveDestination: 到达目的地, currentPhase变更前=" + currentPhase);
        currentPhase = NavigationPhase.IDLE;
        Log.d("NavigationActivity", "onArriveDestination: currentPhase变更后=" + currentPhase);
        naviVoiceQueue.clear();
        patrickAI.speak("已到达目的地，导航结束。你可以说退出导航返回聊天模式，或者继续规划新的路线");
    }

    @Override public void onCalculateRouteFailure(int i) {}
    @Override public void onReCalculateRouteForYaw() {}

    @Override
    public void onReCalculateRouteForTrafficJam() {
        if (currentPhase == NavigationPhase.NAVIGATION_STARTED) {
            patrickAI.speak("检测到前方拥堵，正在重新规划路线");
        }
    }
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

    private void handleNavigationVoice(String naviText) {
        Log.d("NavigationActivity", "handleNavigationVoice: naviText=" + naviText + ", currentPhase=" + currentPhase + ", isPatrickSpeaking=" + isPatrickSpeaking);
        if (naviText == null || naviText.trim().isEmpty()) return;
        long currentTime = System.currentTimeMillis();
        if (naviText.equals(lastNaviText) && (currentTime - lastNaviTextTime) < 1000) {
            return;
        }
        lastNaviText = naviText;
        lastNaviTextTime = currentTime;
        if (currentPhase != NavigationPhase.NAVIGATION_STARTED) {
            return;
        }
        if (isPatrickSpeaking) {
            naviVoiceQueue.offer(naviText);
        } else {
            playNavigationVoice(naviText);
        }
    }

    private void playNavigationVoice(String naviText) {
        Log.d("NavigationActivity", "playNavigationVoice: naviText=" + naviText + ", isNaviSpeaking=" + isNaviSpeaking);
        isNaviSpeaking = true;
        if (patrickAI != null) {
            patrickAI.speak("导航提示：" + naviText, () -> {
                isNaviSpeaking = false;
                // 导航播报完毕后自动恢复监听（由 speak 内部完成）
                // 继续播报下一个导航语音
                if (!naviVoiceQueue.isEmpty()) {
                    String nextNaviText = naviVoiceQueue.poll();
                    playNavigationVoice(nextNaviText);
                }
            });
        }
    }

    private boolean isExitNavigationCommand(String text) {
        String[] exitCommands = {
                "退出导航", "结束导航", "停止导航", "取消导航", "关闭导航",
                "退出", "返回", "回去", "不导航了", "不要导航",
                "退出导航模式", "关闭导航模式", "停止导航模式",
                "回到聊天", "回到AI模式", "回到主界面", "回聊天",
                "不想导航了", "算了", "不去了"
        };
        for (String command : exitCommands) {
            if (text.contains(command)) {
                return true;
            }
        }
        return false;
    }

    private void handleExitNavigation() {
        Log.d("NavigationActivity", "handleExitNavigation: 退出导航, currentPhase变更前=" + currentPhase);
        try {
            if (mAMapNavi != null) {
                mAMapNavi.stopNavi();
            }
        } catch (Exception e) {}
        currentPhase = NavigationPhase.IDLE;
        Log.d("NavigationActivity", "handleExitNavigation: currentPhase变更后=" + currentPhase);
        isWaitingForDestination = false;
        isAskingForDestinationChoice = false;
        pendingDestination = null;
        currentAskingIndex = 0;
        currentSearchResults.clear();
        naviVoiceQueue.clear();
        if (etSearch != null) etSearch.setText("");
        if (lvTips != null) lvTips.setVisibility(View.GONE);
        if (searchBarContainer != null) searchBarContainer.setVisibility(View.GONE);

        // 使用speak的回调来确保播报完毕再跳转
        patrickAI.speak("好的，已退出导航模式，正在返回AI聊天模式", () -> {
            // 这个回调在TTS播报完毕后执行
            try {
                if (patrickAI != null) {
                    // 销毁当前页面的引擎
                    patrickAI.destroy();
                }
            } catch (Exception ignored) {}

            Intent intent = new Intent(NavigationActivity.this, AIChatActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("from_navigation", true);
            startActivity(intent);
            finish();
        });
    }
}