package com.istar.btbattery;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * 从系统读取蓝牙耳机电量。
 *
 * 数据来源：
 * 1) BluetoothDevice.getBatteryLevel()   API 30+ 公开接口
 * 2) 反射调用 getBatteryLevel()          API 26-29
 * 3) ACTION_BATTERY_LEVEL_CHANGED 广播    推送式，实时性最好
 *
 * 底层走的是 HFP 的 AT+IPHONEACCEV / AT+XAPL 通道。
 * 精度完全由耳机固件上报粒度决定，本类不做任何加工。
 */
public class BatteryReader {

    public interface Callback {
        void onLevel(BluetoothDevice device, int level, String source);
        void onUnavailable(String reason);
    }

    private final Context ctx;
    private final Callback cb;
    private BroadcastReceiver receiver;

    public BatteryReader(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (receiver != null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED.equals(action)) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int lvl = intent.getIntExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL, -1);
                    if (d != null) {
                        cb.onLevel(d, lvl, "广播推送 ACTION_BATTERY_LEVEL_CHANGED");
                    }
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) cb.onUnavailable("设备断开：" + safeName(d));
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, f);
        }
    }

    public void stop() {
        if (receiver != null) {
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
    }

    /**
     * 主动读一次电量。
     * @return 0-100；-1 表示读不到（固件不支持 / 未连接）
     */
    @SuppressLint("MissingPermission")
    public int readNow(BluetoothDevice device, String[] sourceOut) {
        if (device == null) {
            if (sourceOut != null) sourceOut[0] = "无目标设备";
            return -1;
        }

        // 1) API 30+ 公开接口
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                int lvl = device.getBatteryLevel();
                if (sourceOut != null) sourceOut[0] = "getBatteryLevel() 公开API";
                if (lvl >= 0) return lvl;
            } catch (Exception ignored) {}
        }

        // 2) 反射（API 26-29）
        try {
            Method m = device.getClass().getMethod("getBatteryLevel");
            m.setAccessible(true);
            Object r = m.invoke(device);
            if (r instanceof Integer) {
                int lvl = (Integer) r;
                if (sourceOut != null) sourceOut[0] = "getBatteryLevel() 反射";
                if (lvl >= 0) return lvl;
            }
        } catch (Exception ignored) {}

        if (sourceOut != null) sourceOut[0] = "读取失败（固件未上报或设备未连接）";
        return -1;
    }

    @SuppressLint("MissingPermission")
    public String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (Exception e) {
            return d.getAddress();
        }
    }
}
