package com.istar.btbattery;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 电量采样记录（内存环形缓冲 + CSV 落盘）
 * 只存原始值，不做任何平滑/取整，保证精度可追溯。
 */
public class BatteryLog {

    public static class Sample {
        public final long timestamp;   // 毫秒
        public final int level;        // 原始值，-1 = 读不到
        public final int quality;      // 0=未知 1=有效
        public final String tag;       // 场景标记：待机/放歌/通话

        public Sample(long timestamp, int level, int quality, String tag) {
            this.timestamp = timestamp;
            this.level = level;
            this.quality = quality;
            this.tag = tag == null ? "" : tag;
        }
    }

    private static final int MAX_POINTS = 3000;
    private final List<Sample> samples = new ArrayList<>();
    private final SimpleDateFormat tsFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);

    public synchronized void add(Sample s) {
        samples.add(s);
        if (samples.size() > MAX_POINTS) {
            samples.subList(0, samples.size() - MAX_POINTS).clear();
        }
    }

    public synchronized List<Sample> snapshot() {
        return new ArrayList<>(samples);
    }

    public synchronized void clear() {
        samples.clear();
    }

    public synchronized int size() {
        return samples.size();
    }

    /** 最近一个有效值 */
    public synchronized int lastValidLevel() {
        for (int i = samples.size() - 1; i >= 0; i--) {
            Sample s = samples.get(i);
            if (s.level >= 0) return s.level;
        }
        return -1;
    }

    public synchronized Sample firstValid() {
        for (Sample s : samples) {
            if (s.level >= 0) return s;
        }
        return null;
    }

    /**
     * 关键统计：判断固件上报的其实是几档
     * 返回 {不同值个数, 最小间隔, 最大间隔}
     */
    public synchronized int[] resolutionStats() {
        List<Integer> distinct = new ArrayList<>();
        for (Sample s : samples) {
            if (s.level < 0) continue;
            if (!distinct.contains(s.level)) distinct.add(s.level);
        }
        java.util.Collections.sort(distinct);
        int minGap = 999, maxGap = 0;
        for (int i = 1; i < distinct.size(); i++) {
            int gap = distinct.get(i) - distinct.get(i - 1);
            if (gap < minGap) minGap = gap;
            if (gap > maxGap) maxGap = gap;
        }
        if (distinct.size() < 2) { minGap = -1; maxGap = -1; }
        return new int[]{distinct.size(), minGap, maxGap};
    }

    /** 场景分组统计：{场景, 首值, 末值, 时长ms, 采样数} */
    public synchronized List<String[]> groupByTag() {
        java.util.LinkedHashMap<String, List<Sample>> map = new java.util.LinkedHashMap<>();
        for (Sample s : samples) {
            String k = s.tag == null || s.tag.isEmpty() ? "未标记" : s.tag;
            List<Sample> list = map.get(k);
            if (list == null) { list = new ArrayList<>(); map.put(k, list); }
            list.add(s);
        }
        List<String[]> out = new ArrayList<>();
        for (java.util.Map.Entry<String, List<Sample>> e : map.entrySet()) {
            List<Sample> list = e.getValue();
            Integer first = null, last = null;
            long t0 = 0, t1 = 0;
            for (Sample s : list) {
                if (s.level < 0) continue;
                if (first == null) { first = s.level; t0 = s.timestamp; }
                last = s.level; t1 = s.timestamp;
            }
            if (first == null) continue;
            out.add(new String[]{
                    e.getKey(), String.valueOf(first), String.valueOf(last),
                    String.valueOf(t1 - t0), String.valueOf(list.size())
            });
        }
        return out;
    }

    /** 生成 CSV 内容 */
    public synchronized String toCsv(String deviceName, String deviceMac) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 蓝牙耳机电量记录\r\n");
        sb.append("# 设备,").append(csv(deviceName)).append("\r\n");
        sb.append("# MAC,").append(csv(deviceMac)).append("\r\n");
        sb.append("# 导出时间,").append(tsFmt.format(new Date())).append("\r\n");
        int[] r = resolutionStats();
        sb.append("# 采样点数,").append(samples.size()).append("\r\n");
        sb.append("# 出现过的不同电量值个数,").append(r[0])
          .append("（若为 11 说明固件只报 10 档；若几十个说明 1% 粒度）\r\n");
        sb.append("\r\n");

        // 场景汇总（放报告里最有用的一段）
        List<String[]> groups = groupByTag();
        if (!groups.isEmpty()) {
            sb.append("# ===== 场景汇总 =====\r\n");
            sb.append("场景,起始电量,结束电量,消耗,时长(分钟),掉电速率(%/h),采样数\r\n");
            for (String[] g : groups) {
                int f = parseInt(g[1]), l = parseInt(g[2]);
                long dur = parseLong(g[3]);
                double hours = dur / 3600_000.0;
                String rate = (hours > 0.001 && f > l)
                        ? String.format(Locale.CHINA, "%.2f", (f - l) / hours) : "";
                sb.append(csv(g[0])).append(',')
                  .append(g[1]).append(',')
                  .append(g[2]).append(',')
                  .append(f - l).append(',')
                  .append(String.format(Locale.CHINA, "%.1f", dur / 60000.0)).append(',')
                  .append(rate).append(',')
                  .append(g[4]).append("\r\n");
            }
            sb.append("\r\n");
        }

        sb.append("# ===== 原始采样 =====\r\n");
        sb.append("时间,时间戳(ms),电量(%),原始值有效,场景\r\n");
        for (Sample s : samples) {
            sb.append(tsFmt.format(new Date(s.timestamp))).append(',')
              .append(s.timestamp).append(',')
              .append(s.level < 0 ? "" : String.valueOf(s.level)).append(',')
              .append(s.quality).append(',')
              .append(csv(s.tag)).append("\r\n");
        }
        return sb.toString();
    }

    private int parseInt(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return 0; }
    }

    private long parseLong(String v) {
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return 0; }
    }

    private String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    public static File logDir(android.content.Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "logs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File externalLogDir(android.content.Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) return null;
        File dir = new File(base, "logs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
