package com.ark3trike.matches;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：负责一次性录制拖动并执行宏（点击按钮中心 -> 拖动 -> 返回）。
 */
public class VolumeKeyAccessibilityService extends AccessibilityService {

    private static final String TAG = "VolumeKeyService";
    private static VolumeKeyAccessibilityService instance;
    private static boolean functionEnabled = false;

    // 坐标比例（基于 1920x1080 给定点计算得到）
    private static final float BUTTON_CENTER_RX = 0.9370f;
    private static final float BUTTON_CENTER_RY = 0.0745f;

    private static final long GESTURE_DELAY_MS = 10L;
    private static final long DELAY_BEFORE_DRAG_MS = 1000L; // 避免手势落在 overlay 上
    private static final long DRAG_DURATION_MS = 400L;      // 拖动时长，避免被系统忽略
    private static final long DRAG_TIMEOUT_MS = 3000L;
    private static final float MIN_DRAG_DISTANCE_PX = 5f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager overlayWindowManager;
    private RecordingOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;

    private boolean recordingActive = false;
    private float startX;
    private float startY;
    private float currentX;
    private float currentY;
    private boolean showArrow = false;
    private long dragStartTime;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
        instance = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 未使用
    }

    @Override
    public void onInterrupt() {
        // 未使用
    }

    /**
    * 开关由浮窗控制；开启时进入录制待机，关闭时清理。
    */
    public static void setFunctionEnabled(boolean enabled) {
        functionEnabled = enabled;
        VolumeKeyAccessibilityService svc = instance;
        if (svc != null) {
            svc.onFunctionStateChanged(enabled);
        }
        FloatingWindowService.notifyFunctionState(enabled);
    }

    public static boolean isFunctionEnabled() {
        return functionEnabled;
    }

    public static VolumeKeyAccessibilityService getInstance() {
        return instance;
    }

    private void onFunctionStateChanged(boolean enabled) {
        if (enabled) {
            startRecordingOverlay();
        } else {
            removeOverlay();
            recordingActive = false;
        }
    }

    /**
     * 添加全屏透明 overlay 捕获下一次拖动。
     */
    private void startRecordingOverlay() {
        if (overlayView != null || !functionEnabled) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture dispatch requires API 24+, current=" + Build.VERSION.SDK_INT);
            failThisRound("api_too_low");
            return;
        }

        overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (overlayWindowManager == null) {
            Log.e(TAG, "WindowManager null, cannot start overlay");
            return;
        }

        overlayView = new RecordingOverlayView(this);
        overlayView.setOnTouchListener(this::handleOverlayTouch);

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            overlayParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        try {
            overlayWindowManager.addView(overlayView, overlayParams);
            Log.d(TAG, "Recording overlay added");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay", e);
            overlayView = null;
        }
    }

    private void removeOverlay() {
        if (overlayWindowManager != null && overlayView != null) {
            try {
                overlayWindowManager.removeView(overlayView);
                Log.d(TAG, "Recording overlay removed");
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove overlay", e);
            }
        }
        overlayView = null;
        overlayParams = null;
    }

    private boolean handleOverlayTouch(View v, MotionEvent event) {
        if (!functionEnabled) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getPointerCount() != 1) return false;
                recordingActive = true;
                startX = event.getRawX();
                startY = event.getRawY();
                currentX = startX;
                currentY = startY;
                showArrow = true;
                dragStartTime = System.currentTimeMillis();
                if (overlayView != null) overlayView.invalidate();
                Log.d(TAG, "Drag start x=" + startX + ", y=" + startY);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (recordingActive) {
                    currentX = event.getRawX();
                    currentY = event.getRawY();
                    if (overlayView != null) overlayView.invalidate();
                }
                return recordingActive;
            case MotionEvent.ACTION_UP:
                if (!recordingActive) return false;
                if (event.getPointerCount() != 1) {
                    failThisRound("multi_pointer");
                    return true;
                }
                long duration = System.currentTimeMillis() - dragStartTime;
                float endX = event.getRawX();
                float endY = event.getRawY();
                float dx = Math.abs(endX - startX);
                float dy = Math.abs(endY - startY);
                float dist = (float) Math.hypot(dx, dy);
                currentX = endX;
                currentY = endY;
                if (overlayView != null) overlayView.invalidate();

                if (duration > DRAG_TIMEOUT_MS) {
                    failThisRound("timeout");
                } else if (dist < MIN_DRAG_DISTANCE_PX) {
                    failThisRound("too_short");
                } else {
                    Log.d(TAG, "Drag end x=" + endX + ", y=" + endY + ", dur=" + duration + "ms");
                    completeRecording(endX, endY);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                failThisRound("cancel");
                return true;
            default:
                return false;
        }
    }

    private void failThisRound(String reason) {
        Log.w(TAG, "Recording failed: " + reason);
        recordingActive = false;
        removeOverlay();
        showArrow = false;
        if (overlayView != null) overlayView.invalidate();
        // 本轮结束，等待用户重新开启
        setFunctionEnabled(false);
    }

    private void completeRecording(float endX, float endY) {
        recordingActive = false;
        removeOverlay();
        showArrow = false;
        if (overlayView != null) overlayView.invalidate();

        PointF start = new PointF(startX, startY);
        PointF end = new PointF(endX, endY);
        PointF buttonCenter = computeButtonCenter();

        runMacroSequence(buttonCenter, start, end);
    }

    private PointF computeButtonCenter() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float x = BUTTON_CENTER_RX * metrics.widthPixels;
        float y = BUTTON_CENTER_RY * metrics.heightPixels;
        return new PointF(x, y);
    }

    private void runMacroSequence(PointF buttonCenter, PointF dragStart, PointF dragEnd) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API requires 24+, current=" + Build.VERSION.SDK_INT);
            failThisRound("api_too_low");
            return;
        }
        Log.d(TAG, "Run macro (drag only after delay): drag " + dragStart + " -> " + dragEnd + ", delay=" + DELAY_BEFORE_DRAG_MS + "ms");
        handler.postDelayed(() -> {
            dispatchDrag(dragStart, dragEnd, new GestureCallbackAdapter(this::finishRound));
            // Fallback：若回调未触发，在拖动时长后再补一次结束
            handler.postDelayed(this::finishRound, DRAG_DURATION_MS + 200);
        }, DELAY_BEFORE_DRAG_MS);
    }

    @TargetApi(Build.VERSION_CODES.N)
    private void dispatchTap(PointF point, GestureResultCallback result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            failThisRound("api_too_low_tap");
            return;
        }
        Path path = new Path();
        path.moveTo(point.x, point.y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 10);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean dispatched = dispatchGesture(gesture, result, null);
        Log.d(TAG, "dispatchTap dispatched=" + dispatched + " to " + point);
        if (!dispatched) {
            failThisRound("gesture_dispatch_failed_tap");
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private void dispatchDrag(PointF start, PointF end, GestureResultCallback result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            failThisRound("api_too_low_drag");
            return;
        }
        Path path = new Path();
        path.moveTo(start.x, start.y);
        path.lineTo(end.x, end.y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, DRAG_DURATION_MS);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean dispatched = dispatchGesture(gesture, result, null);
        Log.d(TAG, "dispatchDrag dispatched=" + dispatched + " from " + start + " to " + end);
        if (!dispatched) {
            failThisRound("gesture_dispatch_failed_drag");
        }
    }

    private void finishRound() {
        Log.d(TAG, "Macro finished (drag only)");
        setFunctionEnabled(false);
    }

    private static class GestureCallbackAdapter extends GestureResultCallback {
        private final Runnable onCompleted;

        GestureCallbackAdapter(Runnable onCompleted) {
            this.onCompleted = onCompleted;
        }

        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            Log.d(TAG, "Gesture completed: " + gestureDescription);
            if (onCompleted != null) {
                onCompleted.run();
            }
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            Log.w(TAG, "Gesture cancelled: " + gestureDescription);
            setFunctionEnabled(false);
        }
    }

    /**
     * 录制过程中绘制箭头指示拖动起止位置。
     */
    private class RecordingOverlayView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RecordingOverlayView(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            linePaint.setColor(0x88FFC107);
            linePaint.setStrokeWidth(8f);
            linePaint.setStyle(Paint.Style.STROKE);

            circlePaint.setColor(0xAAFFC107);
            circlePaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!recordingActive && !showArrow) {
                return;
            }
            float sx = startX;
            float sy = startY;
            float ex = currentX;
            float ey = currentY;

            // 绘制起点终点圆
            canvas.drawCircle(sx, sy, 12f, circlePaint);
            canvas.drawCircle(ex, ey, 12f, circlePaint);

            // 绘制箭头线
            canvas.drawLine(sx, sy, ex, ey, linePaint);

            // 绘制箭头尖
            float dx = ex - sx;
            float dy = ey - sy;
            float len = (float) Math.hypot(dx, dy);
            if (len > 0) {
                float ux = dx / len;
                float uy = dy / len;
                float arrowLen = 36f;
                float arrowWidth = 14f;
                float bx = ex - arrowLen * ux;
                float by = ey - arrowLen * uy;
                float perpX = -uy;
                float perpY = ux;
                float x1 = bx + arrowWidth * perpX;
                float y1 = by + arrowWidth * perpY;
                float x2 = bx - arrowWidth * perpX;
                float y2 = by - arrowWidth * perpY;

                Path arrow = new Path();
                arrow.moveTo(ex, ey);
                arrow.lineTo(x1, y1);
                arrow.lineTo(x2, y2);
                arrow.close();
                canvas.drawPath(arrow, circlePaint);
            }
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean performClick() {
            // 触摸事件已在 onTouch 中处理，这里仅满足可访问性要求。
            return super.performClick();
        }
    }
}
