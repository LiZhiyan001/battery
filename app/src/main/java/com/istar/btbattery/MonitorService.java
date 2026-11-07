package com.istar.btbattery;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 前台服务：BLE GATT 直读 0x2A19 耳机电量，写 CSV，更新常驻通知。
 *
 * 双通道：
 *  - 主：BLE GATT（0x180F/0x2A19），Read + Notify，精度取决于固件
 *  - 备：系统 HFP getBatteryLevel()，仅当 BLE 连不上时使用
 *
 * 通知订阅模式：耳机电量变化会主动推送，不需要定时轮询。
 * 仍保留低频兜底轮询（默认 60s），防止通知丢失。
 */
public class MonitorService extends Service {

    public static final String TAG = "BTBattery";

    public static final String ACTION_START = "com.istar.btbattery.START";
    public static final String ACTION_STOP = "com.istar.btbattery.STOP";
    public static final String ACTION_TICK = "com.istar.btbattery.TICK";
    public static final String EXTRA_TAG = "tag";
    public static final String EXTRA_INTERVAL = "interval";
    public static final String EXTRA_MAC = "mac";
    public static final String EXTRA_PRIVATE_UUID = "private_uuid";

    private static final String CH_ID = "bt_battery_monitor";
    private static final int NOTIF_ID = 1001;
    private static final String PREFS = "bt_battery_prefs";

    public static final BatteryLog LOG = new BatteryLog();

    /** Activity 可读的实时状态 */
    public static volatile int sLastLevel = -1;
    public static volatile String sLastSource = "—";
    public static volatile String sConnState = "未启动";
    public static volatile int sBleReads = 0;
    public static volatile boolean sRunning = false;

    private Handler handler;
    private BluetoothAdapter adapter;
    private BleBatteryReader ble;
    private BatteryReader hfpReader;
    private SharedPreferences prefs;

    private long intervalMs = 60_000L;
    private String currentTag = "待机";
    private boolean running = false;
    private File csvFile = null;
    private String targetMac = "";
    private String privateUuid = "";
    private long lastSampleAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        adapter = BluetoothAdapter.getDefaultAdapter();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createChannel();
        targetMac = prefs.getString("mac", "");
        privateUuid = prefs.getString("private_uuid", "");

        ble = new BleBatteryReader(this, new BleBatteryReader.Listener() {
            @Override
            public void onLevel(int level, String source) {
                sLastLevel = level;
                sLastSource = source;
                sBleReads = ble.readCount();
                // 推送值立刻入账（这是最有价值的实时记录）
                recordSample(level, source, currentTag + "(推送)");
                updateNotification();
            }

            @Override
            public void onState(String state) {
                sConnState = state;
                updateNotification();
                notifyActivity();
            }

            @Override
            public void onError(String msg) {
                sConnState = "错误：" + msg;
                // BLE 失败时，用 HFP 兜底读一次，避免完全空白
                fallbackToHfp();
                notifyActivity();
            }
        });
        if (!privateUuid.isEmpty()) ble.setPrivateChrUuid(privateUuid);
    }

    /** BLE 不通时的兜底：走系统 HFP 电量（精度低，但至少有值） */
    @SuppressLint("MissingPermission")
    private void fallbackToHfp() {
        try {
            if (hfpReader == null) {
                hfpReader = new BatteryReader(this, new BatteryReader.Callback() {
                    @Override
                    public void onLevel(BluetoothDevice device, int level, String source) {
                        if (level >= 0) {
                            sLastLevel = level;
                            sLastSource = "HFP兜底";
                            recordSample(level, "HFP兜底", currentTag + "(HFP)");
                        }
                    }
                    @Override
                    public void onUnavailable(String reason) {}
                });
                hfpReader.start();
            }
            if (adapter != null && adapter.isEnabled()) {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    String[] src = new String[1];
                    int lvl = hfpReader.readNow(d, src);
                    if (lvl >= 0) {
                        sLastLevel = lvl;
                        sLastSource = "HFP兜底";
                        recordSample(lvl, "HFP兜底", currentTag + "(HFP)");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "HFP 兜底失败", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService 要求 5 秒内必须 startForeground
        startForeground(NOTIF_ID, buildNotification("启动中…"));

        if (intent != null) {
            if (ACTION_STOP.equals(intent.getAction())) {
                stopMonitor();
                return START_NOT_STICKY;
            }
            if (intent.hasExtra(EXTRA_INTERVAL)) {
                intervalMs = Math.max(5_000L, intent.getLongExtra(EXTRA_INTERVAL, 60_000L));
            }
            if (intent.hasExtra(EXTRA_TAG)) {
                currentTag = intent.getStringExtra(EXTRA_TAG);
            }
            if (intent.hasExtra(EXTRA_MAC)) {
                String m = intent.getStringExtra(EXTRA_MAC);
                if (m != null && !m.isEmpty()) {
                    targetMac = m;
                    prefs.edit().putString("mac", m).apply();
                }
            }
            if (intent.hasExtra(EXTRA_PRIVATE_UUID)) {
                String u = intent.getStringExtra(EXTRA_PRIVATE_UUID);
                if (u != null && !u.isEmpty()) {
                    privateUuid = u;
                    prefs.edit().putString("private_uuid", u).apply();
                    ble.setPrivateChrUuid(u);
                }
            }
        }

        if (!running) {
            running = true;
            sRunning = true;
            startCsvFile();
            startBle();
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, intervalMs);
        } else {
            updateNotification();
        }
        return START_STICKY;
    }

    private void startBle() {
        sConnState = "扫描中…";
        ble.scanAndConnect(targetMac, 20_000L);
        updateNotification();
    }

    /** 低频兜底：BLE 通知可能丢，定时主动读一次 */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            ble.readOnce();
            // 同时记一笔，即使值没变也留下时间戳，便于算掉电速率
            if (sLastLevel >= 0) {
                recordSample(sLastLevel, sLastSource, currentTag + "(轮询)");
            }
            notifyActivity();
            handler.postDelayed(this, intervalMs);
        }
    };

    private void recordSample(int level, String source, String tag) {
        long now = System.currentTimeMillis();
        // 同值同秒去重，避免推送+轮询重复记
        if (now - lastSampleAt < 1500 && level == sLastLevel
                && LOG.size() > 0) {
            return;
        }
        lastSampleAt = now;
        LOG.add(new BatteryLog.Sample(now, level, level >= 0 ? 1 : 0, tag));
        appendCsv(level, source, tag);
    }

    private void stopMonitor() {
        running = false;
        sRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (ble != null) ble.disconnect();
        stopForeground(true);
        stopSelf();
    }

    // ---------- CSV ----------

    private void startCsvFile() {
        try {
            File dir = BatteryLog.logDir(this);
            String name = "battery_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date())
                    + ".csv";
            csvFile = new File(dir, name);
            FileWriter w = new FileWriter(csvFile, false);
            w.write("# 蓝牙耳机电量记录（BLE GATT 0x2A19）\r\n");
            w.write("# 设备MAC," + targetMac + "\r\n");
            w.write("# 开始时间," + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date()) + "\r\n");
            w.write("# 兜底轮询间隔(ms)," + intervalMs + "\r\n");
            w.write("时间,时间戳(ms),电量,数据来源,场景\r\n");
            w.close();
            Log.i(TAG, "CSV -> " + csvFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "create csv fail", e);
            csvFile = null;
        }
    }

    private void appendCsv(int level, String src, String tag) {
        if (csvFile == null) return;
        try {
            FileWriter w = new FileWriter(csvFile, true);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);
            w.write(f.format(new Date()) + "," + System.currentTimeMillis() + ","
                    + (level < 0 ? "" : String.valueOf(level)) + ","
                    + (src == null ? "" : src.replace(",", ";")) + ","
                    + (tag == null ? "" : tag.replace(",", ";")) + "\r\n");
            w.close();
        } catch (Exception e) {
            Log.e(TAG, "append csv fail", e);
        }
    }

    public File csvFileRef() {
        return csvFile;
    }

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_desc));
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @SuppressLint("MissingPermission")
    private Notification buildNotification(String title) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopI = new Intent(this, MonitorService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String big = "连接：" + sConnState
                + "\n当前电量：" + (sLastLevel >= 0 ? sLastLevel + "%" : "—")
                + "\n来源：" + sLastSource
                + "\n场景：" + currentTag
                + "\n采样数：" + LOG.size()
                + "\nBLE 读数：" + sBleReads;

        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle(title)
                .setContentText(sLastLevel >= 0
                        ? sLastLevel + "%  已采 " + LOG.size() + " 点"
                        : sConnState)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(big))
                .setSmallIcon(android.R.drawable.ic_menu_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            String title = sLastLevel >= 0
                    ? "耳机电量 " + sLastLevel + "%"
                    : "耳机电量 连接中…";
            nm.notify(NOTIF_ID, buildNotification(title));
        } catch (Exception ignored) {}
    }

    private void notifyActivity() {
        Intent i = new Intent(ACTION_TICK);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        running = false;
        sRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (ble != null) ble.disconnect();
        if (hfpReader != null) hfpReader.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
