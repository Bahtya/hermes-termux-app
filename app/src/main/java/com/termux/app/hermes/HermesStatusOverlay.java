package com.termux.app.hermes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.termux.R;

import androidx.core.content.ContextCompat;

/**
 * A small overlay view that shows a colored dot indicating the Hermes gateway status.
 * Green = running, red = stopped, gray = not installed.
 * Tapping the dot opens HermesConfigActivity.
 */
public class HermesStatusOverlay extends View {

    private static final long REFRESH_INTERVAL_MS = 10_000; // 10 seconds

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private HermesGatewayStatus.Status mCurrentStatus = HermesGatewayStatus.Status.NOT_INSTALLED;
    private boolean mChecking = false;

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            checkStatus();
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    public HermesStatusOverlay(Context context) {
        this(context, null);
    }

    public HermesStatusOverlay(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HermesStatusOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        int size = dpToPx(16);
        setLayoutParams(new WindowManager.LayoutParams(size, size));

        mPaint.setColor(getColorForStatus(mCurrentStatus));
        mPaint.setStyle(Paint.Style.FILL);

        setContentDescription(getContext().getString(R.string.hermes_status_overlay_content_description));

        setOnClickListener(v -> {
            Context ctx = getContext();
            ctx.startActivity(new Intent(ctx, HermesConfigActivity.class));
        });

        setClickable(true);
        setFocusable(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dpToPx(1);
        mPaint.setColor(getColorForStatus(mCurrentStatus));
        canvas.drawCircle(cx, cy, radius, mPaint);
    }

    /**
     * Start periodic status checks.
     */
    public void startMonitoring() {
        checkStatus();
        mHandler.removeCallbacks(mRefreshRunnable);
        mHandler.postDelayed(mRefreshRunnable, REFRESH_INTERVAL_MS);
    }

    /**
     * Stop periodic status checks.
     */
    public void stopMonitoring() {
        mHandler.removeCallbacks(mRefreshRunnable);
    }

    private void checkStatus() {
        if (mChecking) return;
        mChecking = true;
        HermesGatewayStatus.checkAsync((status, detail) -> {
            mHandler.post(() -> {
                mCurrentStatus = status;
                updateContentDescription();
                invalidate();
                mChecking = false;
            });
        });
    }

    private void updateContentDescription() {
        Context ctx = getContext();
        String desc;
        switch (mCurrentStatus) {
            case RUNNING:
                desc = ctx.getString(R.string.hermes_status_overlay_running);
                break;
            case STOPPED:
                desc = ctx.getString(R.string.hermes_status_overlay_stopped);
                break;
            case NOT_INSTALLED:
            default:
                desc = ctx.getString(R.string.hermes_status_overlay_not_installed);
                break;
        }
        setContentDescription(desc);
    }

    private int getColorForStatus(HermesGatewayStatus.Status status) {
        switch (status) {
            case RUNNING:
                return ContextCompat.getColor(getContext(), R.color.hermes_status_running);
            case STOPPED:
                return ContextCompat.getColor(getContext(), R.color.hermes_status_stopped);
            case NOT_INSTALLED:
            default:
                return ContextCompat.getColor(getContext(), R.color.hermes_status_not_installed);
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
