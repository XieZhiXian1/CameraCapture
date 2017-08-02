package com.ymlion.cameracapture;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

/**
 * Created by YMlion on 2017/8/2.
 */
public class LocationUtil {

    private static Geocoder mGeocoder;
    public static String mLocation;
    public static double mLatitude;
    public static double mLongitude;
    public static double mAltitude;

    /**
     * 通过地理坐标获取城市名	其中CN分别是city和name的首字母缩写
     */
    public static void getLocation(Context context) {

        mGeocoder = new Geocoder(context);
        //用于获取Location对象，以及其他
        LocationManager locationManager;
        String serviceName = Context.LOCATION_SERVICE;
        //实例化一个LocationManager对象
        locationManager = (LocationManager) context.getSystemService(serviceName);
        //provider的类型
        String provider;
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else {
            Log.e("Location", "getLocation: no provider");
            return;
        }
        Log.d("Location", "getLocation: " + provider);

        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);    //高精度
        criteria.setAltitudeRequired(true);    //要求海拔
        criteria.setBearingRequired(false);    //不要求方位
        criteria.setCostAllowed(false);    //不允许有话费
        criteria.setPowerRequirement(Criteria.POWER_LOW);    //低功耗

        //通过最后一次的地理位置来获得Location对象
        if (ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Location location = locationManager.getLastKnownLocation(provider);

        String locationName = updateWithNewLocation(location);
        if ((locationName != null) && (0 != locationName.length())) {
            mLocation = locationName;
        }

        if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
            return;
        }

		/*
         * 第二个参数表示更新的周期，单位为毫秒；第三个参数的含义表示最小距离间隔，单位是米
		 * 设定每30秒进行一次自动定位
		 */
        locationManager.requestLocationUpdates(provider, 0, 0,
                locationListener);
        locationManager.removeUpdates(locationListener);
    }

    /**
     * 方位改变时触发，进行调用
     */
    private final static LocationListener locationListener = new LocationListener() {
        String tempCityName;

        public void onLocationChanged(Location location) {
            tempCityName = updateWithNewLocation(location);
            if ((tempCityName != null) && (tempCityName.length() != 0)) {
                mLocation = tempCityName;
            }
        }

        public void onProviderDisabled(String provider) {
            tempCityName = updateWithNewLocation(null);
            if ((tempCityName != null) && (tempCityName.length() != 0)) {

                mLocation = tempCityName;
            }
        }

        public void onProviderEnabled(String provider) {
            Log.d("Location", "onProviderEnabled: ");
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d("Location", "onStatusChanged: " + status);
        }
    };

    /**
     * 更新location
     *
     * @return mLocation
     */
    private static String updateWithNewLocation(Location location) {
        String tmpLocation = "";
        List<Address> addList = null;
        if (location != null) {
            mLatitude = location.getLatitude();
            mLongitude = location.getLongitude();
            mAltitude = location.getAltitude();
        } else {
            System.out.println("无法获取地理信息");
            return tmpLocation;
        }

        try {
            addList = mGeocoder.getFromLocation(mLatitude, mLongitude, 1);    //解析经纬度
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (addList != null && addList.size() > 0) {
            for (int i = 0; i < addList.size(); i++) {
                Address add = addList.get(i);
                Log.d("Location", "updateWithNewLocation: " + add);
                for (int j = 0; j <= add.getMaxAddressLineIndex(); j++) {
                    String line = add.getAddressLine(j);
                    tmpLocation += line;
                }
            }
        }
        Log.e("Location", "updateWithNewLocation: " + tmpLocation);
        if (tmpLocation.length() != 0) {
            return tmpLocation.substring(0, (tmpLocation.length() - 1));
        } else {
            return tmpLocation;
        }
    }

    /**
     * 通过经纬度获取地址信息的另一种方法
     *
     * @return 城市名
     */
    public static String GetAddr(String latitude, String longitude) {
        String addr = "";

		/*
         * 也可以是http://maps.google.cn/maps/geo?output=csv&key=abcdef&q=%s,%s，不过解析出来的是英文地址
		 * 密钥可以随便写一个key=abc
		 * output=csv,也可以是xml或json，不过使用csv返回的数据最简洁方便解析
		 */
        String url = String.format(
                "http://ditu.google.cn/maps/geo?output=csv&key=abcdef&q=%s,%s",
                latitude, longitude);
        URL myURL = null;
        URLConnection httpsConn = null;
        try {

            myURL = new URL(url);
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return null;
        }

        try {

            httpsConn = (URLConnection) myURL.openConnection();

            if (httpsConn != null) {
                InputStreamReader insr = new InputStreamReader(
                        httpsConn.getInputStream(), "UTF-8");
                BufferedReader br = new BufferedReader(insr);
                String data = null;
                if ((data = br.readLine()) != null) {
                    String[] retList = data.split(",");
                    if (retList.length > 2 && ("200".equals(retList[0]))) {
                        addr = retList[2];
                    } else {
                        addr = "";
                    }
                }
                insr.close();
            }
        } catch (IOException e) {

            e.printStackTrace();
            return null;
        }
        return addr;
    }

}
