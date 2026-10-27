package com.marco.bondnotifyhaptics;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 2001;

    private GlassStyle glass;
    private String renderedRules = "";
    private SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private final Map<String, ScanResult> scanResults = new LinkedHashMap<>();

    private LinearLayout root;
    private LinearLayout scanResultsLayout;
    private LinearLayout rulesLayout;
    private TextView statusText;
    private TextView setupText;
    private TextView logText;
    private Button permissionButton;
    private Button notificationAccessButton;
    private Button batteryButton;
    private Button scanButton;
    private Button stopScanButton;
    private Button connectSavedButton;
    private Button disconnectButton;
    private Button testButton;
    private Button addAppButton;
    private Button alarmButton;
    private Button preAlarmButton;
    private Button quietHoursButton;
    private boolean scanning;
    private boolean connected;
    private boolean ready;
    private String generation = "idle";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BondHapticService.ACTION_STATE.equals(intent.getAction())) {
                return;
            }
            connected = intent.getBooleanExtra("connected", false);
            ready = intent.getBooleanExtra("ready", false);
            generation = intent.getStringExtra("generation");
            updateUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        glass = new GlassStyle(this);
        getWindow().setStatusBarColor(glass.base);
        getWindow().setNavigationBarColor(glass.base);
        getWindow().getDecorView().setSystemUiVisibility(glass.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        prefs = BondConfig.prefs(this);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        updateUi();
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 branch; newer Android explicitly uses NOT_EXPORTED.
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BondHapticService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        if (prefs.getBoolean(BondConfig.KEY_ENABLED, false)
                && prefs.getString(BondConfig.KEY_ADDRESS, null) != null
                && hasPhonePermissions()) {
            BondHapticService.start(this);
        }
        updateUi();
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        if (scanning) {
            stopScan();
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateUi();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scrollView.setBackground(glass.backdrop());
        scrollView.setClipToPadding(false);
        scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Bond Notify", 28, true, 0xFF172033);
        root.addView(title, matchWrap());
        title.setPadding(0, 0, 0, dp(16));

        statusText = cardText(16, true);
        root.addView(statusText, matchWrap());
        setupText = cardText(13, false);
        addWithTopMargin(root, setupText, dp(8));

        permissionButton = actionButton("Grant Phone Permissions");
        permissionButton.setOnClickListener(v -> requestPhonePermissions());
        addWithTopMargin(root, permissionButton, dp(10));

        LinearLayout setupRow = row();
        notificationAccessButton = actionButton("Notification Access");
        notificationAccessButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        batteryButton = actionButton("Battery Settings");
        batteryButton.setOnClickListener(v -> openBatterySettings());
        setupRow.addView(notificationAccessButton, weightButton());
        setupRow.addView(batteryButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, setupRow, dp(8));

        addWithTopMargin(root, sectionTitle("Bracelet"), dp(18));
        LinearLayout connectRow = row();
        scanButton = actionButton("Scan");
        scanButton.setOnClickListener(v -> startScan());
        stopScanButton = actionButton("Stop");
        stopScanButton.setOnClickListener(v -> stopScan());
        connectRow.addView(scanButton, weightButton());
        connectRow.addView(stopScanButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, connectRow, dp(6));

        LinearLayout savedRow = row();
        connectSavedButton = actionButton("Connect Saved");
        connectSavedButton.setOnClickListener(v -> BondHapticService.connectSaved(this));
        disconnectButton = actionButton("Stop Service");
        disconnectButton.setOnClickListener(v -> BondHapticService.disconnect(this));
        savedRow.addView(connectSavedButton, weightButton());
        savedRow.addView(disconnectButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, savedRow, dp(6));

        testButton = actionButton("Test Bracelet Haptic");
        testButton.setOnClickListener(v -> BondHapticService.test(this));
        addWithTopMargin(root, testButton, dp(6));

        scanResultsLayout = new LinearLayout(this);
        scanResultsLayout.setOrientation(LinearLayout.VERTICAL);
        addWithTopMargin(root, scanResultsLayout, dp(6));

        addWithTopMargin(root, sectionTitle("Notification Rules"), dp(18));
        addAppButton = actionButton("Add App");
        addAppButton.setOnClickListener(v -> showAddAppDialog());
        addWithTopMargin(root, addAppButton, dp(6));
        rulesLayout = new LinearLayout(this);
        rulesLayout.setOrientation(LinearLayout.VERTICAL);
        addWithTopMargin(root, rulesLayout, dp(6));

        addWithTopMargin(root, sectionTitle("Alarm And Quiet Hours"), dp(18));
        LinearLayout alarmRow = row();
        alarmButton = actionButton("Alarm Pattern");
        alarmButton.setOnClickListener(v -> showAlarmDialog());
        preAlarmButton = actionButton("Pre-Alarm");
        preAlarmButton.setOnClickListener(v -> showPreAlarmDialog());
        alarmRow.addView(alarmButton, weightButton());
        alarmRow.addView(preAlarmButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, alarmRow, dp(6));
        quietHoursButton = actionButton("Quiet Hours");
        quietHoursButton.setOnClickListener(v -> toggleQuietHours());
        addWithTopMargin(root, quietHoursButton, dp(6));

        addWithTopMargin(root, sectionTitle("Latest activity"), dp(18));
        logText = cardText(12, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setMinLines(1);
        addWithTopMargin(root, logText, dp(6));

        setContentView(scrollView);
    }

    private void updateUi() {
        boolean hasPerms = hasPhonePermissions();
        boolean notificationAccess = hasNotificationAccess();
        boolean batteryIgnored = isIgnoringBatteryOptimizations();
        String address = prefs.getString(BondConfig.KEY_ADDRESS, null);
        String name = prefs.getString(BondConfig.KEY_NAME, "saved band");
        String saved = address == null ? "No bracelet selected" : name;
        String service = prefs.getBoolean(BondConfig.KEY_ENABLED, false) ? "Enabled" : "Stopped";
        String state = ready ? "Connected" : connected ? "Connecting" : scanning ? "Searching" : "Disconnected";
        statusText.setText(saved + "\n" + state + "  /  " + service);
        setupText.setText("Notifications " + (notificationAccess ? "ready" : "need access")
                + "   /   Battery " + (batteryIgnored ? "unrestricted" : "optimized"));
        permissionButton.setEnabled(!hasPerms);
        notificationAccessButton.setEnabled(!notificationAccess);
        batteryButton.setEnabled(!batteryIgnored);
        scanButton.setEnabled(hasPerms && !scanning);
        stopScanButton.setEnabled(scanning);
        connectSavedButton.setEnabled(hasPerms && address != null);
        disconnectButton.setEnabled(true);
        testButton.setEnabled(connected || ready);
        preAlarmButton.setText("Pre-Alarm: " + yesNo(BondConfig.preAlarmRule(this).enabled));
        quietHoursButton.setText("Quiet Hours: " + yesNo(prefs.getBoolean(BondConfig.KEY_QUIET_ENABLED, false)));
        logText.setText(prefs.getString(BondConfig.KEY_LAST_LOG, ""));
        renderRules();
    }

    private void renderRules() {
        if (rulesLayout == null) {
            return;
        }
        List<AppRule> rules = BondConfig.loadRules(this);
        StringBuilder signature = new StringBuilder();
        for (AppRule rule : rules) signature.append(rule.packageName).append(ruleSummary(rule));
        String key = signature.toString();
        if (key.equals(renderedRules) && rulesLayout.getChildCount() > 0) return;
        renderedRules = key;
        rulesLayout.removeAllViews();
        if (rules.isEmpty()) {
            TextView empty = text("No apps selected", 13, false, 0xFF667085);
            addWithTopMargin(rulesLayout, empty, dp(4));
            return;
        }
        for (AppRule rule : rules) {
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14), dp(10), dp(8), dp(10));
            item.setBackground(glass.surface(false));
            ImageView icon = new ImageView(this);
            try { icon.setImageDrawable(getPackageManager().getApplicationIcon(rule.packageName)); }
            catch (PackageManager.NameNotFoundException ignored) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, dp(4), 0);
            labels.addView(text(rule.label, 16, true, glass.ink), matchWrap());
            labels.addView(text(rule.pattern.replace('_', ' ') + "  /  " + rule.strength + "%", 13, false, glass.muted), matchWrap());
            item.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            android.widget.Switch enabled = new android.widget.Switch(this);
            enabled.setContentDescription(rule.label + " notifications");
            enabled.setChecked(rule.enabled);
            enabled.setMinHeight(dp(48));
            enabled.setOnCheckedChangeListener((button, checked) -> {
                rule.enabled = checked;
                BondConfig.saveRule(this, rule);
                renderedRules = "";
            });
            item.addView(enabled);
            item.setOnClickListener(v -> showRuleDialog(rule));
            labels.setContentDescription(rule.label + ", " + rule.pattern + ", " + rule.strength + " percent. Edit settings");
            labels.setOnClickListener(v -> showRuleDialog(rule));
            addWithTopMargin(rulesLayout, item, dp(8));
        }
    }

    private boolean hasPhonePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPhonePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null) {
            return false;
        }
        ComponentName name = new ComponentName(this, BondNotificationListener.class);
        return flat.toLowerCase(Locale.US).contains(name.flattenToString().toLowerCase(Locale.US));
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openBatterySettings() {
        try {
            if (!isIgnoringBatteryOptimizations()) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
        } catch (Exception ignored) {
        }
        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!hasPhonePermissions()) {
            requestPhonePermissions();
            return;
        }
        if (adapter == null || !adapter.isEnabled() || adapter.getBluetoothLeScanner() == null) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        scanResults.clear();
        scanResultsLayout.removeAllViews();
        scanning = true;
        adapter.getBluetoothLeScanner().startScan(scanCallback);
        updateUi();
        scanResultsLayout.postDelayed(() -> {
            if (scanning) {
                stopScan();
            }
        }, 15_000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null && hasPhonePermissions()) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        }
        scanning = false;
        updateUi();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                handleScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            updateUi();
        }
    };

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null || !hasPhonePermissions()) {
            return;
        }
        String name = advertisementName(result);
        if (!isBondBandName(name)) {
            return;
        }
        String address = result.getDevice().getAddress();
        if (scanResults.containsKey(address)) {
            return;
        }
        scanResults.put(address, result);
        Button button = quietButton(safeName(name) + "  " + address + "  RSSI " + result.getRssi());
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(v -> {
            stopScan();
            BondHapticService.connectAddress(this, address, safeName(name));
        });
        addWithTopMargin(scanResultsLayout, button, dp(6));
    }

    private String advertisementName(ScanResult result) {
        ScanRecord scanRecord = result.getScanRecord();
        String name = scanRecord == null ? null : scanRecord.getDeviceName();
        if (name == null && result.getDevice() != null && hasPhonePermissions()) {
            try {
                name = result.getDevice().getName();
            } catch (SecurityException ignored) {
            }
        }
        return name;
    }

    private boolean isBondBandName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toUpperCase(Locale.US);
        return normalized.equals("BOND")
                || normalized.equals("BOND 4")
                || normalized.equals("BOND MINI")
                || normalized.equals("BOND MORE")
                || normalized.startsWith("BOND");
    }

    private void showAddAppDialog() {
        List<AppChoice> apps = launchableApps();
        List<AppChoice> filtered = new ArrayList<>(apps);
        LinearLayout layout = dialogLayout();
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search apps");
        search.setTextColor(glass.ink);
        search.setHintTextColor(glass.muted);
        search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(glass.accent));
        layout.addView(search, matchWrap());
        android.widget.ListView list = new android.widget.ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        TextView empty = text("No matching apps", 14, false, glass.muted);
        layout.addView(empty, matchWrap());
        list.setEmptyView(empty);
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            public int getCount() { return filtered.size(); }
            public Object getItem(int position) { return filtered.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View convert, ViewGroup parent) {
                LinearLayout item;
                if (convert == null) {
                    item = row();
                    item.setGravity(Gravity.CENTER_VERTICAL);
                    item.setPadding(dp(4), dp(10), dp(4), dp(10));
                    ImageView icon = new ImageView(MainActivity.this);
                    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    item.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
                    LinearLayout labels = new LinearLayout(MainActivity.this);
                    labels.setOrientation(LinearLayout.VERTICAL);
                    labels.setPadding(dp(12), 0, 0, 0);
                    labels.addView(text("", 15, true, glass.ink), matchWrap());
                    labels.addView(text("", 12, false, glass.muted), matchWrap());
                    item.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                } else item = (LinearLayout) convert;
                AppChoice choice = filtered.get(position);
                if (choice.icon == null) {
                    try { choice.icon = getPackageManager().getApplicationIcon(choice.packageName); }
                    catch (PackageManager.NameNotFoundException ignored) {
                        choice.icon = getPackageManager().getDefaultActivityIcon();
                    }
                }
                ((ImageView) item.getChildAt(0)).setImageDrawable(choice.icon);
                LinearLayout labels = (LinearLayout) item.getChildAt(1);
                ((TextView) labels.getChildAt(0)).setText(choice.label);
                ((TextView) labels.getChildAt(1)).setText(choice.packageName);
                return item;
            }
        };
        list.setAdapter(adapter);
        int height = Math.min(dp(420), getResources().getDisplayMetrics().heightPixels / 2);
        layout.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Add notification app")
                .setView(layout).setNegativeButton("Done", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppChoice choice = filtered.get(position);
            AppRule rule = BondConfig.loadRule(this, choice.packageName);
            rule.label = choice.label;
            BondConfig.saveRule(this, rule);
            updateUi();
            dialog.dismiss();
            showRuleDialog(rule);
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void afterTextChanged(Editable value) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                String query = value.toString().trim().toLowerCase(Locale.ROOT);
                filtered.clear();
                for (AppChoice app : apps) {
                    if (app.label.toLowerCase(Locale.ROOT).contains(query)
                            || app.packageName.toLowerCase(Locale.ROOT).contains(query)) filtered.add(app);
                }
                adapter.notifyDataSetChanged();
            }
        });
        showGlassDialog(dialog);
    }

    private List<AppChoice> launchableApps() {
        List<AppChoice> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ApplicationInfo> infos = getPackageManager().getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo info : infos) {
            if (info == null || info.packageName == null || info.packageName.equals(getPackageName()) || seen.contains(info.packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(getPackageManager());
            if (label == null || label.toString().trim().isEmpty()) {
                continue;
            }
            boolean userApp = (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean hasLauncher = getPackageManager().getLaunchIntentForPackage(info.packageName) != null;
            if (!userApp && !hasLauncher) {
                continue;
            }
            AppChoice choice = new AppChoice();
            choice.packageName = info.packageName;
            choice.label = label == null ? choice.packageName : label.toString();

            apps.add(choice);
            seen.add(choice.packageName);
        }
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    private void showRuleDialog(AppRule rule) {
        editRule(BondConfig.loadRule(this, rule.packageName), 0);
    }

    private void showAlarmDialog() { editRule(BondConfig.alarmRule(this), 1); }

    private void showPreAlarmDialog() { editRule(BondConfig.preAlarmRule(this), 2); }

    private void editRule(AppRule edit, int kind) {
        LinearLayout layout = dialogLayout();
        android.widget.Switch enabled = new android.widget.Switch(this);
        enabled.setText("Enabled");
        enabled.setTextColor(glass.ink);
        enabled.setChecked(edit.enabled);
        enabled.setPadding(0, dp(12), 0, dp(12));
        enabled.setOnCheckedChangeListener((button, checked) -> edit.enabled = checked);
        layout.addView(enabled, matchWrap());

        layout.addView(sectionTitle("Pattern"), matchWrap());
        android.widget.Spinner patterns = new android.widget.Spinner(this);
        String[] names = BondConfig.PATTERNS.clone();
        for (int i = 0; i < names.length; i++) names[i] = names[i].replace('_', ' ');
        android.widget.ArrayAdapter<String> patternAdapter = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, names) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convert, parent);
                view.setTextColor(glass.ink);
                return view;
            }
        };
        patternAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        patterns.setAdapter(patternAdapter);
        for (int i = 0; i < BondConfig.PATTERNS.length; i++)
            if (BondConfig.PATTERNS[i].equals(edit.pattern)) patterns.setSelection(i);
        patterns.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                edit.pattern = BondConfig.PATTERNS[position];
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        layout.addView(patterns, matchWrap());
        addSlider(layout, "Strength", edit.strength, 0, 100, 1, "%", value -> edit.strength = value);
        addSlider(layout, "Pulse duration", edit.durationMs, 25, 2000, 25, " ms", value -> edit.durationMs = value);
        addSlider(layout, "Cooldown", edit.cooldownMs / 1000, 0, kind == 2 ? 3600 : 60, 1,
                " s", value -> edit.cooldownMs = value * 1000);

        layout.addView(sectionTitle("Color"), matchWrap());
        LinearLayout swatches = row();
        int[] colors = {0x37B8FF, 0xFFFFFF, 0xFF3030, 0x36D675, 0xFFD33D, 0xB38CFF, 0xFF2D95};
        String[] colorNames = {"Blue", "White", "Red", "Green", "Yellow", "Violet", "Pink"};
        for (int i = 0; i < colors.length; i++) {
            final int color = colors[i];
            android.widget.RadioButton swatch = new android.widget.RadioButton(this);
            swatch.setContentDescription(colorNames[i]);
            swatch.setTooltipText(colorNames[i]);
            swatch.setMinWidth(0);
            swatch.setMinimumWidth(0);
            swatch.setMinHeight(dp(48));
            swatch.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    color == 0xFFFFFF ? glass.muted : 0xFF000000 | color));
            swatch.setChecked(edit.color == color);
            swatch.setOnClickListener(v -> {
                edit.color = color;
                for (int j = 0; j < swatches.getChildCount(); j++)
                    ((android.widget.RadioButton) swatches.getChildAt(j)).setChecked(swatches.getChildAt(j) == v);
            });
            swatches.addView(swatch, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        layout.addView(swatches, matchWrap());
        android.widget.Switch repeat = new android.widget.Switch(this);
        repeat.setText("Repeat while ringing");
        repeat.setTextColor(glass.ink);
        repeat.setChecked(prefs.getBoolean(BondConfig.KEY_ALARM_REPEAT, true));
        if (kind == 1) layout.addView(repeat, matchWrap());
        Button preview = actionButton("Test pattern");
        preview.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(glass.dark ? 0x307DE3C3 : 0x22096B52), null, null));
        preview.setEnabled(ready);
        preview.setOnClickListener(v -> BondHapticService.haptic(this, new HapticCommand(
                "preview", edit.pattern, edit.strength, edit.durationMs, edit.color, edit.priority)));
        addWithTopMargin(layout, preview, dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(edit.label)
                .setView(scroll)
                .setPositiveButton("Save", (dialog, which) -> {
                    if (kind == 1) BondConfig.saveAlarmRule(this, edit, repeat.isChecked());
                    else if (kind == 2) BondConfig.savePreAlarmRule(this, edit);
                    else BondConfig.saveRule(this, edit);
                    updateUi();
                })
                .setNegativeButton("Cancel", null);
        if (kind == 0) builder.setNeutralButton("Remove", (dialog, which) -> {
            BondConfig.removeRule(this, edit.packageName);
            updateUi();
        });
        showGlassDialog(builder.create());
    }

    private void addSlider(LinearLayout parent, String title, int initial, int min, int max,
                           int step, String unit, java.util.function.IntConsumer changed) {
        TextView label = text(title + "  " + initial + unit, 14, false, glass.ink);
        addWithTopMargin(parent, label, dp(14));
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax((max - min) / step);
        slider.setProgress((Math.max(min, Math.min(max, initial)) - min) / step);
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(glass.accent));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(glass.accent));
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    int value = min + progress * step;
                    label.setText(title + "  " + value + unit);
                    changed.accept(value);
                }
            }
            public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            public void onStopTrackingTouch(android.widget.SeekBar bar) { }
        });
        parent.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void showGlassDialog(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(glass.surface(true));
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(glass.accent);
        }
    }

    private void toggleQuietHours() {
        boolean current = prefs.getBoolean(BondConfig.KEY_QUIET_ENABLED, false);
        prefs.edit()
                .putBoolean(BondConfig.KEY_QUIET_ENABLED, !current)
                .putInt(BondConfig.KEY_QUIET_START, 23 * 60)
                .putInt(BondConfig.KEY_QUIET_END, 7 * 60)
                .apply();
        updateUi();
    }

    private String ruleSummary(AppRule rule) {
        return (rule.enabled ? "Enabled" : "Disabled")
                + "\nPattern: " + rule.pattern
                + "\nStrength: " + rule.strength + "%"
                + "\nDuration: " + rule.durationMs + "ms"
                + "\nCooldown: " + rule.cooldownMs + "ms"
                + "\nColor: #" + BondConfig.colorHex(rule.color);
    }

    private LinearLayout dialogLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(12), dp(6), dp(12), 0);
        return layout;
    }

    private void addDialogButton(LinearLayout layout, String label, Runnable action) {
        Button button = actionButton(label);
        button.setOnClickListener(v -> action.run());
        addWithTopMargin(layout, button, dp(6));
    }

    private void addTwoButtons(LinearLayout layout, String leftLabel, Runnable left, String rightLabel, Runnable right) {
        LinearLayout row = row();
        Button leftButton = actionButton(leftLabel);
        leftButton.setOnClickListener(v -> left.run());
        Button rightButton = actionButton(rightLabel);
        rightButton.setOnClickListener(v -> right.run());
        row.addView(leftButton, weightButton());
        row.addView(rightButton, weightButtonWithLeftMargin());
        addWithTopMargin(layout, row, dp(6));
    }

    private TextView sectionTitle(String text) {
        return text(text, 14, true, 0xFF344054);
    }

    private TextView cardText(int size, boolean bold) {
        TextView text = text("", size, bold, 0xFF172033);
        text.setPadding(dp(14), dp(12), dp(14), dp(12));
        text.setBackground(glass.surface(false));
        return text;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color == 0xFF172033 || color == glass.ink ? glass.ink : glass.muted);
        text.setLetterSpacing(0);
        text.setLineSpacing(dp(3), 1f);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return text;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setTextSize(14);
        button.setLetterSpacing(0);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{glass.muted, glass.accent}));
        button.setBackground(glass.control());
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        return button;
    }

    private Button quietButton(String text) {
        Button button = actionButton(text);
        button.setTextColor(glass.ink);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightButtonWithLeftMargin() {
        LinearLayout.LayoutParams params = weightButton();
        params.leftMargin = dp(8);
        return params;
    }

    private void addWithTopMargin(LinearLayout parent, View view, int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = topMargin;
        parent.addView(view, params);
    }

    private String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "(unnamed)" : name;
    }

    private String yesNo(boolean value) {
        return value ? "ON" : "OFF";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class AppChoice {
        String packageName;
        String label;
        Drawable icon;
    }
}
