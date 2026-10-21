package com.androidres.adapter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.radiobutton.MaterialRadioButton;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int SHIZUKU_PERMISSION_CODE = 1001;
    private String selectedRes;
    private static final String CUSTOM_RES_KEY = "custom_resolutions";
    private static final String HIDDEN_PRESET_KEY = "hidden_presets";
    private static final String RECENT_RES_KEY = "recent_resolutions";
    private static final int RECENT_MAX = 5;
    private static final String[] PRESET_RESOLUTIONS = {
            "1280x720", "1920x1080", "2560x1440", "3840x2160"
    };

    private TextView currentResolutionText;
    private LinearLayout presetGroup;
    private LinearLayout recentGroup;
    private TextView recentLabel;
    private EditText customWidth, customHeight;
    private MaterialCheckBox flipCheckBox;
    private MaterialButton applyBtn, restoreBtn;
    private TextView statusText;

    private SharedPreferences prefs;
    private List<String> allResolutions = new ArrayList<>();
    private String appliedCustomRes;
    private String lastAppliedRes;
    private boolean lastAppliedFlipped;
    private CountDownTimer countdownTimer;
    private AlertDialog countdownDialog;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private int pendingWidth, pendingHeight;
    private boolean pendingRestore = false;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_PERMISSION_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        if (pendingRestore) {
                            pendingRestore = false;
                            executor.execute(() -> {
                                int exitCode = execShizukuProcess("wm size reset");
                                if (exitCode == 0) onRestoreSuccess();
                                else runOnUiThread(() -> {
                                    showStatus("Shizuku 恢复失败 (exit: " + exitCode + ")", false);
                                    setButtonsEnabled(true);
                                });
                            });
                        } else if (pendingWidth > 0 && pendingHeight > 0) {
                            execShizukuApply(pendingWidth, pendingHeight);
                        }
                    } else {
                        showStatus("Shizuku 权限被拒绝", false);
                        setButtonsEnabled(true);
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("res_adapter", MODE_PRIVATE);
        initViews();
        initShizuku();
        rebuildAllResolutions();
        rebuildRadioGroup();
        rebuildRecentGroup();
        refreshCurrentResolution();
    }

    private void initViews() {
        currentResolutionText = findViewById(R.id.currentResolution);
        recentGroup = findViewById(R.id.recentGroup);
        recentLabel = findViewById(R.id.recentLabel);
        presetGroup = findViewById(R.id.presetGroup);
        customWidth = findViewById(R.id.customWidth);
        customHeight = findViewById(R.id.customHeight);
        flipCheckBox = findViewById(R.id.flipCheckBox);
        applyBtn = findViewById(R.id.applyBtn);
        restoreBtn = findViewById(R.id.restoreBtn);
        statusText = findViewById(R.id.statusText);

        TextWatcher customWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                selectedRes = null;
                rebuildRadioGroup();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        customWidth.addTextChangedListener(customWatcher);
        customHeight.addTextChangedListener(customWatcher);

        flipCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            rebuildRadioGroup();
        });

        applyBtn.setOnClickListener(v -> onApplyClick());
        restoreBtn.setOnClickListener(v -> onRestoreClick());
    }

    private void initShizuku() {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener);
        } catch (NoClassDefFoundError ignored) {}
    }

    private void rebuildAllResolutions() {
        allResolutions.clear();
        Set<String> hidden = loadHiddenPresets();
        for (String r : PRESET_RESOLUTIONS) {
            if (!hidden.contains(r)) allResolutions.add(r);
        }
        allResolutions.addAll(loadCustomResolutions());
    }

    private Set<String> loadHiddenPresets() {
        String data = prefs.getString(HIDDEN_PRESET_KEY, "");
        if (data.isEmpty()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(data.split(",")));
    }

    private void saveHiddenPresets(Set<String> hidden) {
        prefs.edit().putString(HIDDEN_PRESET_KEY, TextUtils.join(",", hidden)).apply();
    }

    private List<String> loadCustomResolutions() {
        String data = prefs.getString(CUSTOM_RES_KEY, "");
        if (data.isEmpty()) return new ArrayList<>();
        List<String> list = new ArrayList<>();
        boolean changed = false;
        for (String res : data.split(",")) {
            String[] parts = res.split("x");
            if (parts.length == 2) {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                String normalized = Math.max(a, b) + "x" + Math.min(a, b);
                if (!normalized.equals(res)) changed = true;
                if (!list.contains(normalized)) list.add(normalized);
            }
        }
        if (changed) saveCustomResolutions(list);
        return list;
    }

    private void saveCustomResolutions(List<String> list) {
        prefs.edit().putString(CUSTOM_RES_KEY, TextUtils.join(",", list)).apply();
    }

    private void addCustomResolution(String res) {
        List<String> customs = loadCustomResolutions();
        if (!customs.contains(res)) {
            customs.add(0, res);
            saveCustomResolutions(customs);
            rebuildAllResolutions();
            rebuildRadioGroup();
        }
    }

    private void rebuildRadioGroup() {
        presetGroup.removeAllViews();
        boolean flipped = flipCheckBox.isChecked();

        for (String res : allResolutions) {
            final String resRef = res;

            ViewGroup row = (ViewGroup) getLayoutInflater().inflate(
                    R.layout.radio_row, presetGroup, false);
            MaterialRadioButton button = row.findViewById(R.id.radioButton);
            View deleteBtn = row.findViewById(R.id.deleteBtn);

            button.setText(formatResDisplay(res, flipped));
            button.setTag(res);
            button.setTextSize(16);
            button.setClickable(false);
            button.setFocusable(false);
            button.setChecked(res.equals(selectedRes));

            row.setOnClickListener(v -> {
                selectedRes = resRef;
                rebuildRadioGroup();
            });

            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.delete_title)
                        .setMessage(R.string.delete_confirm)
                        .setPositiveButton("删除", (d, w) -> {
                            deleteResolution(resRef);
                            if (resRef.equals(selectedRes)) selectedRes = null;
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });

            presetGroup.addView(row);
        }

        // Add "custom" radio button
        ViewGroup customRow = (ViewGroup) getLayoutInflater().inflate(
                R.layout.radio_row, presetGroup, false);
        MaterialRadioButton customRadio = customRow.findViewById(R.id.radioButton);
        View customDeleteBtn = customRow.findViewById(R.id.deleteBtn);

        customRadio.setText(R.string.custom_radio);
        customRadio.setTag(null);
        customRadio.setTextSize(16);
        customRadio.setClickable(false);
        customRadio.setFocusable(false);
        customRadio.setChecked(selectedRes == null);

        customRow.setOnClickListener(v -> {
            selectedRes = null;
            rebuildRadioGroup();
        });

        customDeleteBtn.setVisibility(View.GONE);
        presetGroup.addView(customRow);
    }

    private void rebuildRecentGroup() {
        List<String> recents = loadRecentResolutions();
        boolean empty = recents.isEmpty();
        recentGroup.setVisibility(empty ? View.GONE : View.VISIBLE);
        recentLabel.setVisibility(empty ? View.GONE : View.VISIBLE);

        recentGroup.removeAllViews();

        for (String entry : recents) {
            String[] parts = entry.split(":");
            String res = parts[0];
            boolean flipped = parts.length > 1 && "1".equals(parts[1]);

            View row = getLayoutInflater().inflate(R.layout.recent_row, recentGroup, false);
            TextView text = row.findViewById(R.id.resolutionText);
            View deleteBtn = row.findViewById(R.id.deleteBtn);

            String[] wh = res.split("x");
            int w = Integer.parseInt(wh[0]);
            int h = Integer.parseInt(wh[1]);

            if (flipped) {
                text.setText(h + " × " + w + "（翻转）");
            } else {
                text.setText(w + " × " + h);
            }

            row.setOnClickListener(v -> {
                lastAppliedRes = res;
                lastAppliedFlipped = flipped;
                if (flipped) {
                    tryApplyResolution(h, w);
                } else {
                    tryApplyResolution(w, h);
                }
            });

            deleteBtn.setOnClickListener(v -> {
                List<String> list = loadRecentResolutions();
                list.remove(entry);
                saveRecentResolutions(list);
                rebuildRecentGroup();
            });

            recentGroup.addView(row);
        }
    }

    private List<String> loadRecentResolutions() {
        String data = prefs.getString(RECENT_RES_KEY, "");
        if (data.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(data.split(",", -1)));
    }

    private void saveRecentResolutions(List<String> list) {
        prefs.edit().putString(RECENT_RES_KEY, TextUtils.join(",", list)).apply();
    }

    private void addRecentResolution(String res, boolean flipped) {
        String newEntry = res + ":" + (flipped ? "1" : "0");
        List<String> recents = loadRecentResolutions();
        recents.removeIf(e -> e.startsWith(res + ":"));
        recents.add(0, newEntry);
        while (recents.size() > RECENT_MAX) {
            recents.remove(recents.size() - 1);
        }
        saveRecentResolutions(recents);
        rebuildRecentGroup();
    }

    private void deleteResolution(String res) {
        List<String> customs = loadCustomResolutions();
        if (customs.remove(res)) {
            saveCustomResolutions(customs);
        } else {
            Set<String> hidden = loadHiddenPresets();
            hidden.add(res);
            saveHiddenPresets(hidden);
        }
        rebuildAllResolutions();
        rebuildRadioGroup();
    }

    private String formatResDisplay(String res, boolean flipped) {
        String[] parts = res.split("x");
        if (parts.length != 2) return res;
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        return flipped ? b + " × " + a : a + " × " + b;
    }

    private int[] getResFromTag(String tag) {
        String[] parts = tag.split("x");
        int w = Integer.parseInt(parts[0]);
        int h = Integer.parseInt(parts[1]);
        if (flipCheckBox.isChecked()) return new int[]{h, w};
        return new int[]{w, h};
    }

    private void refreshCurrentResolution() {
        executor.execute(() -> {
            String res = readWmSizeDirect();
            if (res == null) {
                try { if (Shizuku.pingBinder()) res = readWmSizeViaShizuku(); }
                catch (Exception ignored) {}
            }
            final String finalRes = res;
            runOnUiThread(() -> {
                currentResolutionText.setText(finalRes != null ? formatResDisplay(finalRes, false) : "无法读取");
            });
        });
    }

    private String readWmSizeDirect() {
        try {
            Process p = Runtime.getRuntime().exec("wm size");
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            p.waitFor();
            return parseWmSizeOutput(sb.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String readWmSizeViaShizuku() {
        String output = execShizukuReadStdout("wm size");
        return output != null ? parseWmSizeOutput(output) : null;
    }

    private String parseWmSizeOutput(String output) {
        String[] lines = output.split("\n");
        String physical = null, override = null;
        for (String line : lines) {
            if (line.contains("Override size:"))
                override = extractSize(line);
            else if (line.contains("Physical size:"))
                physical = extractSize(line);
        }
        if (physical != null) saveOriginalResolutionIfNeeded(physical);
        return override != null ? override : physical;
    }

    private void saveOriginalResolutionIfNeeded(String res) {
        if (prefs.getString("original_resolution", null) == null)
            prefs.edit().putString("original_resolution", res).apply();
    }

    private String extractSize(String line) {
        int idx = line.indexOf(':');
        return idx >= 0 ? line.substring(idx + 1).trim() : null;
    }

    private void onApplyClick() {
        hideKeyboard();
        appliedCustomRes = null;
        lastAppliedFlipped = flipCheckBox.isChecked();

        int width, height;

        if (selectedRes != null) {
            int[] wh = getResFromTag(selectedRes);
            width = wh[0];
            height = wh[1];
        } else {
            try {
                width = Integer.parseInt(customWidth.getText().toString().trim());
                height = Integer.parseInt(customHeight.getText().toString().trim());
            } catch (NumberFormatException e) {
                showStatus("请输入有效的宽度和高度", false);
                return;
            }
            if (width <= 0 || height <= 0) {
                showStatus("请输入有效的宽度和高度", false);
                return;
            }
            if (flipCheckBox.isChecked()) {
                int tmp = width; width = height; height = tmp;
            }
            // Save with larger value first (width >= height), ignoring flip
            int saveW = Math.max(width, height);
            int saveH = Math.min(width, height);
            boolean isPreset = false;
            for (String p : PRESET_RESOLUTIONS) {
                if (p.equals(saveW + "x" + saveH))
                { isPreset = true; break; }
            }
            if (!isPreset) appliedCustomRes = saveW + "x" + saveH;
        }

        lastAppliedRes = Math.max(width, height) + "x" + Math.min(width, height);
        tryApplyResolution(width, height);
    }

    private void tryApplyResolution(int width, int height) {
        setButtonsEnabled(false);
        showStatus("正在应用 " + width + " × " + height + " …", true);

        executor.execute(() -> {
            if (execShell("wm size " + width + "x" + height)
                    || execShell(new String[]{"su", "-c", "wm size " + width + "x" + height})) {
                onApplySuccess(width, height);
                return;
            }
            runOnUiThread(() -> tryShizukuApply(width, height));
        });
    }

    private void tryShizukuApply(int width, int height) {
        try {
            if (!Shizuku.pingBinder()) { showNoMethodDialog(); return; }
            boolean granted;
            try { granted = Shizuku.checkSelfPermission() == 0; }
            catch (Exception e) { granted = false; }
            if (granted) execShizukuApply(width, height);
            else {
                pendingWidth = width;
                pendingHeight = height;
                showStatus("正在请求 Shizuku 权限…", true);
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
            }
        } catch (Exception e) {
            showStatus("Shizuku 异常: " + e.getMessage(), false);
            setButtonsEnabled(true);
        }
    }

    private void execShizukuApply(int width, int height) {
        executor.execute(() -> {
            int exitCode = execShizukuProcess("wm size " + width + "x" + height);
            if (exitCode == 0) onApplySuccess(width, height);
            else runOnUiThread(() -> {
                showStatus("Shizuku 执行失败 (exit: " + exitCode + ")", false);
                setButtonsEnabled(true);
            });
        });
    }

    private int execShizukuProcess(String cmdStr) {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) return -1;
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            IRemoteProcess proc = service.newProcess(
                    new String[]{"sh", "-c", cmdStr}, null, null);
            return proc.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private String execShizukuReadStdout(String cmdStr) {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) return null;
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            IRemoteProcess proc = service.newProcess(
                    new String[]{"sh", "-c", cmdStr}, null, null);
            ParcelFileDescriptor pfd = proc.getInputStream();
            FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            proc.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) { return false; }
    }

    private boolean execShell(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) { return false; }
    }

    private void onApplySuccess(int width, int height) {
        runOnUiThread(() -> {
            showStatus("分辨率已应用，等待确认…", true);
            showCountdownDialog(width, height);
            refreshCurrentResolution();
        });
    }

    private void showCountdownDialog(int width, int height) {
        dismissCountdown();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("分辨率已更改")
                .setMessage(buildCountdownMsg(width, height, 15))
                .setPositiveButton("保留", (d, w) -> {
                    countdownTimer.cancel();
                    if (appliedCustomRes != null && !appliedCustomRes.isEmpty())
                        addCustomResolution(appliedCustomRes);
                    addRecentResolution(lastAppliedRes, lastAppliedFlipped);
                    showStatus("已保留 " + width + " × " + height, true);
                    setButtonsEnabled(true);
                })
                .setNegativeButton("恢复", (d, w) -> {
                    countdownTimer.cancel();
                    appliedCustomRes = null;
                    restoreResolution();
                })
                .setCancelable(false)
                .create();

        countdownTimer = new CountDownTimer(15000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                dialog.setMessage(buildCountdownMsg(width, height, (int) (millisUntilFinished / 1000)));
            }
            @Override
            public void onFinish() {
                dialog.dismiss();
                appliedCustomRes = null;
                restoreResolution();
            }
        };

        countdownDialog = dialog;
        dialog.show();
        countdownTimer.start();
    }

    private String buildCountdownMsg(int width, int height, int sec) {
        return "当前分辨率: " + width + " × " + height + "\n\n" + sec + " 秒后自动恢复原始分辨率";
    }

    private void onRestoreClick() {
        hideKeyboard();
        restoreResolution();
    }

    private void restoreResolution() {
        dismissCountdown();
        setButtonsEnabled(false);
        showStatus("正在恢复原始分辨率…", true);

        executor.execute(() -> {
            if (execShell("wm size reset")
                    || execShell(new String[]{"su", "-c", "wm size reset"})) {
                onRestoreSuccess();
                return;
            }
            runOnUiThread(() -> {
                try {
                    if (!Shizuku.pingBinder()) {
                        showStatus("恢复失败，无可用权限", false);
                        setButtonsEnabled(true);
                        showNoMethodDialog();
                        return;
                    }
                    boolean granted;
                    try { granted = Shizuku.checkSelfPermission() == 0; }
                    catch (Exception e) { granted = false; }
                    if (granted) {
                        executor.execute(() -> {
                            int exitCode = execShizukuProcess("wm size reset");
                            if (exitCode == 0) onRestoreSuccess();
                            else runOnUiThread(() -> {
                                showStatus("Shizuku 恢复失败 (exit: " + exitCode + ")", false);
                                setButtonsEnabled(true);
                            });
                        });
                    } else {
                        showStatus("正在请求 Shizuku 权限…", true);
                        pendingRestore = true;
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
                    }
                } catch (Exception e) {
                    showStatus("恢复异常: " + e.getMessage(), false);
                    setButtonsEnabled(true);
                }
            });
        });
    }

    private void onRestoreSuccess() {
        runOnUiThread(() -> {
            showStatus("已恢复原始分辨率", true);
            setButtonsEnabled(true);
            refreshCurrentResolution();
        });
    }

    private void showNoMethodDialog() {
        new AlertDialog.Builder(this)
                .setTitle("无可用权限")
                .setMessage("无法自动调整分辨率。\n\n" +
                        "选项 1：安装 Shizuku（推荐）\n" +
                        "选项 2：通过 ADB 手动执行\n" +
                        "  adb shell wm size WIDTHxHEIGHT")
                .setPositiveButton("下载 Shizuku", (d, w) ->
                        openUrl("https://github.com/RikkaApps/Shizuku/releases"))
                .setNeutralButton("ADB 帮助", (d, w) -> showAdbGuide())
                .setNegativeButton("关闭", (d, w) -> setButtonsEnabled(true))
                .setOnDismissListener(d -> setButtonsEnabled(true))
                .show();
    }

    private void showAdbGuide() {
        new AlertDialog.Builder(this)
                .setTitle("通过 ADB 手动设置")
                .setMessage("1. 电脑连接设备（无线/USB）\n" +
                        "2. 执行：adb shell wm size WIDTHxHEIGHT\n" +
                        "   例如：adb shell wm size 1920x1080\n\n" +
                        "恢复：adb shell wm size reset")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showStatus("无法打开浏览器", false);
        }
    }

    private void showStatus(String msg, boolean isInfo) {
        statusText.setText(msg);
        statusText.setTextColor(getColor(isInfo ? R.color.primary : R.color.error));
        statusText.setVisibility(TextView.VISIBLE);
    }

    private void setButtonsEnabled(boolean enabled) {
        applyBtn.setEnabled(enabled);
        restoreBtn.setEnabled(enabled);
    }

    private void dismissCountdown() {
        if (countdownTimer != null) { countdownTimer.cancel(); countdownTimer = null; }
        if (countdownDialog != null && countdownDialog.isShowing()) {
            countdownDialog.dismiss();
            countdownDialog = null;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null)
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrentResolution();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissCountdown();
        try { Shizuku.removeRequestPermissionResultListener(permissionListener); }
        catch (NoClassDefFoundError ignored) {}
        executor.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (countdownDialog != null && countdownDialog.isShowing()) return;
        super.onBackPressed();
    }
}
