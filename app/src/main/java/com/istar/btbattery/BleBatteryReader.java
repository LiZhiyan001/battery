package com.istar.btbattery;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE GATT 电量读取器。
 *
 * 目标服务：0x180F Battery Service
 * 目标特征：0x2A19 Battery Level（Read + Notify，uint8 0-100）
 *
 * 同时兼容私有电量特征值（13bb0001-5884-4c5d-b75b-8768de...），
 * 因为实测该设备上两类值都暴露了，私有值的原始度更高。
 *
 * 工作流程：
 *   1. 扫描（按 MAC 或按服务 UUID 过滤）找到设备
 *   2. connectGatt → 发现服务
 *   3. 读取 0x2A19 一次，同时开启 CCCD 订阅通知
 *   4. 后续电量变化由 GATT 主动回调，不需要轮询
 *   5. 断连自动重连
 */
public class BleBatteryReader {

    private static final String TAG = "BleBattery";

    public static final UUID SVC_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 实测出现在该设备上的私有电量特征值（13bb0001-5884-4c5d-b75b-8768de... 完整 UUID 从扫描时补全） */
    private UUID privateChrUuid = null;

    public interface Listener {
        /** 读到电量。source 说明来源通道 */
        void onLevel(int level, String source);
        /** 连接状态变化 */
        void onState(String state);
        /** 读不到/出错 */
        void onError(String msg);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private volatile boolean wantConnect = false;
    private volatile boolean discovering = false;
    private int readCount = 0;

    public BleBatteryReader(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
    }

    /** 记录从 nRF 看到的私有特征 UUID，用于额外读取 */
    public void setPrivateChrUuid(String uuid) {
        try { this.privateChrUuid = UUID.fromString(uuid); } catch (Exception ignored) {}
    }

    // ---------- 扫描 ----------

    @SuppressLint("MissingPermission")
    public void scanAndConnect(String macAddress, long timeoutMs) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) { emitError("蓝牙服务不可用"); return; }
        android.bluetooth.BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { emitError("蓝牙未开启"); return; }
        if (adapter.getBluetoothLeScanner() == null) { emitError("BLE 扫描不可用"); return; }

        wantConnect = true;

        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                String addr = d.getAddress();
                if (macAddress != null && !macAddress.isEmpty()
                        && !addr.equalsIgnoreCase(macAddress)) {
                    return;
                }
                // 命中目标，停止扫描并连接
                try {
                    adapter.getBluetoothLeScanner().stopScan(this);
                } catch (Exception ignored) {}
                emitState("找到设备 " + safeName(d) + "，连接中…");
                connect(d);
            }

            @Override
            public void onScanFailed(int errorCode) {
                emitError("扫描失败 code=" + errorCode);
            }
        };

        List<ScanFilter> filters = new ArrayList<>();
        if (macAddress != null && !macAddress.isEmpty()) {
            filters.add(new ScanFilter.Builder()
                    .setDeviceAddress(macAddress).build());
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            adapter.getBluetoothLeScanner().startScan(filters, settings, cb);
            emitState("扫描中…");
        } catch (Exception e) {
            emitError("启动扫描失败：" + e.getMessage());
            return;
        }

        main.postDelayed(() -> {
            try { adapter.getBluetoothLeScanner().stopScan(cb); } catch (Exception ignored) {}
            if (gatt == null) emitError("扫不到该设备（确认已开启/在范围内）");
        }, timeoutMs);
    }

    // ---------- 连接 ----------

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice d) {
        device = d;
        closeGatt();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = d.connectGatt(ctx, false, gattCb);
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitState("已连接，发现服务…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitState("已断开");
                closeGatt();
                if (wantConnect) {
                    main.postDelayed(() -> {
                        if (wantConnect && device != null) connect(device);
                    }, 3000);
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitError("服务发现失败 status=" + status);
                return;
            }
            discovering = false;

            BluetoothGattService bs = g.getService(SVC_BATTERY);
            if (bs == null) {
                emitError("该设备没有 0x180F 电池服务");
                return;
            }

            BluetoothGattCharacteristic ch = bs.getCharacteristic(CHR_BATTERY_LEVEL);
            if (ch == null) {
                emitError("0x180F 里没有 0x2A19 特征值");
                return;
            }

            // 开启通知（CCCD）—— 这样耳机电量变化会主动推，不用轮询
            boolean notifOk = g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                }
            }

            emitState("已订阅电量通知" + (notifOk ? "" : "（订阅可能失败）"));

            // 先读一次当前值
            g.readCharacteristic(ch);

            // 同时尝试私有特征
            if (privateChrUuid != null) {
                BluetoothGattCharacteristic pch = findChar(g, privateChrUuid);
                if (pch != null) {
                    g.setCharacteristicNotification(pch, true);
                    BluetoothGattDescriptor pd = pch.getDescriptor(CCCD);
                    if (pd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(pd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        } else {
                            pd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(pd);
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            handleChar(c);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         byte[] value, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            handleRaw(c.getUuid(), value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            handleChar(c);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,
                                            byte[] value) {
            handleRaw(c.getUuid(), value);
        }
    };

    private void handleChar(BluetoothGattCharacteristic c) {
        byte[] v = c.getValue();
        if (v == null) return;
        handleRaw(c.getUuid(), v);
    }

    /** 解析原始字节 */
    private void handleRaw(UUID uuid, byte[] v) {
        if (v == null || v.length == 0) return;

        if (CHR_BATTERY_LEVEL.equals(uuid)) {
            int level = v[0] & 0xFF;           // uint8
            if (level >= 0 && level <= 100) {
                readCount++;
                emitLevel(level, "GATT 0x2A19");
            } else {
                emitError("0x2A19 值越界：" + level);
            }
        } else if (privateChrUuid != null && privateChrUuid.equals(uuid)) {
            // 私有特征：实测 2 字节小端，如 3D 00 = 61
            int val;
            if (v.length >= 2) {
                val = (v[0] & 0xFF) | ((v[1] & 0xFF) << 8);
            } else {
                val = v[0] & 0xFF;
            }
            if (val >= 0 && val <= 100) {
                emitLevel(val, "私有特征(2B LE)");
            }
        }
    }

    private BluetoothGattCharacteristic findChar(BluetoothGatt g, UUID uuid) {
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(uuid);
            if (c != null) return c;
        }
        return null;
    }

    // ---------- 主动读 ----------

    @SuppressLint("MissingPermission")
    public void readOnce() {
        if (gatt == null || device == null) return;
        BluetoothGattService bs = gatt.getService(SVC_BATTERY);
        if (bs == null) return;
        BluetoothGattCharacteristic ch = bs.getCharacteristic(CHR_BATTERY_LEVEL);
        if (ch != null) gatt.readCharacteristic(ch);
    }

    public boolean isConnected() {
        return gatt != null;
    }

    public int readCount() {
        return readCount;
    }

    // ---------- 释放 ----------

    @SuppressLint("MissingPermission")
    public void disconnect() {
        wantConnect = false;
        closeGatt();
        emitState("已主动断开");
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {}
            gatt = null;
        }
    }

    private void emitLevel(int level, String src) {
        main.post(() -> {
            if (listener != null) listener.onLevel(level, src);
        });
    }

    private void emitState(String s) {
        Log.i(TAG, s);
        main.post(() -> {
            if (listener != null) listener.onState(s);
        });
    }

    private void emitError(String s) {
        Log.w(TAG, s);
        main.post(() -> {
            if (listener != null) listener.onError(s);
        });
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (Exception e) {
            return d.getAddress();
        }
    }
}
