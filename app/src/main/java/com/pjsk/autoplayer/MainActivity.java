package com.pjsk.autoplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.pjsk.autoplayer.overlay.StatusOverlay;
import com.pjsk.autoplayer.settings.AppSettings;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;

    private TextView statusView;
    private TextView overlayStatusView;
    private TextView calibrationValueView;
    private TextView touchMappingView;
    private SeekBar calibrationSeekBar;
    private Switch previewSwitch;
    private Switch noClickSwitch;
    private Switch autoSoloSwitch;
    private boolean updatingCalibrationUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(20));
        root.setBackgroundColor(Color.rgb(247, 248, 250));

        TextView title = new TextView(this);
        title.setText("PJSK Native Auto");
        title.setTextColor(Color.rgb(20, 24, 32));
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setText("状态：未启动");
        statusView.setTextColor(Color.rgb(45, 52, 64));
        statusView.setTextSize(15f);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.setMargins(0, dp(8), 0, dp(10));
        root.addView(statusView, statusParams);

        Button start = new Button(this);
        start.setText("开始运行");
        start.setAllCaps(false);
        start.setOnClickListener(v -> requestCapture());
        root.addView(start, buttonParams());

        Button stop = new Button(this);
        stop.setText("停止运行");
        stop.setAllCaps(false);
        stop.setOnClickListener(v -> requestStop());
        root.addView(stop, buttonParams());

        previewSwitch = new Switch(this);
        previewSwitch.setText("显示识别预览窗口");
        previewSwitch.setTextSize(15f);
        previewSwitch.setTextColor(Color.rgb(45, 52, 64));
        previewSwitch.setChecked(AppSettings.isPreviewEnabled(this));
        previewSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setPreviewEnabled(this, isChecked);
            if (CaptureService.isRunning()) {
                Intent intent = new Intent(this, CaptureService.class)
                        .setAction(CaptureService.ACTION_SET_PREVIEW)
                        .putExtra(CaptureService.EXTRA_PREVIEW_ENABLED, isChecked);
                startService(intent);
            }
            statusView.setText(isChecked
                    ? "状态：识别预览已开启"
                    : "状态：识别预览已关闭");
        });
        LinearLayout.LayoutParams switchParams = matchWrap();
        switchParams.setMargins(0, dp(8), 0, 0);
        root.addView(previewSwitch, switchParams);

        noClickSwitch = new Switch(this);
        noClickSwitch.setText("不点击模式（只识别，关闭后 5 秒恢复点击）");
        noClickSwitch.setTextSize(15f);
        noClickSwitch.setTextColor(Color.rgb(45, 52, 64));
        noClickSwitch.setChecked(AppSettings.isNoClickMode(this));
        noClickSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setNoClickMode(this, isChecked);
            statusView.setText(isChecked
                    ? "状态：已开启只识别不点击"
                    : "状态：5 秒后恢复点击");
        });
        LinearLayout.LayoutParams noClickParams = matchWrap();
        noClickParams.setMargins(0, dp(6), 0, 0);
        root.addView(noClickSwitch, noClickParams);

        autoSoloSwitch = new Switch(this);
        autoSoloSwitch.setText("自动单人模式");
        autoSoloSwitch.setTextSize(15f);
        autoSoloSwitch.setTextColor(Color.rgb(45, 52, 64));
        autoSoloSwitch.setChecked(AppSettings.isAutoSoloModeEnabled(this));
        autoSoloSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setAutoSoloModeEnabled(this, isChecked);
            statusView.setText(isChecked
                    ? "状态：已开启自动单人模式"
                    : "状态：已关闭自动单人模式");
        });
        LinearLayout.LayoutParams autoSoloParams = matchWrap();
        autoSoloParams.setMargins(0, dp(6), 0, 0);
        root.addView(autoSoloSwitch, autoSoloParams);

        addCalibrationControls(root);
        addTouchMappingControls(root);

        overlayStatusView = new TextView(this);
        overlayStatusView.setTextSize(15f);
        overlayStatusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams overlayStatusParams = matchWrap();
        overlayStatusParams.setMargins(0, dp(12), 0, dp(4));
        root.addView(overlayStatusView, overlayStatusParams);

        Button overlay = new Button(this);
        overlay.setText("开启悬浮窗权限");
        overlay.setAllCaps(false);
        overlay.setOnClickListener(v -> openOverlaySettings());
        root.addView(overlay, buttonParams());

        TextView hint = new TextView(this);
        hint.setText("运行后切到目标界面，左上角显示状态；识别预览会抽样显示画面、识别框、处理 FPS 和丢帧数。");
        hint.setTextColor(Color.rgb(88, 96, 110));
        hint.setTextSize(14f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.setMargins(0, dp(10), 0, 0);
        root.addView(hint, hintParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateOverlayStatus();
        if (previewSwitch != null) {
            previewSwitch.setChecked(AppSettings.isPreviewEnabled(this));
        }
        if (noClickSwitch != null) {
            noClickSwitch.setChecked(AppSettings.isNoClickMode(this));
        }
        if (autoSoloSwitch != null) {
            autoSoloSwitch.setChecked(AppSettings.isAutoSoloModeEnabled(this));
        }
        updateCalibrationUi();
        updateTouchMappingUi();
    }

    private void requestCapture() {
        if (!StatusOverlay.canDrawOverlays(this)) {
            statusView.setText("状态：未开启悬浮窗权限，仍会运行，但只能看通知栏状态");
        } else {
            statusView.setText("状态：等待录屏授权");
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            statusView.setText("状态：无法获取录屏服务");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void requestStop() {
        Intent service = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
        startService(service);
        statusView.setText("状态：已发送停止命令");
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void updateOverlayStatus() {
        boolean granted = StatusOverlay.canDrawOverlays(this);
        overlayStatusView.setText(granted
                ? "悬浮窗权限：已开启"
                : "悬浮窗权限：未开启");
        overlayStatusView.setTextColor(granted
                ? Color.rgb(30, 122, 72)
                : Color.rgb(180, 82, 32));
    }

    private void addCalibrationControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("判定点校准");
        title.setTextColor(Color.rgb(20, 24, 32));
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        calibrationValueView = new TextView(this);
        calibrationValueView.setTextColor(Color.rgb(45, 52, 64));
        calibrationValueView.setTextSize(15f);
        calibrationValueView.setGravity(Gravity.CENTER);
        root.addView(calibrationValueView, matchWrap());

        calibrationSeekBar = new SeekBar(this);
        calibrationSeekBar.setMax(AppSettings.ACTION_Y_MAX - AppSettings.ACTION_Y_MIN);
        calibrationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingCalibrationUi) {
                    return;
                }
                setActionY(AppSettings.ACTION_Y_MIN + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        LinearLayout.LayoutParams seekParams = matchWrap();
        seekParams.setMargins(0, dp(4), 0, 0);
        root.addView(calibrationSeekBar, seekParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button up = new Button(this);
        up.setText("上移");
        up.setAllCaps(false);
        up.setOnClickListener(v -> adjustActionY(-2.0));
        row.addView(up, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button reset = new Button(this);
        reset.setText("重置");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> {
            AppSettings.resetActionY(this);
            updateCalibrationUi();
            statusView.setText("状态：判定点已重置");
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        resetParams.setMargins(dp(8), 0, dp(8), 0);
        row.addView(reset, resetParams);

        Button down = new Button(this);
        down.setText("下移");
        down.setAllCaps(false);
        down.setOnClickListener(v -> adjustActionY(2.0));
        row.addView(down, new LinearLayout.LayoutParams(0, dp(42), 1f));

        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.setMargins(0, dp(6), 0, 0);
        root.addView(row, rowParams);

        updateCalibrationUi();
    }

    private void adjustActionY(double delta) {
        setActionY(AppSettings.getActionY(this) + delta);
    }

    private void setActionY(double value) {
        AppSettings.setActionY(this, value);
        updateCalibrationUi();
        statusView.setText("状态：判定点已调整");
    }

    private void updateCalibrationUi() {
        if (calibrationValueView == null || calibrationSeekBar == null) {
            return;
        }
        int actionY = (int) Math.round(AppSettings.getActionY(this));
        calibrationValueView.setText("当前判定点：" + actionY);
        updatingCalibrationUi = true;
        calibrationSeekBar.setProgress(actionY - AppSettings.ACTION_Y_MIN);
        updatingCalibrationUi = false;
    }

    private void addTouchMappingControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("点击映射");
        title.setTextColor(Color.rgb(20, 24, 32));
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        touchMappingView = new TextView(this);
        touchMappingView.setTextColor(Color.rgb(45, 52, 64));
        touchMappingView.setTextSize(15f);
        touchMappingView.setGravity(Gravity.CENTER);
        root.addView(touchMappingView, matchWrap());

        Button cycle = new Button(this);
        cycle.setText("切换点击映射");
        cycle.setAllCaps(false);
        cycle.setOnClickListener(v -> {
            int mode = AppSettings.nextTouchMappingMode(this);
            updateTouchMappingUi();
            statusView.setText("状态：点击映射已切换为 "
                    + AppSettings.touchMappingLabel(mode));
        });
        root.addView(cycle, buttonParams());

        updateTouchMappingUi();
    }

    private void updateTouchMappingUi() {
        if (touchMappingView == null) {
            return;
        }
        int mode = AppSettings.getTouchMappingMode(this);
        touchMappingView.setText("当前映射：" + AppSettings.touchMappingLabel(mode));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            statusView.setText("状态：录屏授权已取消");
            return;
        }

        Intent service = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        statusView.setText("状态：已启动，切回目标界面查看悬浮窗");
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46));
        params.setMargins(0, dp(7), 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
