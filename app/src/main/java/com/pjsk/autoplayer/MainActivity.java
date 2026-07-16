package com.pjsk.autoplayer;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;

import com.pjsk.autoplayer.overlay.StatusOverlay;
import com.pjsk.autoplayer.settings.AppSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;
    public static final String EXTRA_AUTO_REAUTHORIZE = "autoReauthorize";

    private TextView statusView;
    private TextView overlayStatusView;
    private TextView calibrationValueView;
    private TextView touchMappingView;
    private TextView noteModelView;
    private SeekBar calibrationSeekBar;
    private Switch previewSwitch;
    private Switch noClickSwitch;
    private Switch autoSoloSwitch;
    private Switch logicPlaySwitch;
    private TextView captureTargetView;
    private Spinner captureTargetSelector;
    private ArrayAdapter<String> captureTargetAdapter;
    private final List<CaptureTargetChoice> captureTargets = new ArrayList<>();
    private boolean updatingCaptureTargetSelector;
    private TextView logicProfileView;
    private EditText logicProfileSearch;
    private Spinner logicProfileSelector;
    private ArrayAdapter<String> logicProfileAdapter;
    private final List<AppSettings.LogicProfileChoice> visibleLogicProfiles = new ArrayList<>();
    private boolean updatingLogicProfileSelector;
    private boolean updatingCalibrationUi;
    private boolean captureRequestInFlight;

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

        addCaptureTargetControls(root);

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

        logicPlaySwitch = new Switch(this);
        logicPlaySwitch.setText("\u903b\u8f91\u6f14\u594f\u6a21\u5f0f");
        logicPlaySwitch.setTextSize(15f);
        logicPlaySwitch.setTextColor(Color.rgb(45, 52, 64));
        logicPlaySwitch.setChecked(AppSettings.isLogicPlayModeEnabled(this));
        logicPlaySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSettings.setLogicPlayModeEnabled(this, isChecked);
            if (isChecked) {
                AppSettings.setAutoSoloModeEnabled(this, true);
                if (autoSoloSwitch != null && !autoSoloSwitch.isChecked()) {
                    autoSoloSwitch.setChecked(true);
                }
            }
            statusView.setText(isChecked
                    ? "\u72b6\u6001\uff1a\u5df2\u5f00\u542f\u903b\u8f91\u6f14\u594f\u6a21\u5f0f\uff0c\u5148\u8fdb\u5165\u6e38\u620f\u7ed3\u675f\u6d41\u7a0b"
                    : "\u72b6\u6001\uff1a\u5df2\u5173\u95ed\u903b\u8f91\u6f14\u594f\u6a21\u5f0f");
        });
        LinearLayout.LayoutParams logicPlayParams = matchWrap();
        logicPlayParams.setMargins(0, dp(6), 0, 0);
        root.addView(logicPlaySwitch, logicPlayParams);

        addLogicProfileControls(root);

        addCalibrationControls(root);
        addTouchMappingControls(root);
        addNoteModelControls(root);

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

        if (getIntent().getBooleanExtra(EXTRA_AUTO_REAUTHORIZE, false)) {
            getIntent().removeExtra(EXTRA_AUTO_REAUTHORIZE);
            root.post(this::showReauthorizationRequired);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTO_REAUTHORIZE, false)) {
            intent.removeExtra(EXTRA_AUTO_REAUTHORIZE);
            showReauthorizationRequired();
        }
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
        if (logicPlaySwitch != null) {
            logicPlaySwitch.setChecked(AppSettings.isLogicPlayModeEnabled(this));
        }
        updateLogicProfileUi();
        updateCalibrationUi();
        updateTouchMappingUi();
        updateNoteModelUi();
        updateCaptureTargetUi();
    }

    private void requestCapture() {
        requestCapture(false);
    }

    private void requestCapture(boolean automaticRecovery) {
        if (captureRequestInFlight) {
            return;
        }
        captureRequestInFlight = true;
        String targetPackage = AppSettings.captureTargetPackage(this);
        String targetLabel = AppSettings.captureTargetLabel(this);
        if (targetPackage.isEmpty()) {
            captureRequestInFlight = false;
            statusView.setText("\u72b6\u6001\uff1a\u8bf7\u5148\u9009\u62e9\u5355\u5e94\u7528\u5f55\u5c4f\u76ee\u6807");
            Toast.makeText(this, "\u8bf7\u5148\u5728\u4e3b\u83dc\u5355\u9009\u62e9\u8981\u5f55\u5236\u7684\u6e38\u620f", Toast.LENGTH_LONG).show();
            return;
        }
        if (automaticRecovery) {
            statusView.setText("\u72b6\u6001\uff1a\u6b63\u5728\u81ea\u52a8\u6062\u590d\u5355\u5e94\u7528\u5f55\u5c4f\u6388\u6743");
        }
        if (!StatusOverlay.canDrawOverlays(this)) {
            statusView.setText("状态：未开启悬浮窗权限，仍会运行，但只能看通知栏状态");
        } else {
            statusView.setText("状态：等待录屏授权");
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            captureRequestInFlight = false;
            statusView.setText("状态：无法获取录屏服务");
            return;
        }
        new Thread(() -> {
            // System confirmation is authoritative. Do not mutate the AppOp through root.
            boolean rootGranted = true;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    captureRequestInFlight = false;
                    return;
                }
                if (!rootGranted) {
                    statusView.setText("\u72b6\u6001\uff1a\u81ea\u52a8\u6388\u6743\u4e0d\u53ef\u7528\uff0c\u8bf7\u5728\u7cfb\u7edf\u9875\u9762\u624b\u52a8\u6388\u6743\u5355\u5e94\u7528\u5f55\u5c4f");
                }
                Toast.makeText(this,
                        "\u8bf7\u5728\u7cfb\u7edf\u9875\u9762\u9009\u62e9\u5355\u5e94\u7528\uff0c\u5e76\u9009\u62e9\uff1a" + targetLabel,
                        Toast.LENGTH_LONG).show();
                startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
            });
        }, "pjsk-projection-auth").start();
    }

    private void showReauthorizationRequired() {
        String label = AppSettings.captureTargetLabel(this);
        statusView.setText(label.isEmpty()
                ? "\u72b6\u6001\uff1a\u5f55\u5c4f\u5df2\u505c\u6b62\uff0c\u8bf7\u9009\u62e9\u76ee\u6807\u540e\u70b9\u51fb\u5f00\u59cb\u8fd0\u884c"
                : "\u72b6\u6001\uff1a\u5f55\u5c4f\u5df2\u505c\u6b62\uff0c\u8bf7\u70b9\u51fb\u5f00\u59cb\u8fd0\u884c\u91cd\u65b0\u6388\u6743 " + label);
    }

    /**
     * Android owns the final MediaProjection picker. This selector stores the
     * intended game so every later system authorization points to one stable
     * target, without using a root AppOp bypass.
     */
    private void addCaptureTargetControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("\u5355\u5e94\u7528\u5f55\u5c4f\u76ee\u6807");
        title.setTextColor(Color.rgb(20, 24, 32));
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        captureTargetView = new TextView(this);
        captureTargetView.setTextSize(14f);
        captureTargetView.setTextColor(Color.rgb(72, 80, 94));
        captureTargetView.setGravity(Gravity.CENTER);
        root.addView(captureTargetView, matchWrap());

        captureTargetAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new ArrayList<>());
        captureTargetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        captureTargetSelector = new Spinner(this);
        captureTargetSelector.setAdapter(captureTargetAdapter);
        captureTargetSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (updatingCaptureTargetSelector || position < 0 || position >= captureTargets.size()) {
                    return;
                }
                CaptureTargetChoice choice = captureTargets.get(position);
                AppSettings.setCaptureTarget(MainActivity.this, choice.packageName, choice.label);
                updateCaptureTargetUi();
                statusView.setText(choice.packageName.isEmpty()
                        ? "\u72b6\u6001\uff1a\u672a\u9009\u62e9\u5355\u5e94\u7528\u5f55\u5c4f\u76ee\u6807"
                        : "\u72b6\u6001\uff1a\u5f55\u5c4f\u76ee\u6807\u5df2\u8bbe\u4e3a " + choice.label);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(captureTargetSelector, matchWrap());

        Button refresh = new Button(this);
        refresh.setText("\u5237\u65b0\u5e76\u66f4\u6539\u5f55\u5c4f\u76ee\u6807");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> {
            refreshCaptureTargets();
            captureTargetSelector.performClick();
        });
        root.addView(refresh, buttonParams());

        TextView hint = new TextView(this);
        hint.setText("\u9996\u6b21\u8bf7\u9009\u62e9\u6e38\u620f\uff1b\u4ee5\u540e\u4f1a\u8bb0\u4f4f\u6b64\u9009\u62e9\u3002\u7cfb\u7edf\u6388\u6743\u9875\u4ecd\u9700\u624b\u52a8\u786e\u8ba4\u8be5\u5e94\u7528\u3002\n\u5f55\u5c4f\u4e2d\u9014\u505c\u6b62\u540e\uff0c\u56de\u5230\u8fd9\u91cc\u70b9\u51fb\u5f00\u59cb\u8fd0\u884c\u91cd\u65b0\u6388\u6743\u3002");
        hint.setTextColor(Color.rgb(88, 96, 110));
        hint.setTextSize(13f);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, matchWrap());

        refreshCaptureTargets();
    }

    private void refreshCaptureTargets() {
        PackageManager packageManager = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        List<CaptureTargetChoice> choices = new ArrayList<>();
        choices.add(new CaptureTargetChoice("\u672a\u9009\u62e9\uff08\u9996\u6b21\u8bf7\u9009\u6e38\u620f\uff09", ""));
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) {
                continue;
            }
            String label = String.valueOf(info.loadLabel(packageManager)).trim();
            if (label.isEmpty()) {
                label = info.activityInfo.packageName;
            }
            choices.add(new CaptureTargetChoice(label, info.activityInfo.packageName));
        }
        Collections.sort(choices.subList(1, choices.size()),
                Comparator.comparing(choice -> choice.label.toLowerCase(Locale.ROOT)));

        String selectedPackage = AppSettings.captureTargetPackage(this);
        int selectedIndex = 0;
        for (int index = 1; index < choices.size(); index++) {
            if (choices.get(index).packageName.equals(selectedPackage)) {
                selectedIndex = index;
                break;
            }
        }
        updatingCaptureTargetSelector = true;
        captureTargets.clear();
        captureTargets.addAll(choices);
        captureTargetAdapter.clear();
        for (CaptureTargetChoice choice : choices) {
            captureTargetAdapter.add(choice.packageName.isEmpty()
                    ? choice.label
                    : choice.label + "  [" + choice.packageName + "]");
        }
        captureTargetAdapter.notifyDataSetChanged();
        captureTargetSelector.setSelection(selectedIndex);
        updatingCaptureTargetSelector = false;
        updateCaptureTargetUi();
    }

    private void updateCaptureTargetUi() {
        if (captureTargetView == null) {
            return;
        }
        String label = AppSettings.captureTargetLabel(this);
        String packageName = AppSettings.captureTargetPackage(this);
        captureTargetView.setText(packageName.isEmpty()
                ? "\u5f53\u524d\u76ee\u6807\uff1a\u672a\u9009\u62e9"
                : "\u5f53\u524d\u76ee\u6807\uff1a" + label + "  (" + packageName + ")");
    }

    private static final class CaptureTargetChoice {
        final String label;
        final String packageName;

        CaptureTargetChoice(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
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


    private void addLogicProfileControls(LinearLayout root) {
        AppSettings.importLogicProfilesFromDirectory(this);

        TextView title = new TextView(this);
        title.setText("\u903b\u8f91\u6f14\u594f");
        title.setTextColor(Color.rgb(20, 24, 32));
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        logicProfileView = new TextView(this);
        logicProfileView.setTextColor(Color.rgb(45, 52, 64));
        logicProfileView.setTextSize(15f);
        logicProfileView.setGravity(Gravity.CENTER);
        root.addView(logicProfileView, matchWrap());

        TextView directory = new TextView(this);
        File logicDirectory = AppSettings.logicProfilesDirectory(this);
        directory.setText("逻辑目录：" + logicDirectory.getAbsolutePath());
        directory.setTextColor(Color.rgb(88, 96, 110));
        directory.setTextSize(12f);
        directory.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams directoryParams = matchWrap();
        directoryParams.setMargins(0, dp(4), 0, 0);
        root.addView(directory, directoryParams);

        logicProfileSearch = new EditText(this);
        logicProfileSearch.setHint("搜索逻辑名称或 ID");
        logicProfileSearch.setSingleLine(true);
        logicProfileSearch.setTextSize(15f);
        logicProfileSearch.setPadding(dp(12), 0, dp(12), 0);
        logicProfileSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                refreshLogicProfileSelector(editable == null ? "" : editable.toString());
            }
        });
        LinearLayout.LayoutParams searchParams = matchWrap();
        searchParams.setMargins(0, dp(6), 0, 0);
        root.addView(logicProfileSearch, searchParams);

        logicProfileSelector = new Spinner(this);
        logicProfileAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        logicProfileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logicProfileSelector.setAdapter(logicProfileAdapter);
        logicProfileSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (updatingLogicProfileSelector
                        || position < 0 || position >= visibleLogicProfiles.size()) {
                    return;
                }
                AppSettings.LogicProfileChoice choice = visibleLogicProfiles.get(position);
                if (AppSettings.selectLogicProfile(MainActivity.this, choice.id)) {
                    updateLogicProfileUi();
                    statusView.setText("状态：逻辑已切换为 " + choice.name);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams selectorParams = matchWrap();
        selectorParams.setMargins(0, dp(6), 0, 0);
        root.addView(logicProfileSelector, selectorParams);

        Button scanDirectory = new Button(this);
        scanDirectory.setText("扫描逻辑目录");
        scanDirectory.setAllCaps(false);
        scanDirectory.setOnClickListener(v -> {
            int count = AppSettings.importLogicProfilesFromDirectory(this);
            updateLogicProfileUi();
            if (logicProfileSearch != null) {
                logicProfileSearch.setText("");
            }
            showLogicProfileDropdown();
            statusView.setText(count > 0
                    ? "状态：已从目录加载 " + count + " 个逻辑"
                    : "状态：逻辑目录中没有有效 JSON");
        });
        root.addView(scanDirectory, buttonParams());

        Button cycle = new Button(this);
        cycle.setText("\u5207\u6362\u903b\u8f91");
        cycle.setAllCaps(false);
        cycle.setOnClickListener(v -> {
            String label = AppSettings.nextLogicProfile(this);
            updateLogicProfileUi();
            statusView.setText("\u72b6\u6001\uff1a\u903b\u8f91\u5df2\u5207\u6362\u4e3a " + label);
        });
        root.addView(cycle, buttonParams());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button export = new Button(this);
        export.setText("\u5bfc\u51fa\u903b\u8f91");
        export.setAllCaps(false);
        export.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        "pjsk_logic_profiles",
                        AppSettings.exportLogicProfilesJson(this)));
                statusView.setText("\u72b6\u6001\uff1a\u903b\u8f91\u5df2\u5bfc\u51fa\u5230\u526a\u8d34\u677f");
            }
        });
        row.addView(export, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button importProfiles = new Button(this);
        importProfiles.setText("\u5bfc\u5165\u903b\u8f91");
        importProfiles.setAllCaps(false);
        importProfiles.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence clipText = null;
            if (clipboard != null && clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                clipText = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
            }
            boolean ok = clipText != null && AppSettings.importLogicProfilesJson(this, clipText.toString());
            updateLogicProfileUi();
            statusView.setText(ok ? "\u72b6\u6001\uff1a\u903b\u8f91\u5bfc\u5165\u6210\u529f" : "\u72b6\u6001\uff1a\u526a\u8d34\u677f\u6ca1\u6709\u6709\u6548\u903b\u8f91 JSON");
        });
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        importParams.setMargins(dp(8), 0, 0, 0);
        row.addView(importProfiles, importParams);

        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.setMargins(0, dp(6), 0, 0);
        root.addView(row, rowParams);

        updateLogicProfileUi();
    }

    private void updateLogicProfileUi() {
        if (logicProfileView == null) {
            return;
        }
        logicProfileView.setText("\u5f53\u524d\u903b\u8f91\uff1a" + AppSettings.logicProfileLabel(this));
        if (logicProfileSelector != null) {
            AppSettings.LogicProfileChoice selected = AppSettings.selectedLogicProfileChoice(this);
            refreshLogicProfileSelector(logicProfileSearch == null
                    ? "" : logicProfileSearch.getText().toString());
            updatingLogicProfileSelector = true;
            int selectedIndex = 0;
            for (int i = 0; i < visibleLogicProfiles.size(); i++) {
                if (visibleLogicProfiles.get(i).id.equals(selected.id)) {
                    selectedIndex = i;
                    break;
                }
            }
            logicProfileSelector.setSelection(selectedIndex, false);
            updatingLogicProfileSelector = false;
        }
    }

    private void refreshLogicProfileSelector(String query) {
        if (logicProfileAdapter == null) {
            return;
        }
        boolean wasUpdating = updatingLogicProfileSelector;
        updatingLogicProfileSelector = true;
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visibleLogicProfiles.clear();
        for (AppSettings.LogicProfileChoice choice : AppSettings.logicProfileChoices(this)) {
            if (normalizedQuery.isEmpty()
                    || choice.name.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                    || choice.id.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                visibleLogicProfiles.add(choice);
            }
        }
        logicProfileAdapter.clear();
        for (AppSettings.LogicProfileChoice choice : visibleLogicProfiles) {
            logicProfileAdapter.add(choice.label);
        }
        logicProfileAdapter.notifyDataSetChanged();
        updatingLogicProfileSelector = wasUpdating;
    }

    private void showLogicProfileDropdown() {
        if (logicProfileSelector == null) {
            return;
        }
        refreshLogicProfileSelector(logicProfileSearch == null
                ? "" : logicProfileSearch.getText().toString());
        logicProfileSelector.performClick();
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

    private void addNoteModelControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("音符识别模型");
        title.setTextColor(Color.rgb(20, 24, 32));
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        noteModelView = new TextView(this);
        noteModelView.setTextColor(Color.rgb(45, 52, 64));
        noteModelView.setTextSize(15f);
        noteModelView.setGravity(Gravity.CENTER);
        root.addView(noteModelView, matchWrap());

        Button cycle = new Button(this);
        cycle.setText("切换音符模型");
        cycle.setAllCaps(false);
        cycle.setOnClickListener(v -> {
            int mode = AppSettings.nextNoteModelMode(this);
            updateNoteModelUi();
            statusView.setText("状态：音符模型已切换为 "
                    + AppSettings.noteModelLabel(mode)
                    + "，下次启动生效");
        });
        root.addView(cycle, buttonParams());

        updateNoteModelUi();
    }

    private void updateNoteModelUi() {
        if (noteModelView == null) {
            return;
        }
        int mode = AppSettings.getNoteModelMode(this);
        noteModelView.setText("当前模型：" + AppSettings.noteModelLabel(mode));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        captureRequestInFlight = false;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "\u81ea\u52a8\u6388\u6743\u672a\u5b8c\u6210\uff0c\u8bf7\u624b\u52a8\u6388\u6743\u5355\u5e94\u7528\u5f55\u5c4f", Toast.LENGTH_LONG).show();
            statusView.setText("状态：录屏授权已取消");
            return;
        }

        Intent service = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_LAUNCH_CAPTURE_TARGET, true);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        Toast.makeText(this, "\u5f55\u5c4f\u6388\u6743\u5b8c\u6210\uff0c\u91c7\u96c6\u5df2\u6062\u590d", Toast.LENGTH_LONG).show();
        statusView.setText("状态：已启动，正在切换到录屏目标");
    }

    /** Opens the same app that the user selected as the intended capture target. */
    private void launchCaptureTarget() {
        String packageName = AppSettings.captureTargetPackage(this);
        if (packageName.isEmpty() || isFinishing() || isDestroyed()) {
            return;
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            Toast.makeText(this, "\u65e0\u6cd5\u542f\u52a8\u5df2\u9009\u76ee\u6807\uff0c\u8bf7\u66f4\u6539\u5f55\u5c4f\u76ee\u6807", Toast.LENGTH_LONG).show();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(launchIntent);
        } catch (RuntimeException error) {
            Toast.makeText(this, "\u542f\u52a8\u5df2\u9009\u76ee\u6807\u5931\u8d25\uff0c\u8bf7\u624b\u52a8\u5207\u6362", Toast.LENGTH_LONG).show();
        }
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
