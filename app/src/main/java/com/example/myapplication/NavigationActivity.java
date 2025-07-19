package com.example.myapplication;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Poi;
import com.amap.api.navi.AmapNaviPage;
import com.amap.api.navi.AmapNaviParams;
import com.amap.api.navi.AmapNaviType;

import java.util.ArrayList;
import java.util.List;

public class NavigationActivity extends AppCompatActivity {

    LatLng p1 = new LatLng(39.993266, 116.473193); // 首开广场
    LatLng p2 = new LatLng(39.917337, 116.397056); // 故宫博物院
    LatLng p3 = new LatLng(39.904556, 116.427231); // 北京站
    LatLng p4 = new LatLng(39.773801, 116.368984); // 新三余公园(南5环)
    LatLng p5 = new LatLng(40.041986, 116.414496); // 立水桥(北5环)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 你不需要 setContentView(R.layout.xxx)，因为导航页面会全屏覆盖
        startNavigation(); // 直接进入导航
    }

    private void startNavigation() {
        // 设置起点、终点、途经点
        Poi start = new Poi("立水桥(北5环)", p5, "");
        List<Poi> poiList = new ArrayList<>();
        poiList.add(new Poi("首开广场", p1, ""));
        poiList.add(new Poi("故宫博物院", p2, ""));
        poiList.add(new Poi("北京站", p3, ""));
        Poi end = new Poi("新三余公园(南5环)", p4, "");

        AmapNaviParams naviParams = new AmapNaviParams(start, poiList, end, AmapNaviType.DRIVER);
        naviParams.setUseInnerVoice(true); // 使用高德的内置语音播报
        AmapNaviPage.getInstance().showRouteActivity(getApplicationContext(), naviParams, new com.example.myapplication.MyINaviInfoCallback());
    }
}
