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
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import com.pjsk.autoplayer.settings.DebugDisplayController;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;
    private static final int MAX_VISIBLE_LOGIC_PROFILES = 120;
    public static final String EXTRA_AUTO_REAUTHORIZE = "autoReauthorize";
    public static final String EXTRA_OPEN_LOGIC_CHART_PICKER = "openLogicChartPicker";

    private static final int COLOR_PAGE = Color.rgb(244, 247, 251);
    private static final int COLOR_SURFACE = Color.rgb(255, 255, 255);
    private static final int COLOR_TEXT = Color.rgb(28, 38, 52);
    private static final int COLOR_MUTED = Color.rgb(101, 114, 130);
    private static final int COLOR_BORDER = Color.rgb(220, 228, 238);
    private static final int COLOR_PRIMARY = Color.rgb(43, 169, 156);
    private static final int COLOR_DANGER = Color.rgb(222, 93, 106);

    private TextView statusView;
    private TextView overlayStatusView;
    private TextView calibrationValueView;
    private TextView touchMappingView;
    private TextView noteModelView;
    private TextView customOverlayActionView;
    private Switch overlayVisibilitySwitch;
    private Switch overlayCollapseSwitch;
    private Switch overlayParametersSwitch;
    private Switch overlayDebugSwitch;
    private Switch overlayRecordingSwitch;
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
    private Spinner logicSongSelector;
    private Spinner logicDifficultySelector;
    private ArrayAdapter<String> logicSongAdapter;
    private ArrayAdapter<String> logicDifficultyAdapter;
    private final List<String> logicSongIds = new ArrayList<>();
    private final List<String> logicDifficulties = new ArrayList<>();
    private boolean updatingLogicChartSelectors;
    private boolean updatingCalibrationUi;
    private boolean updatingOverlayRuntimeSwitches;
    private boolean captureRequestInFlight;
    private LinearLayout runPage;
    private LinearLayout logicPage;
    private LinearLayout debugPage;
    private Button runPageButton;
    private Button logicPageButton;
    private Button debugPageButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(COLOR_PAGE);
        getWindow().setNavigationBarColor(COLOR_PAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int visibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(visibility);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(18) + statusBarHeight(), dp(16), dp(24));
        root.setBackgroundColor(COLOR_PAGE);

        TextView title = new TextView(this);
        title.setText("PJSK AUTO");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, 0, 0, dp(6));
        root.addView(title, titleParams);

        statusView = new TextView(this);
        statusView.setText("状态：未启动");
        statusView.setTextColor(COLOR_MUTED);
        statusView.setTextSize(14f);
        statusView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(12), dp(9), dp(12), dp(9));
        statusView.setBackground(makeRoundedBackground(COLOR_SURFACE, COLOR_BORDER, 10));
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.setMargins(0, 0, 0, dp(10));
        root.addView(statusView, statusParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        Button start = new Button(this);
        start.setText("开始运行");
        start.setAllCaps(false);
        start.setTag("primary");
        start.setOnClickListener(v -> requestCapture());
        actionRow.addView(start, actionButtonParams(true));

        Button stop = new Button(this);
        stop.setText("停止运行");
        stop.setAllCaps(false);
        stop.setTag("danger");
        stop.setOnClickListener(v -> requestStop());
        actionRow.addView(stop, actionButtonParams(false));
        LinearLayout.LayoutParams actionRowParams = matchWrap();
        actionRowParams.setMargins(0, 0, 0, dp(4));
        root.addView(actionRow, actionRowParams);

        LinearLayout pageTabs = new LinearLayout(this);
        pageTabs.setOrientation(LinearLayout.HORIZONTAL);
        pageTabs.setGravity(Gravity.CENTER_VERTICAL);
        runPageButton = createPageTab("运行", 0);
        logicPageButton = createPageTab("逻辑", 1);
        debugPageButton = createPageTab("调试", 2);
        pageTabs.addView(runPageButton, pageTabParams(true));
        pageTabs.addView(logicPageButton, pageTabParams(true));
        pageTabs.addView(debugPageButton, pageTabParams(false));
        LinearLayout.LayoutParams pageTabsParams = matchWrap();
        pageTabsParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(pageTabs, pageTabsParams);

        runPage = createPage();
        logicPage = createPage();
        debugPage = createPage();
        root.addView(runPage, matchWrap());
        root.addView(logicPage, matchWrap());
        root.addView(debugPage, matchWrap());

        addCaptureTargetControls(runPage);

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
        runPage.addView(previewSwitch, switchParams);

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
        runPage.addView(noClickSwitch, noClickParams);

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
        logicPage.addView(autoSoloSwitch, autoSoloParams);

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
        logicPage.addView(logicPlaySwitch, logicPlayParams);

        addLogicProfileControls(logicPage);

        addCalibrationControls(debugPage);
        addTouchMappingControls(debugPage);
        addNoteModelControls(debugPage);
        addOverlayCustomActionControls(debugPage);
        addOverlayRuntimeControls(debugPage);

        overlayStatusView = new TextView(this);
        overlayStatusView.setTextSize(13f);
        overlayStatusView.setTextColor(COLOR_MUTED);
        overlayStatusView.setGravity(Gravity.START);
        LinearLayout.LayoutParams overlayStatusParams = matchWrap();
        overlayStatusParams.setMargins(0, dp(12), 0, dp(4));
        debugPage.addView(overlayStatusView, overlayStatusParams);

        Button overlay = new Button(this);
        overlay.setText("开启悬浮窗权限");
        overlay.setAllCaps(false);
        overlay.setOnClickListener(v -> openOverlaySettings());
        debugPage.addView(overlay, buttonParams());

        TextView hint = new TextView(this);
        hint.setText("运行后切到目标界面，左上角显示状态；识别预览会抽样显示画面、识别框、处理 FPS 和丢帧数。");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(13f);
        hint.setGravity(Gravity.START);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.setMargins(0, dp(10), 0, 0);
        runPage.addView(hint, hintParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(COLOR_PAGE);
        styleInteractiveViews(root);
        setActivePage(0);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        if (getIntent().getBooleanExtra(EXTRA_AUTO_REAUTHORIZE, false)) {
            getIntent().removeExtra(EXTRA_AUTO_REAUTHORIZE);
            root.post(this::showReauthorizationRequired);
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_LOGIC_CHART_PICKER, false)) {
            getIntent().removeExtra(EXTRA_OPEN_LOGIC_CHART_PICKER);
            root.post(() -> openLogicChartPicker(true));
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
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_LOGIC_CHART_PICKER, false)) {
            intent.removeExtra(EXTRA_OPEN_LOGIC_CHART_PICKER);
            openLogicChartPicker(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateOverlayStatus();
        if (previewSwitch != null) {
            previewSwitch.setChecked(AppSettings.isPreviewEnabled(this));
        }
        updateOverlayRuntimeStateUi();
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
        updateLogicChartUi();
        updateCalibrationUi();
        updateTouchMappingUi();
        updateNoteModelUi();
        updateOverlayCustomActionUi();
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
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        captureTargetView = new TextView(this);
        styleReadout(captureTargetView);
        LinearLayout.LayoutParams captureTargetParams = matchWrap();
        captureTargetParams.setMargins(0, 0, 0, dp(6));
        root.addView(captureTargetView, captureTargetParams);

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


    /** Chart selection is intentionally backed by a small filename-only index. */
    private void addLogicProfileControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("\u903b\u8f91\u8c31\u9762");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        logicProfileView = new TextView(this);
        styleReadout(logicProfileView);
        LinearLayout.LayoutParams profileParams = matchWrap();
        profileParams.setMargins(0, 0, 0, dp(6));
        root.addView(logicProfileView, profileParams);

        TextView directory = new TextView(this);
        directory.setText("\u8c31\u9762\u76ee\u5f55\uff1a" + AppSettings.logicProfilesDirectory(this).getAbsolutePath());
        directory.setTextColor(COLOR_MUTED);
        directory.setTextSize(12f);
        directory.setGravity(Gravity.CENTER);
        root.addView(directory, matchWrap());

        TextView songLabel = new TextView(this);
        songLabel.setText("\u5e8f\u53f7");
        songLabel.setTextColor(COLOR_MUTED);
        songLabel.setTextSize(13f);
        LinearLayout.LayoutParams songLabelParams = matchWrap();
        songLabelParams.setMargins(0, dp(8), 0, dp(2));
        root.addView(songLabel, songLabelParams);

        logicSongSelector = new Spinner(this);
        logicSongAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        logicSongAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logicSongSelector.setAdapter(logicSongAdapter);
        logicSongSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingLogicChartSelectors || position < 0 || position >= logicSongIds.size()) {
                    return;
                }
                refreshLogicDifficultySelector(logicSongIds.get(position), true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(logicSongSelector, matchWrap());

        TextView difficultyLabel = new TextView(this);
        difficultyLabel.setText("\u96be\u5ea6");
        difficultyLabel.setTextColor(COLOR_MUTED);
        difficultyLabel.setTextSize(13f);
        LinearLayout.LayoutParams difficultyLabelParams = matchWrap();
        difficultyLabelParams.setMargins(0, dp(8), 0, dp(2));
        root.addView(difficultyLabel, difficultyLabelParams);

        logicDifficultySelector = new Spinner(this);
        logicDifficultyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        logicDifficultyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logicDifficultySelector.setAdapter(logicDifficultyAdapter);
        logicDifficultySelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingLogicChartSelectors || position < 0 || position >= logicDifficulties.size()) {
                    return;
                }
                String songId = selectedLogicSongId();
                if (AppSettings.selectLogicChart(MainActivity.this, songId, logicDifficulties.get(position))) {
                    updateLogicChartUi();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(logicDifficultySelector, matchWrap());

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("\u626b\u63cf\u5e76\u5237\u65b0\u8c31\u9762\u7d22\u5f15");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> {
            int count = AppSettings.refreshLogicChartIndex(this);
            refreshLogicChartSelectors();
            statusView.setText(count > 0
                    ? "\u72b6\u6001\uff1a\u5df2\u5237\u65b0 " + count + " \u4e2a\u8c31\u9762\u5e8f\u53f7"
                    : "\u72b6\u6001\uff1a\u672a\u627e\u5230\u7b26\u5408\u547d\u540d\u89c4\u5219\u7684\u8c31\u9762\u6587\u4ef6");
        });
        buttonRow.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button apply = new Button(this);
        apply.setText("\u5e94\u7528\u5f53\u524d\u8c31\u9762");
        apply.setAllCaps(false);
        apply.setOnClickListener(v -> {
            String songId = selectedLogicSongId();
            String difficulty = selectedLogicDifficulty();
            if (AppSettings.selectLogicChart(this, songId, difficulty)) {
                updateLogicChartUi();
                statusView.setText("\u72b6\u6001\uff1a\u5df2\u5e94\u7528\u8c31\u9762 " + songId + " " + difficulty);
            }
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        applyParams.setMargins(dp(8), 0, 0, 0);
        buttonRow.addView(apply, applyParams);

        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.setMargins(0, dp(8), 0, 0);
        root.addView(buttonRow, rowParams);
        refreshLogicChartSelectors();
    }

    private void refreshLogicChartSelectors() {
        if (logicSongAdapter == null || logicDifficultyAdapter == null) {
            return;
        }
        updatingLogicChartSelectors = true;
        logicSongIds.clear();
        logicSongIds.addAll(AppSettings.logicChartSongIds(this));
        logicSongAdapter.clear();
        for (String songId : logicSongIds) {
            logicSongAdapter.add(songId);
        }
        logicSongAdapter.notifyDataSetChanged();

        String selectedSongId = AppSettings.selectedLogicChartSongId(this);
        int songIndex = Math.max(0, logicSongIds.indexOf(selectedSongId));
        if (logicSongIds.isEmpty()) {
            logicDifficulties.clear();
            logicDifficultyAdapter.clear();
            logicDifficultyAdapter.notifyDataSetChanged();
            updatingLogicChartSelectors = false;
            updateLogicChartUi();
            return;
        }
        logicSongSelector.setSelection(songIndex, false);
        refreshLogicDifficultySelector(logicSongIds.get(songIndex), false);
        updatingLogicChartSelectors = false;
        updateLogicChartUi();
    }

    private void refreshLogicDifficultySelector(String songId, boolean selectCurrent) {
        if (logicDifficultyAdapter == null) {
            return;
        }
        boolean wasUpdating = updatingLogicChartSelectors;
        updatingLogicChartSelectors = true;
        logicDifficulties.clear();
        logicDifficulties.addAll(AppSettings.logicChartDifficulties(this, songId));
        logicDifficultyAdapter.clear();
        for (String difficulty : logicDifficulties) {
            logicDifficultyAdapter.add(difficulty.toUpperCase(Locale.ROOT));
        }
        logicDifficultyAdapter.notifyDataSetChanged();
        int difficultyIndex = logicDifficulties.indexOf(AppSettings.selectedLogicChartDifficulty(this));
        if (difficultyIndex < 0) {
            difficultyIndex = 0;
        }
        if (!logicDifficulties.isEmpty()) {
            logicDifficultySelector.setSelection(difficultyIndex, false);
            if (selectCurrent) {
                AppSettings.selectLogicChart(this, songId, logicDifficulties.get(difficultyIndex));
            }
        }
        updatingLogicChartSelectors = wasUpdating;
        updateLogicChartUi();
    }

    private String selectedLogicSongId() {
        int position = logicSongSelector == null ? -1 : logicSongSelector.getSelectedItemPosition();
        return position >= 0 && position < logicSongIds.size()
                ? logicSongIds.get(position)
                : AppSettings.selectedLogicChartSongId(this);
    }

    private String selectedLogicDifficulty() {
        int position = logicDifficultySelector == null ? -1 : logicDifficultySelector.getSelectedItemPosition();
        return position >= 0 && position < logicDifficulties.size()
                ? logicDifficulties.get(position)
                : AppSettings.selectedLogicChartDifficulty(this);
    }

    private void updateLogicChartUi() {
        if (logicProfileView == null) {
            return;
        }
        String songId = AppSettings.selectedLogicChartSongId(this);
        String difficulty = AppSettings.selectedLogicChartDifficulty(this);
        logicProfileView.setText(songId.isEmpty() || difficulty.isEmpty()
                ? "\u5f53\u524d\u8c31\u9762\uff1a\u672a\u9009\u62e9"
                : "\u5f53\u524d\u8c31\u9762\uff1a" + songId + "  " + difficulty.toUpperCase(Locale.ROOT));
    }

    /** Opens the exact same validated sequence/difficulty selectors used by the main screen. */
    private void openLogicChartPicker(boolean openSongDropdown) {
        setActivePage(1);
        refreshLogicChartSelectors();
        if (openSongDropdown && logicSongSelector != null && !logicSongIds.isEmpty()) {
            logicSongSelector.postDelayed(logicSongSelector::performClick, 180);
        }
    }

    private void addLegacyLogicProfileControls(LinearLayout root) {
        AppSettings.importLogicProfilesFromDirectory(this);

        TextView title = new TextView(this);
        title.setText("\u903b\u8f91\u6f14\u594f");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        logicProfileView = new TextView(this);
        styleReadout(logicProfileView);
        LinearLayout.LayoutParams logicProfileParams = matchWrap();
        logicProfileParams.setMargins(0, 0, 0, dp(6));
        root.addView(logicProfileView, logicProfileParams);

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
        String selectedId = AppSettings.selectedLogicProfileChoice(this).id;
        visibleLogicProfiles.clear();
        for (AppSettings.LogicProfileChoice choice : AppSettings.logicProfileChoices(this)) {
            if (normalizedQuery.isEmpty()
                    || choice.name.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                    || choice.id.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                if (visibleLogicProfiles.size() < MAX_VISIBLE_LOGIC_PROFILES) {
                    visibleLogicProfiles.add(choice);
                } else if (choice.id.equals(selectedId)) {
                    // Keep the current choice selectable even when an empty
                    // search has thousands of matches.
                    visibleLogicProfiles.remove(visibleLogicProfiles.size() - 1);
                    visibleLogicProfiles.add(choice);
                }
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
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        calibrationValueView = new TextView(this);
        styleReadout(calibrationValueView);
        LinearLayout.LayoutParams calibrationParams = matchWrap();
        calibrationParams.setMargins(0, 0, 0, dp(4));
        root.addView(calibrationValueView, calibrationParams);

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
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        touchMappingView = new TextView(this);
        styleReadout(touchMappingView);
        LinearLayout.LayoutParams touchMappingParams = matchWrap();
        touchMappingParams.setMargins(0, 0, 0, dp(6));
        root.addView(touchMappingView, touchMappingParams);

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
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        noteModelView = new TextView(this);
        styleReadout(noteModelView);
        LinearLayout.LayoutParams noteModelParams = matchWrap();
        noteModelParams.setMargins(0, 0, 0, dp(6));
        root.addView(noteModelView, noteModelParams);

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

    private void addOverlayCustomActionControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("\u5c0f\u7a97\u53e3\u81ea\u5b9a\u4e49\u6309\u94ae");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(2));
        root.addView(title, titleParams);

        customOverlayActionView = new TextView(this);
        styleReadout(customOverlayActionView);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.setMargins(0, 0, 0, dp(6));
        root.addView(customOverlayActionView, actionParams);

        Button cycle = new Button(this);
        cycle.setText("\u5207\u6362\u81ea\u5b9a\u4e49\u64cd\u4f5c");
        cycle.setAllCaps(false);
        cycle.setOnClickListener(v -> {
            int action = AppSettings.nextOverlayCustomAction(this);
            updateOverlayCustomActionUi();
            refreshOverlayCustomAction();
            statusView.setText("\u72b6\u6001\uff1a\u5c0f\u7a97\u53e3\u81ea\u5b9a\u4e49\u6309\u94ae\u5df2\u8bbe\u4e3a "
                    + AppSettings.overlayCustomActionLabel(action));
        });
        root.addView(cycle, buttonParams());

        updateOverlayCustomActionUi();
    }

    private void updateOverlayCustomActionUi() {
        if (customOverlayActionView == null) {
            return;
        }
        customOverlayActionView.setText("\u5f53\u524d\u81ea\u5b9a\u4e49\uff1a"
                + AppSettings.overlayCustomActionLabel(AppSettings.getOverlayCustomAction(this)));
    }

    /** Main-screen counterparts for every runtime control exposed by the overlay. */
    private void addOverlayRuntimeControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("\u5c0f\u7a97\u53e3\u8fd0\u884c\u63a7\u5236");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(12), 0, dp(4));
        root.addView(title, titleParams);

        overlayVisibilitySwitch = addOverlayRuntimeSwitchRow(
                root,
                "\u663e\u793a/\u9690\u85cf\u5c0f\u7a97\u53e3",
                "\u663e\u793a",
                CaptureService.ACTION_TOGGLE_OVERLAY_VISIBILITY);
        overlayCollapseSwitch = addOverlayRuntimeSwitchRow(
                root,
                "\u6298\u53e0/\u5c55\u5f00\u5c0f\u7a97\u53e3",
                "\u6298\u53e0",
                CaptureService.ACTION_TOGGLE_OVERLAY_COLLAPSE);
        overlayParametersSwitch = addOverlayRuntimeSwitchRow(
                root,
                "\u663e\u793a/\u9690\u85cf\u5c0f\u7a97\u53e3\u53c2\u6570",
                "\u53c2\u6570",
                CaptureService.ACTION_TOGGLE_OVERLAY_PARAMETERS);
        addOverlayRuntimeActionRow(root,
                "\u91cd\u7f6e\u8fd0\u884c\u72b6\u6001",
                "\u6267\u884c",
                CaptureService.ACTION_RESET_PLAYBACK);
        overlayDebugSwitch = addOverlayRuntimeSwitchRow(
                root,
                "\u5f00\u5173\u8c03\u8bd5\u663e\u793a",
                "\u8c03\u8bd5",
                CaptureService.ACTION_TOGGLE_DEBUG_DISPLAY);
        overlayRecordingSwitch = addOverlayRuntimeSwitchRow(
                root,
                "\u5f00\u59cb/\u7ed3\u675f\u5f55\u5c4f",
                "\u5f55\u5c4f",
                CaptureService.ACTION_TOGGLE_SCREEN_RECORDING);
        updateOverlayRuntimeStateUi();
    }

    private Switch addOverlayRuntimeSwitchRow(
            LinearLayout root,
            String label,
            String switchLabel,
            String action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> toggleOverlayRuntimeAction(action));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        buttonParams.setMargins(0, dp(7), dp(6), 0);
        row.addView(button, buttonParams);

        Switch toggle = new Switch(this);
        toggle.setTag("overlay-toggle");
        toggle.setText(switchLabel);
        toggle.setGravity(Gravity.CENTER);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingOverlayRuntimeSwitches) {
                toggleOverlayRuntimeAction(action);
            }
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(98), dp(46));
        toggleParams.setMargins(0, dp(7), 0, 0);
        row.addView(toggle, toggleParams);
        root.addView(row, matchWrap());
        return toggle;
    }

    private void addOverlayRuntimeActionRow(
            LinearLayout root,
            String label,
            String actionLabel,
            String action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> toggleOverlayRuntimeAction(action));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        buttonParams.setMargins(0, dp(7), dp(6), 0);
        row.addView(button, buttonParams);

        TextView actionView = new TextView(this);
        actionView.setText(actionLabel);
        actionView.setTextColor(COLOR_MUTED);
        actionView.setTextSize(12f);
        actionView.setGravity(Gravity.CENTER);
        actionView.setBackground(makeRoundedBackground(
                Color.rgb(246, 250, 252), COLOR_BORDER, 8));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(98), dp(46));
        actionParams.setMargins(0, dp(7), 0, 0);
        row.addView(actionView, actionParams);
        root.addView(row, matchWrap());
    }

    private void toggleOverlayRuntimeAction(String action) {
        if (!CaptureService.isRunning()) {
            applyOverlayStartupPreference(action);
            updateOverlayRuntimeStateUi();
            return;
        }
        startService(new Intent(this, CaptureService.class).setAction(action));
        runPage.postDelayed(this::updateOverlayRuntimeStateUi, 180L);
    }

    private void updateOverlayRuntimeStateUi() {
        boolean hidden = AppSettings.isOverlayHidden(this);
        boolean collapsed = AppSettings.isOverlayCollapsed(this);
        boolean parametersVisible = AppSettings.isOverlayParametersVisible(this);
        boolean debugEnabled = AppSettings.isDebugDisplayEnabled(this);
        updatingOverlayRuntimeSwitches = true;
        setOverlayRuntimeSwitch(overlayVisibilitySwitch, !hidden, hidden
                ? Color.rgb(190, 92, 70) : Color.rgb(33, 133, 91));
        setOverlayRuntimeSwitch(overlayCollapseSwitch, collapsed, collapsed
                ? Color.rgb(184, 126, 55) : COLOR_MUTED);
        setOverlayRuntimeSwitch(overlayParametersSwitch, parametersVisible, parametersVisible
                ? Color.rgb(33, 133, 91) : COLOR_MUTED);
        setOverlayRuntimeSwitch(overlayDebugSwitch, debugEnabled, debugEnabled
                ? Color.rgb(33, 133, 91) : COLOR_MUTED);
        setOverlayRuntimeSwitch(overlayRecordingSwitch, CaptureService.isScreenRecording(),
                CaptureService.isScreenRecording() ? Color.rgb(222, 93, 106) : COLOR_MUTED);
        updatingOverlayRuntimeSwitches = false;
    }

    private void setOverlayRuntimeSwitch(Switch control, boolean checked, int accent) {
        if (control == null) {
            return;
        }
        control.setChecked(checked);
        control.setTextColor(accent);
    }

    /** Stores meaningful overlay choices even before the capture service exists. */
    private void applyOverlayStartupPreference(String action) {
        if (CaptureService.ACTION_TOGGLE_OVERLAY_VISIBILITY.equals(action)) {
            boolean hidden = !AppSettings.isOverlayHidden(this);
            AppSettings.setOverlayHidden(this, hidden);
            statusView.setText(hidden
                    ? "\u72b6\u6001\uff1a\u5df2\u8bbe\u4e3a\u542f\u52a8\u540e\u9690\u85cf\u5c0f\u7a97\u53e3"
                    : "\u72b6\u6001\uff1a\u5df2\u8bbe\u4e3a\u542f\u52a8\u540e\u663e\u793a\u5c0f\u7a97\u53e3");
            return;
        }
        if (CaptureService.ACTION_TOGGLE_OVERLAY_COLLAPSE.equals(action)) {
            boolean collapsed = !AppSettings.isOverlayCollapsed(this);
            AppSettings.setOverlayCollapsed(this, collapsed);
            statusView.setText(collapsed
                    ? "\u72b6\u6001\uff1a\u5df2\u8bbe\u4e3a\u542f\u52a8\u540e\u6298\u53e0\u5c0f\u7a97\u53e3"
                    : "\u72b6\u6001\uff1a\u5df2\u8bbe\u4e3a\u542f\u52a8\u540e\u5c55\u5f00\u5c0f\u7a97\u53e3");
            return;
        }
        if (CaptureService.ACTION_TOGGLE_OVERLAY_PARAMETERS.equals(action)) {
            boolean visible = !AppSettings.isOverlayParametersVisible(this);
            AppSettings.setOverlayParametersVisible(this, visible);
            statusView.setText(visible
                    ? "\u72b6\u6001\uff1a\u5df2\u8bbe\u4e3a\u542f\u52a8\u540e\u663e\u793a\u5c0f\u7a97\u53e3\u53c2\u6570"
                    : "\u72b6\u6001\uff1a\u5df2\u8bbe\u4e3a\u542f\u52a8\u540e\u9690\u85cf\u5c0f\u7a97\u53e3\u53c2\u6570");
            return;
        }
        if (CaptureService.ACTION_TOGGLE_DEBUG_DISPLAY.equals(action)) {
            boolean enabled = !AppSettings.isDebugDisplayEnabled(this);
            AppSettings.setDebugDisplayEnabled(this, enabled);
            statusView.setText("\u72b6\u6001\uff1a\u6b63\u5728\u5e94\u7528\u8c03\u8bd5\u663e\u793a\u8bbe\u7f6e");
            new Thread(() -> {
                boolean success = DebugDisplayController.setEnabled(enabled);
                runOnUiThread(() -> statusView.setText(success
                        ? (enabled
                                ? "\u72b6\u6001\uff1a\u5df2\u5f00\u542f\u8c03\u8bd5\u663e\u793a"
                                : "\u72b6\u6001\uff1a\u5df2\u5173\u95ed\u8c03\u8bd5\u663e\u793a")
                        : "\u72b6\u6001\uff1a\u8c03\u8bd5\u663e\u793a\u8bbe\u7f6e\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 root \u6743\u9650"));
            }, "pjsk-main-debug-display").start();
            return;
        }
        if (CaptureService.ACTION_RESET_PLAYBACK.equals(action)) {
            statusView.setText("\u72b6\u6001\uff1a\u4e0b\u6b21\u542f\u52a8\u5c06\u4ee5\u5e72\u51c0\u521d\u59cb\u72b6\u6001\u8fd0\u884c");
            return;
        }
        statusView.setText("\u72b6\u6001\uff1a\u5f55\u5c4f\u9700\u5728\u91c7\u96c6\u670d\u52a1\u542f\u52a8\u540e\u624d\u80fd\u4f7f\u7528");
    }

    private void refreshOverlayCustomAction() {
        if (!CaptureService.isRunning()) {
            return;
        }
        startService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_REFRESH_OVERLAY));
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

    private void styleInteractiveViews(View view) {
        if (view instanceof Button) {
            styleButton((Button) view);
        } else if (view instanceof Switch) {
            Switch control = (Switch) view;
            if ("overlay-toggle".equals(control.getTag())) {
                control.setTextColor(COLOR_MUTED);
                control.setTextSize(11f);
                control.setMinHeight(0);
                control.setMinimumHeight(0);
                control.setPadding(0, 0, 0, 0);
                control.setBackgroundColor(Color.TRANSPARENT);
                return;
            }
            control.setTextColor(COLOR_TEXT);
            control.setTextSize(14f);
            control.setMinHeight(dp(48));
            control.setPadding(dp(12), dp(6), dp(10), dp(6));
            control.setBackground(makeRoundedBackground(COLOR_SURFACE, COLOR_BORDER, 9));
        } else if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setTextColor(COLOR_TEXT);
            input.setHintTextColor(COLOR_MUTED);
            input.setTextSize(14f);
            input.setPadding(dp(12), dp(4), dp(12), dp(4));
            input.setBackground(makeRoundedBackground(COLOR_SURFACE, COLOR_BORDER, 8));
        } else if (view instanceof Spinner) {
            view.setPadding(dp(10), 0, dp(10), 0);
            view.setBackground(makeRoundedBackground(COLOR_SURFACE, COLOR_BORDER, 8));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                styleInteractiveViews(group.getChildAt(index));
            }
        }
    }

    private LinearLayout createPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(0, dp(4), 0, 0);
        return page;
    }

    private Button createPageTab(String label, int page) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTag(page == 0 ? "tab-active" : "tab");
        button.setOnClickListener(view -> setActivePage(page));
        return button;
    }

    private void setActivePage(int page) {
        if (runPage == null || logicPage == null || debugPage == null) {
            return;
        }
        runPage.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        logicPage.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        debugPage.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        updatePageTab(runPageButton, page == 0);
        updatePageTab(logicPageButton, page == 1);
        updatePageTab(debugPageButton, page == 2);
    }

    private void updatePageTab(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setTag(active ? "tab-active" : "tab");
        styleButton(button);
    }

    private void styleReadout(TextView view) {
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(14f);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(42));
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        view.setBackground(makeRoundedBackground(COLOR_SURFACE, COLOR_BORDER, 8));
    }

    private void styleButton(Button button) {
        Object tag = button.getTag();
        String variant = tag instanceof String ? (String) tag : "default";
        int fill = COLOR_SURFACE;
        int border = COLOR_BORDER;
        int text = COLOR_TEXT;
        if ("primary".equals(variant)) {
            fill = COLOR_PRIMARY;
            border = COLOR_PRIMARY;
            text = Color.WHITE;
        } else if ("danger".equals(variant)) {
            fill = COLOR_DANGER;
            border = COLOR_DANGER;
            text = Color.WHITE;
        } else if ("tab-active".equals(variant)) {
            fill = COLOR_PRIMARY;
            border = COLOR_PRIMARY;
            text = Color.WHITE;
        } else if ("tab".equals(variant)) {
            fill = COLOR_SURFACE;
            border = COLOR_BORDER;
            text = COLOR_MUTED;
        }
        button.setTextColor(text);
        button.setTextSize(14f);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(makeRoundedBackground(fill, border, 8));
    }

    private GradientDrawable makeRoundedBackground(int fillColor, int borderColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), borderColor);
        return background;
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

    private LinearLayout.LayoutParams actionButtonParams(boolean hasRightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(0, 0, hasRightMargin ? dp(8) : 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams pageTabParams(boolean hasRightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.setMargins(0, 0, hasRightMargin ? dp(6) : 0, 0);
        return params;
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId == 0 ? 0 : getResources().getDimensionPixelSize(resourceId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
