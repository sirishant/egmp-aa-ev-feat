package com.hkmc.cluster;

import android.content.Context;

/** Stub for javac only — never dexed. Real class is in the car's automotive.jar. */
public class NavigationCluster {
    public NavigationCluster(Context context) {}

    public void addListener(NavigationClusterListener listener) {}

    public void removeListener(NavigationClusterListener listener) {}

    public void requestBatteryInfoReq() {}

    public void updateTBTTrapInfo(int fixedSpeed, int mobileSpeed, int overSpeedAlarm, int redlineSpeed,
            int nonSpeed, int warningZone, int curveWarning, int mergeWarning, int tollGateCurrency,
            int tollGate, int displayTG, int barGraphLevel, int speedLimit, int busSpeed,
            int speedLimitType, int speedLimitUnit, int speedInfoType) {
    }

    public void updateTBTInfo(int displayType, int sideStreet, float direction, float distance,
            int distanceErr, int combiSideStreet, int scale, int barGraphLevel,
            int barGraphLevelErr) {}

    public void updateTBTSubInfo(float distance, int distanceErr, int unit, int hours, int minutes,
            int timeType, int timeFormat, float compassDegree, int compassDegreeErr,
            int estPointType) {}
}
