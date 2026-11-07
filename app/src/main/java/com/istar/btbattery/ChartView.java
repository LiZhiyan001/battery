package com.istar.btbattery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * 电量曲线：X = 时间，Y = 电量 0-100
 * 值断档（读不到）时用虚线断开，避免误读成"掉到 0"。
 */
public class ChartView extends View {

    private List<BatteryLog.Sample> data;
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int PAD_L = 42;
    private static final int PAD_R = 14;
    private static final int PAD_T = 16;
    private static final int PAD_B = 30;

    public ChartView(Context c) { super(c); init(); }
    public ChartView(Context c, AttributeSet a) { super(c, a); init(); }
    public ChartView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        pLine.setColor(0xFF4C9AFF);
        pLine.setStrokeWidth(3f);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);

        pGrid.setColor(0xFF2A2F3A);
        pGrid.setStrokeWidth(1f);

        pText.setColor(0xFF9AA3B2);
        pText.setTextSize(24f);

        pDot.setColor(0xFF4C9AFF);
        pDot.setStyle(Paint.Style.FILL);

        pFill.setColor(0x224C9AFF);
        pFill.setStyle(Paint.Style.FILL);
    }

    public void setData(List<BatteryLog.Sample> d) {
        this.data = d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float w = getWidth(), h = getHeight();
        float left = PAD_L, right = w - PAD_R;
        float top = PAD_T, bottom = h - PAD_B;

        // 网格 + Y 轴刻度（0/25/50/75/100）
        for (int v = 0; v <= 100; v += 25) {
            float y = bottom - (bottom - top) * v / 100f;
            cv.drawLine(left, y, right, y, pGrid);
            cv.drawText(String.valueOf(v), 8, y + 8, pText);
        }

        if (data == null || data.isEmpty()) {
            cv.drawText("暂无数据，点开始记录", left + 20, (top + bottom) / 2, pText);
            return;
        }

        long t0 = Long.MAX_VALUE, t1 = Long.MIN_VALUE;
        for (BatteryLog.Sample s : data) {
            if (s.level < 0) continue;
            if (s.timestamp < t0) t0 = s.timestamp;
            if (s.timestamp > t1) t1 = s.timestamp;
        }
        if (t0 == Long.MAX_VALUE) {
            cv.drawText("全部采样均读取失败（固件不报电量）", left + 20, (top + bottom) / 2, pText);
            return;
        }
        long span = Math.max(1, t1 - t0);

        // X 轴端点时间标注
        cv.drawText(fmtTime(t0), left, h - 8, pText);
        String ts = fmtTime(t1);
        cv.drawText(ts, right - pText.measureText(ts), h - 8, pText);

        Path path = new Path();
        Path fill = new Path();
        boolean started = false;
        float prevX = 0, prevY = 0;
        boolean prevValid = false;

        for (BatteryLog.Sample s : data) {
            if (s.level < 0) { prevValid = false; continue; }
            float x = left + (right - left) * (s.timestamp - t0) / (float) span;
            float y = bottom - (bottom - top) * s.level / 100f;

            if (!prevValid) {
                path.moveTo(x, y);
                if (!started) { fill.moveTo(x, bottom); fill.lineTo(x, y); started = true; }
                else { fill.lineTo(x, bottom); fill.lineTo(x, y); }
            } else {
                path.lineTo(x, y);
                fill.lineTo(x, y);
            }
            prevX = x; prevY = y; prevValid = true;
        }
        if (started) {
            fill.lineTo(prevX, bottom);
            fill.close();
            cv.drawPath(fill, pFill);
        }
        cv.drawPath(path, pLine);

        // 最后一个点标记
        if (prevValid) {
            cv.drawCircle(prevX, prevY, 6f, pDot);
        }
    }

    private String fmtTime(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.CHINA);
        return f.format(new java.util.Date(ms));
    }
}
