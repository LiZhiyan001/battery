package com.istar.btbattery;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 100;
    private static final String PREFS = "bt_battery_prefs";

    private TextView tvDevice, tvLevel, tvSource, tvCount, tvDuration, tvRate,
            tvResolution, tvInterval, tvLogPath, tvConn, tvBleReads;
    private ChartView chart;
    private MaterialButton btnStart, btnExport, btnClear, btnScan, btnPrivate;
    private MaterialButtonToggleGroup toggleTag;
    private SeekBar seekInterval;

    private final long[] INTERVALS = {5_000, 10_000, 15_000, 30_000, 60_000,
            120_000, 180_000, 300_000, 600_000, 900_000, 1_200_000, 1_800_000};
    private final String[] INTERVAL_LABEL = {"5 秒", "10 秒", "15 秒", "30 秒", "60 秒",
            "2 分", "3 分", "5 分", "10 分", "15 分", "20 分", "30 分"};

    private long intervalMs = 60_000;
    private String currentTag = "待机";
    private boolean running = false;
    private long runStart = 0;

    private String selectedMac = "";
    private String selectedName = "";
    private String privateUuid = "";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BroadcastReceiver tickReceiver;
    private SharedPreferences prefs;

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private final Map<String, String> scanResults = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedMac = prefs.getString("mac", "");
        privateUuid = prefs.getString("private_uuid", "");

        bindViews();
        setupListeners();
        checkPermissions();

        tickReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshUi();
            }
        };
        IntentFilter f = new IntentFilter(MonitorService.ACTION_TICK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tickReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(tickReceiver, f);
        }

        refreshUi();
        ui.postDelayed(refreshRunnable, 1000);
    }

    private void bindViews() {
        tvDevice = findViewById(R.id.tvDevice);
        tvLevel = findViewById(R.id.tvLevel);
        tvSource = findViewById(R.id.tvSource);
        tvCount = findViewById(R.id.tvCount);
        tvDuration = findViewById(R.id.tvDuration);
        tvRate = findViewById(R.id.tvRate);
        tvResolution = findViewById(R.id.tvResolution);
        tvInterval = findViewById(R.id.tvInterval);
        tvLogPath = findViewById(R.id.tvLogPath);
        tvConn = findViewById(R.id.tvConn);
        tvBleReads = findViewById(R.id.tvBleReads);
        chart = findViewById(R.id.chart);
        btnStart = findViewById(R.id.btnStart);
        btnExport = findViewById(R.id.btnExport);
        btnClear = findViewById(R.id.btnClear);
        btnScan = findViewById(R.id.btnScan);
        btnPrivate = findViewById(R.id.btnPrivate);
        toggleTag = findViewById(R.id.toggleTag);
        seekInterval = findViewById(R.id.seekInterval);

        toggleTag.check(R.id.btnIdle);
        seekInterval.setProgress(4);
        tvInterval.setText(INTERVAL_LABEL[4]);
    }

    private void setupListeners() {
        btnStart.setOnClickListener(v -> {
            if (running) stopMonitor(); else startMonitor();
        });

        btnExport.setOnClickListener(v -> exportCsv());

        btnClear.setOnClickListener(v -> {
            MonitorService.LOG.clear();
            refreshUi();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });

        btnScan.setOnClickListener(v -> showScanDialog());

        btnPrivate.setOnClickListener(v -> showPrivateUuidDialog());

        toggleTag.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnIdle) currentTag = "待机";
            else if (checkedId == R.id.btnMusic) currentTag = "放歌";
            else if (checkedId == R.id.btnCall) currentTag = "通话";
            if (running) {
                Intent i = new Intent(this, MonitorService.class);
                i.setAction(MonitorService.ACTION_START);
                i.putExtra(MonitorService.EXTRA_TAG, currentTag);
                i.putExtra(MonitorService.EXTRA_MAC, selectedMac);
                startService(i);
                Toast.makeText(this, "场景切换为：" + currentTag, Toast.LENGTH_SHORT).show();
            }
        });

        seekInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                intervalMs = INTERVALS[p];
                tvInterval.setText(INTERVAL_LABEL[p]);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ---------- 扫描选设备 ----------

    @SuppressLint("MissingPermission")
    private void showScanDialog() {
        if (!hasBlePermission()) {
            checkPermissions();
            Toast.makeText(this, "请先授予蓝牙权限", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) {
            Toast.makeText(this, "BLE 扫描不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        scanResults.clear();
        final AlertDialog[] dlgRef = new AlertDialog[1];

        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult result) {
                String addr = result.getDevice().getAddress();
                String name;
                try { name = result.getDevice().getName(); } catch (Exception e) { name = null; }
                if (name == null || name.isEmpty()) name = "(无名称)";
                int rssi = result.getRssi();
                scanResults.put(addr, name + "   " + rssi + " dBm");
                if (dlgRef[0] != null && dlgRef[0].isShowing()) {
                    // 简单刷新：重建列表
                    // 用不到复杂 adapter，直接靠用户点确定前停止
                }
            }
            @Override
            public void onScanFailed(int code) {
                Toast.makeText(MainActivity.this, "扫描失败 code=" + code, Toast.LENGTH_SHORT).show();
            }
        };

        ScanSettings s = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, s, scanCb);

        ui.postDelayed(() -> {
            try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
            if (dlgRef[0] != null && dlgRef[0].isShowing()) dlgRef[0].dismiss();
            showDeviceListDialog();
        }, 8000);

        new AlertDialog.Builder(this)
                .setTitle("扫描中… 8 秒")
                .setMessage("请让耳机进入配对模式，并放在手机附近。\n扫到后会弹出设备列表。")
                .setNegativeButton("取消", (d, w) -> {
                    try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
                })
                .setOnDismissListener(d -> {})
                .show();
    }

    private void showDeviceListDialog() {
        if (scanResults.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("没扫到设备")
                    .setMessage("可能原因：\n"
                            + "1. 耳机已被手机系统蓝牙连接（BLE 被占用）\n"
                            + "2. 耳机没进配对模式\n"
                            + "3. 距离太远\n\n"
                            + "建议：先在系统蓝牙里断开耳机，再重试。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        List<String> labels = new ArrayList<>();
        List<String> macs = new ArrayList<>();
        for (Map.Entry<String, String> e : scanResults.entrySet()) {
            labels.add(e.getValue() + "\n" + e.getKey());
            macs.add(e.getKey());
        }
        String[] items = labels.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("选择目标设备（" + items.length + " 个）")
                .setItems(items, (d, which) -> {
                    selectedMac = macs.get(which);
                    selectedName = labels.get(which).split("\n")[0];
                    prefs.edit().putString("mac", selectedMac).apply();
                    refreshUi();
                    Toast.makeText(this, "已选择 " + selectedMac, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("重新扫描", (d, w) -> showScanDialog())
                .show();
    }

    // ---------- 私有 UUID ----------

    private void showPrivateUuidDialog() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("nRF Connect 里看到的私有电量特征 UUID，例如：\n"
                + "13bb0001-5884-4c5d-b75b-8768de...（要填完整 36 位）\n"
                + "留空则只读标准 0x2A19");
        hint.setTextSize(12);
        ll.addView(hint);

        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        et.setHint("13bb0001-5884-4c5d-b75b-8768dexxxxxx");
        et.setText(privateUuid);
        et.setTextSize(13);
        ll.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("私有电量特征 UUID")
                .setView(ll)
                .setPositiveButton("保存", (d, w) -> {
                    privateUuid = et.getText().toString().trim();
                    prefs.edit().putString("private_uuid", privateUuid).apply();
                    Toast.makeText(this, privateUuid.isEmpty() ? "已清空" : "已保存",
                            Toast.LENGTH_SHORT).show();
                    refreshUi();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 权限 ----------

    private boolean hasBlePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void checkPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> ask = new ArrayList<>();
        for (String p : need) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ask.add(p);
            }
        }
        if (!ask.isEmpty()) {
            ActivityCompat.requestPermissions(this, ask.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_PERM) {
            for (int i = 0; i < p.length; i++) {
                if (r[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "缺少权限 " + p[i] + "，BLE 功能不可用",
                            Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }

    // ---------- 控制 ----------

    private void startMonitor() {
        if (selectedMac.isEmpty()) {
            Toast.makeText(this, "请先扫描并选择目标设备", Toast.LENGTH_LONG).show();
            showScanDialog();
            return;
        }
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(MonitorService.ACTION_START);
        i.putExtra(MonitorService.EXTRA_INTERVAL, intervalMs);
        i.putExtra(MonitorService.EXTRA_TAG, currentTag);
        i.putExtra(MonitorService.EXTRA_MAC, selectedMac);
        if (!privateUuid.isEmpty()) {
            i.putExtra(MonitorService.EXTRA_PRIVATE_UUID, privateUuid);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        running = true;
        runStart = System.currentTimeMillis();
        btnStart.setText("停止记录");
        suggestBatteryWhitelist();
        Toast.makeText(this, "开始记录 " + selectedMac, Toast.LENGTH_SHORT).show();
    }

    private void stopMonitor() {
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(MonitorService.ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        running = false;
        btnStart.setText("开始记录");
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
        refreshUi();
    }

    private void suggestBatteryWhitelist() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "长时间测试建议：把本 App 加入电池优化白名单",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {}
    }

    // ---------- UI 刷新 ----------

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            ui.postDelayed(this, 1000);
        }
    };

    private void refreshUi() {
        List<BatteryLog.Sample> data = MonitorService.LOG.snapshot();
        chart.setData(data);
        tvCount.setText(String.valueOf(data.size()));

        int lastLevel = MonitorService.sLastLevel;
        if (lastLevel >= 0) {
            tvLevel.setText(String.valueOf(lastLevel));
        } else {
            tvLevel.setText("--");
        }
        tvSource.setText("来源：" + MonitorService.sLastSource);

        tvConn.setText("连接状态：" + MonitorService.sConnState);
        tvBleReads.setText("BLE 读数："
                + (MonitorService.sBleReads > 0 ? MonitorService.sBleReads + " 次" : "—"));

        if (selectedMac.isEmpty()) {
            tvDevice.setText("未选择设备 — 点右上「扫描」选择");
        } else {
            tvDevice.setText("目标：" + (selectedName.isEmpty() ? "" : selectedName + "  ")
                    + selectedMac);
        }

        if (running && runStart > 0) {
            long d = System.currentTimeMillis() - runStart;
            tvDuration.setText(String.format(Locale.CHINA, "%02d:%02d:%02d",
                    d / 3600000, (d % 3600000) / 60000, (d % 60000) / 1000));
        } else {
            tvDuration.setText("00:00:00");
        }

        // 掉电速率 %/hour
        BatteryLog.Sample first = MonitorService.LOG.firstValid();
        BatteryLog.Sample last = null;
        for (int i = data.size() - 1; i >= 0; i--) {
            if (data.get(i).level >= 0) { last = data.get(i); break; }
        }
        if (first != null && last != null && last.timestamp != first.timestamp) {
            long dt = last.timestamp - first.timestamp;
            int dv = first.level - last.level;
            if (dt >= 60_000 && dv > 0) {
                double perHour = dv * 3600_000.0 / dt;
                tvRate.setText(String.format(Locale.CHINA, "%.1f %%/h", perHour));
            } else {
                tvRate.setText("— %/h");
            }
        } else {
            tvRate.setText("— %/h");
        }

        // 粒度检测
        int[] r = MonitorService.LOG.resolutionStats();
        if (data.size() < 3) {
            tvResolution.setText("上报粒度检测：待采集（至少 3 个点）");
        } else if (r[0] <= 1) {
            tvResolution.setText("上报粒度检测：所有值相同，暂无法判断");
        } else if (r[1] >= 10) {
            tvResolution.setText("上报粒度：约 10% 档（最小间隔 " + r[1]
                    + "，出现 " + r[0] + " 个不同值）→ 固件只报整十");
        } else if (r[1] == 1) {
            tvResolution.setText("上报粒度：1%（最小间隔 1，出现 " + r[0]
                    + " 个不同值）→ 精度够用");
        } else {
            tvResolution.setText("上报粒度：约 " + r[1] + "%（最小间隔 " + r[1]
                    + "，出现 " + r[0] + " 个不同值）→ 非整十但未达 1%");
        }

        tvInterval.setText(INTERVAL_LABEL[seekInterval.getProgress()]);
    }

    // ---------- 导出 ----------

    private void exportCsv() {
        List<BatteryLog.Sample> data = MonitorService.LOG.snapshot();
        if (data.isEmpty()) {
            Toast.makeText(this, "没有数据可导出", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir = BatteryLog.logDir(this);
            String name = "export_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                    .format(new java.util.Date()) + ".csv";
            File out = new File(dir, name);

            String content = MonitorService.LOG.toCsv(
                    selectedName.isEmpty() ? "未知设备" : selectedName, selectedMac);
            FileWriter w = new FileWriter(out);
            w.write(content);
            w.close();

            tvLogPath.setText("导出到：" + out.getAbsolutePath());

            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", out);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "耳机电量记录 " + name);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "导出 CSV"));

        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        try {
            if (tickReceiver != null) unregisterReceiver(tickReceiver);
        } catch (Exception ignored) {}
    }
}
