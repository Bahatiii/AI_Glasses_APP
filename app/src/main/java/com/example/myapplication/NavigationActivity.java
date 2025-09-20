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

public class NavigationActivity extends AppCompatActivity implements AMapNaviListener, PatrickAIManager.PatrickCallback {
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

    // 新增：全局Patrick AI
    private PatrickAIManager patrickAI;
    private boolean isWaitingForDestination = false; // 等待用户确认目的地
    private String pendingDestination = null; // 待确认的目的地

    // 新增：搜索结果选择逻辑
    private boolean isAskingForDestinationChoice = false; // 正在询问搜索结果
    private int currentAskingIndex = 0; // 当前询问的索引
    private List<Tip> currentSearchResults = new ArrayList<>(); // 当前搜索结果

    // 新增：防止重复搜索的标志
    private boolean isSearchingProgrammatically = false;

    // 新增：TTS协调管理
    private boolean isPatrickSpeaking = false;
    private boolean isNaviSpeaking = false;
    private Handler ttsHandler = new Handler();
    private Queue<String> naviVoiceQueue = new LinkedList<>(); // 导航语音队列

    // **新增：导航阶段状态枚举**
    public enum NavigationPhase {
        IDLE,                    // 空闲状态
        SEARCHING_DESTINATION,   // 搜索目的地中
        ASKING_DESTINATION,      // 询问目的地确认中
        CHOOSING_FROM_RESULTS,   // 从搜索结果中选择
        ROUTE_PLANNING,          // 路线规划中
        NAVIGATION_STARTED       // 导航已开始
    }

    private NavigationPhase currentPhase = NavigationPhase.IDLE;

    // **新增：防止重复处理同一条导航语音**
    private String lastNaviText = "";
    private long lastNaviTextTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SpeechUtility.createUtility(this, "appid=9be1e7dc");
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        setContentView(R.layout.activity_navigation);

        // 初始化全局Patrick AI
        patrickAI = PatrickAIManager.getInstance(this);
        patrickAI.setCallback(this);

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
            patrickAI.patrickSpeak("已进入导航模式，请告诉我你要去哪里，或者你可以直接在搜索框输入目的地。如需退出导航，可以说退出导航");
        }, 1000);
    }

    // === Patrick AI 回调接口实现 ===
    @Override
    public void onPatrickSpeak(String text) {
        runOnUiThread(() -> {
            isPatrickSpeaking = true;

            Toast.makeText(this, "Patrick: " + text, Toast.LENGTH_SHORT).show();

            // **关键修改：如果是导航语音播报，暂停语音识别更长时间**
            boolean isNaviVoice = text.startsWith("导航提示：");
            int baseTime = isNaviVoice ? 150 : 100; // 导航语音给更多时间
            int speakDuration = Math.max(2000, text.length() * baseTime);

            Log.d("NavigationActivity", "Patrick开始说话: " + text + ", 预计时长: " + speakDuration + "ms, 是否导航语音: " + isNaviVoice);

            // Patrick说话结束后的处理
            ttsHandler.postDelayed(() -> {
                isPatrickSpeaking = false;
                Log.d("NavigationActivity", "Patrick说话结束");

                // 播放积压的导航语音
                if (!naviVoiceQueue.isEmpty()) {
                    String nextNaviText = naviVoiceQueue.poll();
                    Log.d("NavigationActivity", "Patrick说话结束，播放积压的导航语音: " + nextNaviText);
                    playNavigationVoice(nextNaviText);
                }

            }, speakDuration);
        });
    }

    @Override
    public void onUserSpeak(String text) {
        Log.d("NavigationActivity", "=== onUserSpeak 被调用 ===");
        Log.d("NavigationActivity", "接收到用户语音: " + text);
        Log.d("NavigationActivity", "当前状态 - isWaitingForDestination: " + isWaitingForDestination);
        Log.d("NavigationActivity", "当前状态 - isAskingForDestinationChoice: " + isAskingForDestinationChoice);
        Log.d("NavigationActivity", "当前状态 - pendingDestination: " + pendingDestination);
        Log.d("NavigationActivity", "当前状态 - currentAskingIndex: " + currentAskingIndex);
        Log.d("NavigationActivity", "当前阶段: " + currentPhase);

        runOnUiThread(() -> {
            // 处理用户的语音输入
            handleUserVoiceInput(text);
        });
    }

    @Override
    public void onNavigationRequest() {
        // 已经在导航模式，Patrick说明一下
        patrickAI.patrickSpeak("我们已经在导航模式了，请告诉我你要去哪里");
    }

    @Override
    public void onVideoRequest() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, VideoActivity_pi.class);
            startActivity(intent);
        });
    }

    // 处理用户语音输入的导航相关逻辑
    private void handleUserVoiceInput(String text) {
        Log.d("NavigationActivity", "=== handleUserVoiceInput 开始 ===");
        Log.d("NavigationActivity", "处理用户输入: " + text + ", 当前阶段: " + currentPhase);

        // **新增：检查退出导航命令**
        if (isExitNavigationCommand(text)) {
            Log.d("NavigationActivity", "检测到退出导航命令: " + text);
            handleExitNavigation();
            return;
        }

        if (isAskingForDestinationChoice) {
            Log.d("NavigationActivity", "当前在询问目的地选择状态");
            handleDestinationChoiceResponse(text);
        } else if (isWaitingForDestination) {
            Log.d("NavigationActivity", "当前在等待目的地确认状态");
            if (text.contains("是") || text.contains("好的") || text.contains("确定") ||
                    text.contains("可以") || text.contains("对") || text.contains("是的") ||
                    text.contains("嗯") || text.contains("行") || text.contains("OK") ||
                    text.contains("可以的") || text.contains("没错")) {
                Log.d("NavigationActivity", "用户确认目的地: " + pendingDestination);
                if (pendingDestination != null) {
                    // **设置搜索阶段**
                    currentPhase = NavigationPhase.SEARCHING_DESTINATION;

                    // 确认目的地，开始搜索（设置标志防止重复）
                    isSearchingProgrammatically = true;
                    etSearch.setText(pendingDestination);
                    isSearchingProgrammatically = false;

                    // **优化：合并语音播报，一次性说完**
                    patrickAI.patrickSpeak("好的，正在搜索" + pendingDestination);

                    // 手动调用搜索，只调用一次
                    searchByKeyword(pendingDestination);

                    isWaitingForDestination = false;
                    pendingDestination = null;
                }
            } else if (text.contains("不") || text.contains("错") || text.contains("不是") ||
                    text.contains("不对") || text.contains("不是的") || text.contains("错了")) {
                Log.d("NavigationActivity", "用户拒绝目的地，重新询问");

                // **重置为空闲状态**
                currentPhase = NavigationPhase.IDLE;

                patrickAI.patrickSpeak("好的，请重新告诉我你要去哪里");
                isWaitingForDestination = false;
                pendingDestination = null;
            } else {
                Log.d("NavigationActivity", "用户说了新的目的地，重新处理");
                processPotentialDestination(text);
            }
        } else {
            Log.d("NavigationActivity", "正常状态，检查是否为目的地输入");
            processPotentialDestination(text);
        }
    }

    // 处理搜索结果选择的回应
    private void handleDestinationChoiceResponse(String text) {
        Log.d("NavigationActivity", "=== handleDestinationChoiceResponse ===");
        Log.d("NavigationActivity", "处理选择回应: " + text + ", 阶段: " + currentPhase);
        Log.d("NavigationActivity", "当前询问索引: " + currentAskingIndex);

        if (text.contains("是") || text.contains("好的") || text.contains("确定") ||
                text.contains("可以") || text.contains("对") || text.contains("是的") ||
                text.contains("嗯") || text.contains("行") || text.contains("OK") ||
                text.contains("可以的") || text.contains("没错")) {
            Log.d("NavigationActivity", "用户确认当前选项");
            if (currentAskingIndex < currentSearchResults.size()) {
                Tip selectedTip = currentSearchResults.get(currentAskingIndex);
                Log.d("NavigationActivity", "选择目的地: " + selectedTip.getName());
                selectDestination(selectedTip);
            }
            isAskingForDestinationChoice = false;
        } else if (text.contains("不") || text.contains("错") || text.contains("不是") ||
                text.contains("下一个") || text.contains("不对") || text.contains("不是的") ||
                text.contains("错了") || text.contains("换一个")) {
            Log.d("NavigationActivity", "用户拒绝当前选项，询问下一个");
            askNextDestination();
        } else {
            Log.d("NavigationActivity", "用户输入无法识别，重新询问当前选项");
            if (currentAskingIndex < currentSearchResults.size()) {
                Tip currentTip = currentSearchResults.get(currentAskingIndex);
                patrickAI.patrickSpeak("请确认，你要去" + currentTip.getName() + "吗？");
            }
        }
    }

    // 询问下一个目的地
    private void askNextDestination() {
        currentAskingIndex++;
        if (currentAskingIndex < currentSearchResults.size()) {
            Tip nextTip = currentSearchResults.get(currentAskingIndex);
            patrickAI.patrickSpeak("那你要去" + nextTip.getName() + "吗？");
        } else {
            if (currentSearchResults.size() > 0) {
                Tip firstTip = currentSearchResults.get(0);
                patrickAI.patrickSpeak("好的，我为你自动选择第一个选项：" + firstTip.getName());
                selectDestination(firstTip);
            } else {
                // **重置为空闲状态**
                currentPhase = NavigationPhase.IDLE;
                patrickAI.patrickSpeak("没有找到合适的地点，请重新搜索");
            }
            isAskingForDestinationChoice = false;
        }
    }

    // 选择目的地并开始导航
    private void selectDestination(Tip selectedTip) {
        Log.d("NavigationActivity", "选择目的地: " + selectedTip.getName());

        // **设置路线规划阶段**
        currentPhase = NavigationPhase.ROUTE_PLANNING;

        // 设置标志，防止触发TextWatcher
        isSearchingProgrammatically = true;
        etSearch.setText(selectedTip.getName());
        isSearchingProgrammatically = false;

        lvTips.setVisibility(View.GONE);
        if (searchBarContainer != null) searchBarContainer.setVisibility(View.GONE);

        if (selectedTip.getPoint() != null) {
            mEndLatLng = new NaviLatLng(selectedTip.getPoint().getLatitude(), selectedTip.getPoint().getLongitude());

            // Patrick语音反馈
            patrickAI.patrickSpeak("好的，我为你规划到" + selectedTip.getName() + "的路线");

            Toast.makeText(this, "已选终点: " + selectedTip.getName(), Toast.LENGTH_SHORT).show();
            startWalkNavigation();
        } else {
            // **重置为空闲状态**
            currentPhase = NavigationPhase.IDLE;

            Toast.makeText(this, "该地点暂无坐标", Toast.LENGTH_SHORT).show();
            patrickAI.patrickSpeak("抱歉，该地点无法导航，请重新选择");
        }
    }

    // 处理可能的目的地输入
    private void processPotentialDestination(String text) {
        Log.d("NavigationActivity", "=== processPotentialDestination ===");
        Log.d("NavigationActivity", "处理潜在目的地: " + text);

        // 过滤明显不是地名的输入
        String[] nonLocationWords = {"你好", "再见", "谢谢", "天气", "时间", "今天", "明天", "什么", "怎么", "为什么"};
        for (String word : nonLocationWords) {
            if (text.contains(word)) {
                Log.d("NavigationActivity", "包含非地名词汇: " + word + "，忽略处理");
                return;
            }
        }

        // 检查是否包含地名相关关键词
        if (text.contains("去") || text.contains("到") || text.contains("找") ||
                text.contains("导航") || text.contains("路线") ||
                text.length() > 2) {

            Log.d("NavigationActivity", "符合地名特征，提取目的地");
            String destination = extractDestination(text);
            Log.d("NavigationActivity", "提取的目的地: " + destination);

            if (destination != null && !destination.trim().isEmpty()) {
                // **设置阶段状态**
                currentPhase = NavigationPhase.ASKING_DESTINATION;

                pendingDestination = destination;
                isWaitingForDestination = true;
                Log.d("NavigationActivity", "设置待确认目的地: " + destination + ", 阶段: " + currentPhase);
                patrickAI.patrickSpeak("你是要去" + destination + "吗？");
            }
        } else {
            Log.d("NavigationActivity", "不符合地名特征，忽略");
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
                    Log.d("NavigationActivity", "程序设置文本，跳过自动搜索");
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
                etSearch.setText(text);
                etSearch.setSelection(text.length());
                searchByKeyword(text);
            }
        }
        public void onVolumeChanged(int volume, byte[] data) {}
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {}
    };

    private void searchByKeyword(String keyword) {
        Log.d("NavigationActivity", "开始搜索关键词: " + keyword + ", 阶段: " + currentPhase);

        Inputtips inputTips = new Inputtips(this, new InputtipsQuery(keyword, ""));
        inputTips.setInputtipsListener((list, rCode) -> {
            Log.d("NavigationActivity", "搜索结果回调，rCode: " + rCode + ", 结果数量: " + (list != null ? list.size() : 0));

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
                    // **设置选择阶段**
                    currentPhase = NavigationPhase.CHOOSING_FROM_RESULTS;

                    Log.d("NavigationActivity", "找到搜索结果，准备询问，阶段: " + currentPhase);

                    // **立即开始询问，不再延迟**
                    isAskingForDestinationChoice = true;
                    currentAskingIndex = 0;

                    // **关键优化：合并所有播报，一次性说完**
                    String firstLocation = currentSearchResults.get(0).getName();
                    if (names.size() == 1) {
                        patrickAI.patrickSpeak("找到了" + firstLocation + "，确认去这里吗？");
                    } else {
                        patrickAI.patrickSpeak("找到了" + names.size() + "个地点，第一个是" + firstLocation + "，去这里吗？");
                    }

                } else {
                    // **重置为空闲状态**
                    currentPhase = NavigationPhase.IDLE;

                    Log.d("NavigationActivity", "没有找到搜索结果");
                    patrickAI.patrickSpeak("没有找到相关地点，请重新输入");
                }
            } else {
                // **重置为空闲状态**
                currentPhase = NavigationPhase.IDLE;

                Log.d("NavigationActivity", "搜索失败，rCode: " + rCode);
                lvTips.setVisibility(android.view.View.GONE);
                patrickAI.patrickSpeak("搜索出现问题，请重试");
            }
        });
        inputTips.requestInputtipsAsyn();
    }

    // **简化或删除startAskingDestinations方法**
    private void startAskingDestinations() {
        // 这个方法现在不需要了，直接在searchByKeyword中处理
        Log.d("NavigationActivity", "startAskingDestinations已被合并到searchByKeyword中");
    }

    // === 以下保持原有的导航功能不变 ===
    private void initNavi() {
        try {
            mAMapNavi = AMapNavi.getInstance(getApplicationContext());
            mAMapNavi.addAMapNaviListener(this);

            // **关键修改：禁用内置语音，改为手动捕获和播报**
            mAMapNavi.setUseInnerVoice(false); // 禁用内置语音

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
        // 最后再尝试扫描整个窗口的视图树，查找可能的"退出/返回"按钮并附加监听（通过 id 名称、文本或 contentDescription）
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
        if (!mAMapNavi.calculateWalkRoute(mStartLatLng, mEndLatLng)) {
            Toast.makeText(this, "路径规划失败", Toast.LENGTH_SHORT).show();
        } else {
            // **移除这里的语音播报，避免与onCalculateRouteSuccess重复**
            // patrickAI.patrickSpeak("开始为你导航，请注意路况安全");
            Log.d("NavigationActivity", "开始路线规划");
        }
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
    protected void onResume() {
        super.onResume();
        if (mAMapNaviView != null) mAMapNaviView.onResume();
        // 恢复Patrick AI监听
        if (patrickAI != null) {
            patrickAI.setCallback(this);
            patrickAI.resumeListening();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAMapNaviView != null) mAMapNaviView.onPause();
        if (patrickAI != null) patrickAI.pauseListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAMapNavi != null) { mAMapNavi.stopNavi(); mAMapNavi.destroy(); }
        if (mAMapNaviView != null) mAMapNaviView.onDestroy();
        if (locationClient != null) { locationClient.stopLocation(); locationClient.onDestroy(); }
        if (mIat != null) { mIat.cancel(); mIat.destroy(); }
        if (patrickAI != null) patrickAI.setCallback(null);
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
        if (mAMapNavi != null) {
            Map<Integer, AMapNaviPath> paths = mAMapNavi.getNaviPaths();
            if (paths != null && paths.size() > 0) {
                drawRoute(paths);

                // **设置导航开始阶段 - 现在可以播放导航语音了**
                currentPhase = NavigationPhase.NAVIGATION_STARTED;

                Log.d("NavigationActivity", "路线规划成功，进入导航阶段: " + currentPhase);

                // **只在这里播报一次导航开始，与高德的导航语音区分开**
                patrickAI.patrickSpeak("路线规划成功，导航开始");
            } else {
                // **重置为空闲状态**
                currentPhase = NavigationPhase.IDLE;
                Toast.makeText(this, "未获取到路径", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onCalculateRouteFailure(AMapCalcRouteResult result) {
        // **重置为空闲状态**
        currentPhase = NavigationPhase.IDLE;

        Toast.makeText(this, "路径规划失败：" + result.getErrorCode(), Toast.LENGTH_LONG).show();
        patrickAI.patrickSpeak("路径规划失败，请重新选择目的地");
    }

    @Override public void onNaviRouteNotify(AMapNaviRouteNotifyData aMapNaviRouteNotifyData) {}
    @Override public void notifyParallelRoad(int i) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo[] aMapNaviTrafficFacilityInfos) {}
    @Override public void OnUpdateTrafficFacility(AMapNaviTrafficFacilityInfo aMapNaviTrafficFacilityInfo) {}
    @Override public void updateAimlessModeStatistics(AimLessModeStat aimLessModeStat) {}
    @Override public void updateAimlessModeCongestionInfo(AimLessModeCongestionInfo aimLessModeCongestionInfo) {}
    @Override public void onPlayRing(int i) {}

    // **实现导航语音捕获 - 重载方法1**
    @Override
    public void onGetNavigationText(int type, String naviText) {
        Log.d("NavigationActivity", "导航语音 (type=" + type + "): " + naviText);
        handleNavigationVoice(naviText);
    }

    // **实现导航语音捕获 - 重载方法2**
    @Override
    public void onGetNavigationText(String naviText) {
        Log.d("NavigationActivity", "导航语音: " + naviText);
        handleNavigationVoice(naviText);
    }

    @Override
    public void onGpsSignalWeak(boolean isWeak) {
        // **添加阶段控制：只有在导航阶段才提示GPS信号问题**
        if (isWeak && currentPhase == NavigationPhase.NAVIGATION_STARTED) {
            Log.d("NavigationActivity", "GPS信号较弱，当前阶段: " + currentPhase);
            patrickAI.patrickSpeak("GPS信号较弱，请注意路况");
        } else if (isWeak) {
            Log.d("NavigationActivity", "GPS信号较弱，但当前阶段不是导航中，忽略提示: " + currentPhase);
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
        // **重置为空闲状态**
        currentPhase = NavigationPhase.IDLE;

        // 清空导航语音队列
        naviVoiceQueue.clear();
        patrickAI.patrickSpeak("已到达目的地，导航结束。你可以说退出导航返回聊天模式，或者继续规划新的路线");
    }

    @Override public void onCalculateRouteFailure(int i) {}
    @Override public void onReCalculateRouteForYaw() {}

    @Override
    public void onReCalculateRouteForTrafficJam() {
        // 只有在导航阶段才提示重新规划
        if (currentPhase == NavigationPhase.NAVIGATION_STARTED) {
            Log.d("NavigationActivity", "检测到拥堵，重新规划路线，当前阶段: " + currentPhase);
            patrickAI.patrickSpeak("检测到前方拥堵，正在重新规划路线");
        } else {
            Log.d("NavigationActivity", "检测到拥堵，但当前阶段不是导航中，忽略: " + currentPhase);
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

    // **处理导航语音播报 - 增加阶段控制和去重**
    private void handleNavigationVoice(String naviText) {
        if (naviText == null || naviText.trim().isEmpty()) return;

        // **新增：去重机制 - 防止同一条语音被重复处理**
        long currentTime = System.currentTimeMillis();
        if (naviText.equals(lastNaviText) && (currentTime - lastNaviTextTime) < 1000) {
            Log.d("NavigationActivity", "重复的导航语音，忽略: " + naviText);
            return;
        }
        lastNaviText = naviText;
        lastNaviTextTime = currentTime;

        Log.d("NavigationActivity", "处理导航语音: " + naviText + ", 当前阶段: " + currentPhase);

        // **关键修改：只有在导航已开始阶段才播放导航语音**
        if (currentPhase != NavigationPhase.NAVIGATION_STARTED) {
            Log.d("NavigationActivity", "当前阶段不允许导航语音播报，忽略: " + naviText);
            return;
        }

        if (isPatrickSpeaking) {
            // Patrick正在说话，将导航语音加入队列
            Log.d("NavigationActivity", "Patrick正在说话，导航语音排队: " + naviText);
            naviVoiceQueue.offer(naviText);
        } else {
            // 立即播放导航语音
            playNavigationVoice(naviText);
        }
    }

    // **修改播放导航语音方法，增加更强的识别暂停**
    private void playNavigationVoice(String naviText) {
        isNaviSpeaking = true;

        // **强制暂停Patrick AI监听，避免把导航语音识别为用户输入**
        if (patrickAI != null) {
            Log.d("NavigationActivity", "播放导航语音前暂停Patrick监听");
            patrickAI.pauseListening();
        }

        Log.d("NavigationActivity", "播放导航语音: " + naviText);

        // 使用Patrick的TTS播放导航指令，但用特殊的语调/前缀标识
        patrickAI.patrickSpeak("导航提示：" + naviText);

        // **增加播放时长，确保完全播放完毕**
        int speakDuration = Math.max(4000, naviText.length() * 150); // 增加到150ms/字符

        // 导航语音结束后的处理
        ttsHandler.postDelayed(() -> {
            isNaviSpeaking = false;
            Log.d("NavigationActivity", "导航语音播放结束，恢复Patrick监听");

            // **延迟恢复Patrick监听，确保TTS完全结束**
            ttsHandler.postDelayed(() -> {
                if (patrickAI != null) {
                    patrickAI.resumeListening();
                    Log.d("NavigationActivity", "Patrick监听已恢复");
                }
            }, 800); // 额外等800ms

            // 播放队列中的下一个导航语音
            if (!naviVoiceQueue.isEmpty()) {
                String nextNaviText = naviVoiceQueue.poll();
                Log.d("NavigationActivity", "播放队列中的导航语音: " + nextNaviText);
                playNavigationVoice(nextNaviText);
            }

        }, speakDuration);
    }

    // **新增：检查是否为退出导航命令**
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
                Log.d("NavigationActivity", "匹配到退出命令: " + command);
                return true;
            }
        }
        return false;
    }

    // **新增：处理退出导航**
    private void handleExitNavigation() {
        Log.d("NavigationActivity", "处理退出导航请求，当前阶段: " + currentPhase);

        // 停止所有导航相关功能
        try {
            if (mAMapNavi != null) {
                mAMapNavi.stopNavi();
                Log.d("NavigationActivity", "已停止导航");
            }
        } catch (Exception e) {
            Log.e("NavigationActivity", "停止导航失败: " + e.getMessage());
        }

        // 清理状态
        currentPhase = NavigationPhase.IDLE;
        isWaitingForDestination = false;
        isAskingForDestinationChoice = false;
        pendingDestination = null;
        currentAskingIndex = 0;
        currentSearchResults.clear();
        naviVoiceQueue.clear();

        // 清理UI
        if (etSearch != null) etSearch.setText("");
        if (lvTips != null) lvTips.setVisibility(View.GONE);
        if (searchBarContainer != null) searchBarContainer.setVisibility(View.GONE);

        // Patrick语音确认
        patrickAI.patrickSpeak("好的，已退出导航模式，正在返回AI聊天模式");

        // 延迟1.5秒后返回AI聊天模式
        new Handler().postDelayed(() -> {
            Log.d("NavigationActivity", "返回AI聊天模式");
            Intent intent = new Intent(NavigationActivity.this, AIChatActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // **关键修改：添加标志表示从导航返回**
            intent.putExtra("from_navigation", true);
            startActivity(intent);
            finish(); // 关闭当前导航Activity
        }, 1500);
    }
}