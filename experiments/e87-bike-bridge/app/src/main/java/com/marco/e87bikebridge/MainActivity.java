package com.marco.e87bikebridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 8701;
    private static final String LOG_TAG = "E87Bridge";

    private static final UUID QIX_SERVICE_UUID = UUID.fromString("c2e6fd00-e966-1000-8000-bef9c223df6a");
    private static final UUID QIX_NOTIFY_UUID = UUID.fromString("c2e6fd01-e966-1000-8000-bef9c223df6a");
    private static final UUID QIX_WRITE_UUID = UUID.fromString("c2e6fd02-e966-1000-8000-bef9c223df6a");
    private static final UUID QIX_CONTROL_UUID = UUID.fromString("c2e6fd03-e966-1000-8000-bef9c223df6a");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID CSC_MEASUREMENT_UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb");
    private static final UUID SUPER73_SERVICE_UUID = UUID.fromString("00001554-1212-efde-1523-785feabcd123");
    private static final UUID SUPER73_NOTIFY_UUID = UUID.fromString("0000155e-1212-efde-1523-785feabcd123");
    private static final UUID SUPER73_REGISTER_UUID = UUID.fromString("0000155f-1212-efde-1523-785feabcd123");
    private static final UUID SUPER73_REGISTER_ID_UUID = UUID.fromString("00001564-1212-efde-1523-785feabcd123");
    private static final int MAX_RENDERED_SCAN_RESULTS = 25;
    private static final long SCAN_RENDER_INTERVAL_MS = 700;
    private static final String[] KNOWN_BADGE_ADDRESSES = {}; // Select your badge by scan/name; no personal address is shipped.

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> scanResults = new LinkedHashMap<>();
    private final Queue<byte[]> badgeWriteQueue = new ArrayDeque<>();
    private final Queue<BluetoothGattDescriptor> badgeDescriptorQueue = new ArrayDeque<>();

    private SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private BluetoothGatt badgeGatt;
    private BluetoothGatt bikeGatt;
    private BluetoothGattCharacteristic badgeWriteChar;
    private BluetoothGattCharacteristic bikeNotifyChar;
    private BluetoothGattCharacteristic bikeWriteChar;
    private BluetoothGattCharacteristic bikeRegisterIdChar;

    private LinearLayout root;
    private LinearLayout scanResultsLayout;
    private LinearLayout bikeCharsLayout;
    private TextView statusText;
    private TextView badgeText;
    private TextView bikeText;
    private TextView speedText;
    private TextView logText;
    private Button scanButton;
    private Button stopScanButton;
    private Button knownBadgeButton;
    private Button permissionButton;
    private Button disconnectButton;
    private Button sendButton;
    private Button minusButton;
    private Button plusButton;
    private Button wakeLiveScreenButton;
    private Button demoButton;
    private Button stopDemoButton;

    private boolean scanning;
    private boolean autoConnectBadgeScan;
    private boolean scanRenderPending;
    private boolean badgeConnected;
    private boolean badgeReady;
    private boolean bikeConnected;
    private boolean badgeSending;
    private boolean badgeDescriptorWriting;
    private boolean interpretationScreenEntered;
    private boolean interpretationEnabled;
    private boolean super73Bike;
    private boolean badgeDemoRunning;
    private int scanSession;
    private int manualSpeed = 25;
    private int demoSpeed;
    private int demoDirection = 1;
    private long lastSpeedSendMs;
    private long lastScanRenderMs;
    private Integer lastWheelRevs;
    private Integer lastWheelEvent;

    private final Runnable badgeDemoRunnable = new Runnable() {
        @Override
        public void run() {
            runBadgeDemoTick();
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            runOnUiThread(() -> handleScanResult(result));
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                autoConnectBadgeScan = false;
                scanning = false;
                updateUi();
                appendLog("scan failed error=" + errorCode);
            });
        }
    };

    private final BluetoothGattCallback badgeCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            runOnUiThread(() -> {
                badgeConnected = newState == BluetoothProfile.STATE_CONNECTED;
                badgeReady = false;
                badgeWriteChar = null;
                if (!badgeConnected && badgeDemoRunning) {
                    stopBadgeDemo();
                }
                updateUi();
                appendLog("badge state=" + newState + " status=" + status);
            });
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sleep(250);
                safeDiscoverServices(gatt);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(QIX_SERVICE_UUID);
            if (service != null) {
                badgeWriteChar = service.getCharacteristic(QIX_WRITE_UUID);
                badgeReady = badgeWriteChar != null;
                if (badgeReady) {
                    setupBadgeNotifications(gatt, service);
                    handler.postDelayed(() -> {
                        queueBadgePacket(buildBindPacket());
                        ensureBadgeLiveScreen();
                        handler.postDelayed(() -> sendSpeed(manualSpeed, true), 900);
                    }, 500);
                }
            }
            runOnUiThread(() -> {
                updateUi();
                appendLog("badge services status=" + status + " ready=" + badgeReady);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            handleBadgeNotify(characteristic.getUuid(), value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleBadgeNotify(characteristic.getUuid(), value);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            runOnUiThread(() -> appendLog("badge write " + shortUuid(characteristic.getUuid()) + " status=" + status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            badgeDescriptorWriting = false;
            runOnUiThread(() -> appendLog("badge cccd "
                    + shortUuid(descriptor.getCharacteristic().getUuid()) + " status=" + status));
            handler.postDelayed(() -> writeNextBadgeDescriptor(gatt), 80);
        }
    };

    private final BluetoothGattCallback bikeCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            runOnUiThread(() -> {
                bikeConnected = newState == BluetoothProfile.STATE_CONNECTED;
                updateUi();
                appendLog("bike state=" + newState + " status=" + status);
            });
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sleep(250);
                safeDiscoverServices(gatt);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            setupSuper73Bike(gatt);
            runOnUiThread(() -> {
                renderBikeCharacteristics(gatt);
                updateUi();
                appendLog("bike services status=" + status);
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleBikeBytes(characteristic.getUuid(), characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleBikeBytes(characteristic.getUuid(), characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleBikeBytes(characteristic.getUuid(), value);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            runOnUiThread(() -> appendLog("bike write " + shortUuid(characteristic.getUuid())
                    + " status=" + status + " " + toHex(characteristic.getValue())));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            runOnUiThread(() -> appendLog("bike cccd "
                    + shortUuid(descriptor.getCharacteristic().getUuid()) + " status=" + status));
            if (status == BluetoothGatt.GATT_SUCCESS && SUPER73_NOTIFY_UUID.equals(descriptor.getCharacteristic().getUuid())) {
                handler.postDelayed(MainActivity.this::requestSuper73Settings, 150);
                handler.postDelayed(MainActivity.this::requestSuper73TotalDistance, 350);
                handler.postDelayed(MainActivity.this::requestSuper73RideData, 650);
                handler.postDelayed(MainActivity.this::requestSuper73BatteryData, 950);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        updateUi();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        disconnectAll();
        super.onDestroy();
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
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(0xFFF4F7F5);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("E87 Bike Bridge", 26, true, 0xFF16352E), matchWrap());
        TextView subtitle = text("BLE e-bike speed to E87/Q87 badge live text", 15, false, 0xFF52635E);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle, matchWrap());

        statusText = cardText(14, true);
        root.addView(statusText, matchWrap());
        badgeText = cardText(13, false);
        addWithTopMargin(root, badgeText, dp(8));
        bikeText = cardText(13, false);
        addWithTopMargin(root, bikeText, dp(8));
        speedText = cardText(24, true);
        speedText.setGravity(Gravity.CENTER);
        addWithTopMargin(root, speedText, dp(8));

        permissionButton = button("Grant BLE Permissions");
        permissionButton.setOnClickListener(v -> requestBlePermissions());
        addWithTopMargin(root, permissionButton, dp(10));

        LinearLayout scanRow = row();
        scanButton = button("Scan");
        scanButton.setOnClickListener(v -> startScan());
        stopScanButton = button("Stop");
        stopScanButton.setOnClickListener(v -> stopScan());
        scanRow.addView(scanButton, weightButton());
        scanRow.addView(stopScanButton, weightButtonLeft());
        addWithTopMargin(root, scanRow, dp(8));

        knownBadgeButton = button("Connect Known Badge");
        knownBadgeButton.setOnClickListener(v -> connectKnownBadge());
        addWithTopMargin(root, knownBadgeButton, dp(6));

        disconnectButton = button("Disconnect");
        disconnectButton.setOnClickListener(v -> disconnectAll());
        addWithTopMargin(root, disconnectButton, dp(8));

        root.addView(section("Manual Speed Test"), matchWrap());
        LinearLayout speedRow = row();
        minusButton = button("- 1");
        minusButton.setOnClickListener(v -> {
            manualSpeed = Math.max(0, manualSpeed - 1);
            sendSpeed(manualSpeed, true);
            updateUi();
        });
        sendButton = button("Send Speed");
        sendButton.setOnClickListener(v -> sendSpeed(manualSpeed, true));
        plusButton = button("+ 1");
        plusButton.setOnClickListener(v -> {
            manualSpeed = Math.min(99, manualSpeed + 1);
            sendSpeed(manualSpeed, true);
            updateUi();
        });
        speedRow.addView(minusButton, weightButton());
        speedRow.addView(sendButton, weightButtonLeft());
        speedRow.addView(plusButton, weightButtonLeft());
        addWithTopMargin(root, speedRow, dp(6));

        wakeLiveScreenButton = button("Wake Badge Live Screen");
        wakeLiveScreenButton.setOnClickListener(v -> wakeBadgeLiveScreen());
        addWithTopMargin(root, wakeLiveScreenButton, dp(6));

        LinearLayout demoRow = row();
        demoButton = button("Start Badge Demo");
        demoButton.setOnClickListener(v -> startBadgeDemo());
        stopDemoButton = button("Stop Demo");
        stopDemoButton.setOnClickListener(v -> stopBadgeDemo());
        demoRow.addView(demoButton, weightButton());
        demoRow.addView(stopDemoButton, weightButtonLeft());
        addWithTopMargin(root, demoRow, dp(6));

        root.addView(section("Scan Results"), matchWrap());
        scanResultsLayout = new LinearLayout(this);
        scanResultsLayout.setOrientation(LinearLayout.VERTICAL);
        addWithTopMargin(root, scanResultsLayout, dp(4));

        root.addView(section("Bike Characteristics"), matchWrap());
        bikeCharsLayout = new LinearLayout(this);
        bikeCharsLayout.setOrientation(LinearLayout.VERTICAL);
        addWithTopMargin(root, bikeCharsLayout, dp(4));

        root.addView(section("Log"), matchWrap());
        logText = cardText(12, false);
        addWithTopMargin(root, logText, dp(4));

        setContentView(scrollView);
    }

    private void updateUi() {
        statusText.setText("Permissions: " + (hasBlePermissions() ? "ok" : "needed")
                + "    Scanning: " + (scanning ? "yes" : "no")
                + "    Auto badge: " + (autoConnectBadgeScan ? "yes" : "no")
                + "    Demo: " + (badgeDemoRunning ? "yes" : "no"));
        String badgeAddress = prefs.getString("badge_address", "none");
        String bikeAddress = prefs.getString("bike_address", "none");
        badgeText.setText("Badge: " + badgeAddress
                + "\nconnected=" + badgeConnected + " ready=" + badgeReady
                + "\nknown=" + knownBadgeAddressesText());
        bikeText.setText("Bike: " + bikeAddress
                + "\nconnected=" + bikeConnected
                + "\ntype=" + (super73Bike ? "Super73/S75" : "generic")
                + "\nnotify=" + (bikeNotifyChar == null ? "none" : bikeNotifyChar.getUuid()));
        speedText.setText(String.format(Locale.US, "%02d km/h", manualSpeed));

        boolean permitted = hasBlePermissions();
        permissionButton.setEnabled(!permitted);
        scanButton.setEnabled(permitted && !scanning);
        stopScanButton.setEnabled(scanning);
        knownBadgeButton.setEnabled(permitted && !badgeConnected && !autoConnectBadgeScan);
        sendButton.setEnabled(badgeReady);
        minusButton.setEnabled(badgeReady);
        plusButton.setEnabled(badgeReady);
        wakeLiveScreenButton.setEnabled(badgeReady);
        demoButton.setEnabled(badgeReady && !badgeDemoRunning);
        stopDemoButton.setEnabled(badgeDemoRunning);
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null || adapter.getBluetoothLeScanner() == null) {
            appendLog("Bluetooth LE scanner unavailable");
            return;
        }
        scanResults.clear();
        scanRenderPending = false;
        lastScanRenderMs = 0;
        renderScanResults();
        adapter.getBluetoothLeScanner().startScan(scanCallback);
        scanning = true;
        updateUi();
        appendLog("scan started");
        int session = ++scanSession;
        handler.postDelayed(() -> {
            if (scanning && scanSession == session) {
                stopScan();
                appendLog("scan timed out");
            }
        }, 15000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        boolean tryRememberedBadge = autoConnectBadgeScan;
        if (adapter != null && adapter.getBluetoothLeScanner() != null && scanning && hasBlePermissions()) {
            try {
                adapter.getBluetoothLeScanner().stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanSession++;
        scanning = false;
        autoConnectBadgeScan = false;
        updateUi();
        if (tryRememberedBadge && !connectRememberedBadge()) {
            appendLog("known badge not found");
        }
    }

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        scanResults.put(result.getDevice().getAddress(), result);
        if (autoConnectBadgeScan && looksLikeBadge(result)) {
            autoConnectBadgeScan = false;
            appendLog("known badge found " + describeScanResult(result));
            stopScan();
            connectBadge(result.getDevice());
            return;
        }
        scheduleScanRender();
    }

    private void scheduleScanRender() {
        if (scanRenderPending) {
            return;
        }
        long now = System.currentTimeMillis();
        long waitMs = Math.max(0, SCAN_RENDER_INTERVAL_MS - (now - lastScanRenderMs));
        scanRenderPending = true;
        handler.postDelayed(() -> {
            scanRenderPending = false;
            lastScanRenderMs = System.currentTimeMillis();
            renderScanResults();
        }, waitMs);
    }

    private List<ScanResult> sortedScanResults() {
        List<ScanResult> results = new ArrayList<>(scanResults.values());
        Collections.sort(results, (left, right) -> Integer.compare(scanPriority(right), scanPriority(left)));
        return results;
    }

    private int scanPriority(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return Integer.MIN_VALUE;
        }
        int score = result.getRssi();
        if (isKnownBadgeAddress(result.getDevice().getAddress())) {
            score += 10000;
        }
        if (hasQixService(result)) {
            score += 8000;
        }
        if (isBadgeName(scanName(result))) {
            score += 6000;
        }
        if (looksLikeSuper73Bike(result)) {
            score += 5000;
        }
        return score;
    }

    private ScanResult bestKnownBadgeResult() {
        ScanResult best = null;
        for (ScanResult result : scanResults.values()) {
            if (!looksLikeBadge(result)) {
                continue;
            }
            if (best == null || scanPriority(result) > scanPriority(best)) {
                best = result;
            }
        }
        return best;
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice connectedKnownBadgeDevice() {
        if (!hasBlePermissions()) {
            return null;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) {
            return null;
        }
        try {
            for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT)) {
                if (device != null
                        && (isKnownBadgeAddress(device.getAddress()) || isBadgeName(safeDeviceName(device)))) {
                    return device;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean looksLikeBadge(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return false;
        }
        return isKnownBadgeAddress(result.getDevice().getAddress())
                || isBadgeName(scanName(result))
                || hasQixService(result);
    }

    private boolean looksLikeSuper73Bike(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return false;
        }
        return isSuper73Name(scanName(result)) || hasService(result, SUPER73_SERVICE_UUID);
    }

    private boolean isBadgeName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        return upper.contains("E87") || upper.contains("Q87") || upper.contains("ZRUN");
    }

    private boolean isSuper73Name(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        return name.toUpperCase(Locale.US).contains("SUPER73");
    }

    private boolean hasQixService(ScanResult result) {
        if (hasService(result, QIX_SERVICE_UUID)) {
            return true;
        }
        if (result.getScanRecord() == null || result.getScanRecord().getServiceUuids() == null) {
            return false;
        }
        List<ParcelUuid> services = result.getScanRecord().getServiceUuids();
        for (ParcelUuid service : services) {
            UUID uuid = service.getUuid();
            String text = uuid.toString().toLowerCase(Locale.US);
            if (text.contains("c2e6fd00") || text.startsWith("0000fd00")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasService(ScanResult result, UUID target) {
        if (result == null || result.getScanRecord() == null || result.getScanRecord().getServiceUuids() == null) {
            return false;
        }
        List<ParcelUuid> services = result.getScanRecord().getServiceUuids();
        for (ParcelUuid service : services) {
            if (target.equals(service.getUuid())) {
                return true;
            }
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private String scanName(ScanResult result) {
        String name = null;
        if (result.getScanRecord() != null) {
            name = result.getScanRecord().getDeviceName();
        }
        if (TextUtils.isEmpty(name) && result.getDevice() != null) {
            name = safeDeviceName(result.getDevice());
        }
        return TextUtils.isEmpty(name) ? "(unnamed)" : name;
    }

    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "(unnamed)";
        }
        try {
            String name = device.getName();
            return TextUtils.isEmpty(name) ? "(unnamed)" : name;
        } catch (SecurityException ignored) {
            return "(unnamed)";
        }
    }

    private String describeScanResult(ScanResult result) {
        return scanName(result) + " " + result.getDevice().getAddress() + " RSSI " + result.getRssi();
    }

    private boolean isKnownBadgeAddress(String address) {
        if (TextUtils.isEmpty(address)) {
            return false;
        }
        for (String known : KNOWN_BADGE_ADDRESSES) {
            if (known.equalsIgnoreCase(address)) {
                return true;
            }
        }
        return false;
    }

    private String knownBadgeAddressesText() {
        StringBuilder builder = new StringBuilder();
        for (String address : KNOWN_BADGE_ADDRESSES) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(address);
        }
        return builder.toString();
    }

    @SuppressLint("MissingPermission")
    private void renderScanResults() {
        scanResultsLayout.removeAllViews();
        if (scanResults.isEmpty()) {
            scanResultsLayout.addView(text("No devices yet.", 13, false, 0xFF66736F), matchWrap());
            return;
        }
        List<ScanResult> results = sortedScanResults();
        int shown = 0;
        for (ScanResult result : results) {
            if (shown >= MAX_RENDERED_SCAN_RESULTS) {
                break;
            }
            BluetoothDevice device = result.getDevice();
            String name = scanName(result);
            boolean badgeCandidate = looksLikeBadge(result);
            boolean super73Candidate = looksLikeSuper73Bike(result);
            LinearLayout card = card();
            TextView label = text(name + "\n" + device.getAddress() + "  RSSI " + result.getRssi(),
                    13, false, 0xFF1E2D28);
            if (badgeCandidate) {
                label.append("\nlikely E87/Q87 badge");
            }
            if (super73Candidate) {
                label.append("\nlikely Super73/S75 bike");
            }
            card.addView(label, matchWrap());
            LinearLayout buttons = row();
            Button badgeButton = smallButton("Badge");
            badgeButton.setOnClickListener(v -> connectBadge(device));
            Button bikeButton = smallButton("Bike");
            bikeButton.setOnClickListener(v -> connectBike(device));
            buttons.addView(badgeButton, weightButton());
            buttons.addView(bikeButton, weightButtonLeft());
            card.addView(buttons, matchWrap());
            addWithTopMargin(scanResultsLayout, card, dp(6));
            shown++;
        }
        int hidden = results.size() - shown;
        if (hidden > 0) {
            addWithTopMargin(scanResultsLayout,
                    text(hidden + " weaker devices hidden while scanning.", 12, false, 0xFF66736F),
                    dp(4));
        }
    }

    @SuppressLint("MissingPermission")
    private void connectKnownBadge() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        BluetoothDevice connected = connectedKnownBadgeDevice();
        if (connected != null) {
            appendLog("known badge already connected at OS " + connected.getAddress()
                    + " (" + safeDeviceName(connected) + ")");
            connectBadge(connected);
            return;
        }
        ScanResult best = bestKnownBadgeResult();
        if (best != null) {
            appendLog("known badge from scan " + describeScanResult(best));
            connectBadge(best.getDevice());
            return;
        }
        autoConnectBadgeScan = true;
        if (!scanning) {
            startScan();
        } else {
            appendLog("auto badge enabled for current scan");
            updateUi();
        }
    }

    @SuppressLint("MissingPermission")
    private boolean connectRememberedBadge() {
        if (!hasBlePermissions() || adapter == null) {
            return false;
        }
        String remembered = prefs.getString("badge_address", "");
        if (connectAddressIfValid(remembered, "remembered badge")) {
            return true;
        }
        for (String address : KNOWN_BADGE_ADDRESSES) {
            if (connectAddressIfValid(address, "known badge direct")) {
                return true;
            }
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private boolean connectAddressIfValid(String address, String label) {
        if (TextUtils.isEmpty(address) || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return false;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            appendLog(label + " " + address);
            connectBadge(device);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void connectBadge(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (scanning) {
            stopScan();
        }
        closeGatt(badgeGatt);
        badgeReady = false;
        badgeConnected = false;
        badgeWriteChar = null;
        badgeDescriptorQueue.clear();
        badgeDescriptorWriting = false;
        interpretationScreenEntered = false;
        interpretationEnabled = false;
        prefs.edit().putString("badge_address", device.getAddress()).apply();
        appendLog("connecting badge " + device.getAddress() + " (" + safeDeviceName(device) + ")");
        badgeGatt = device.connectGatt(this, false, badgeCallback, BluetoothDevice.TRANSPORT_LE);
        updateUi();
    }

    @SuppressLint("MissingPermission")
    private void connectBike(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (scanning) {
            stopScan();
        }
        closeGatt(bikeGatt);
        bikeConnected = false;
        bikeNotifyChar = null;
        bikeWriteChar = null;
        bikeRegisterIdChar = null;
        super73Bike = false;
        lastWheelRevs = null;
        lastWheelEvent = null;
        prefs.edit().putString("bike_address", device.getAddress()).apply();
        appendLog("connecting bike " + device.getAddress() + " (" + safeDeviceName(device) + ")");
        bikeGatt = device.connectGatt(this, false, bikeCallback, BluetoothDevice.TRANSPORT_LE);
        updateUi();
    }

    @SuppressLint("MissingPermission")
    private void setupSuper73Bike(BluetoothGatt gatt) {
        if (gatt == null || !hasBlePermissions()) {
            return;
        }
        BluetoothGattService service = gatt.getService(SUPER73_SERVICE_UUID);
        if (service == null) {
            super73Bike = false;
            return;
        }
        bikeRegisterIdChar = service.getCharacteristic(SUPER73_REGISTER_ID_UUID);
        bikeWriteChar = service.getCharacteristic(SUPER73_REGISTER_UUID);
        bikeNotifyChar = service.getCharacteristic(SUPER73_NOTIFY_UUID);
        super73Bike = bikeRegisterIdChar != null && bikeWriteChar != null && bikeNotifyChar != null;
        if (!super73Bike) {
            appendLog("Super73 service found but required chars missing");
            return;
        }
        appendLog("Super73/S75 bike service ready");
        enableNotify(gatt, bikeNotifyChar);
    }

    private void requestSuper73Settings() {
        writeSuper73Register(new byte[]{0x03, 0x00}, paddedCommand(0x03, 0x00));
    }

    private void requestSuper73TotalDistance() {
        writeSuper73Register(new byte[]{0x02, 0x02}, paddedCommand(0x02, 0x02));
    }

    private void requestSuper73RideData() {
        writeSuper73Register(new byte[]{0x02, 0x03}, paddedCommand(0x02, 0x03));
    }

    private void requestSuper73BatteryData() {
        writeSuper73Register(new byte[]{0x04, 0x01}, paddedCommand(0x04, 0x01));
    }

    private byte[] paddedCommand(int first, int second) {
        byte[] data = new byte[10];
        data[0] = (byte) (first & 0xFF);
        data[1] = (byte) (second & 0xFF);
        return data;
    }

    @SuppressLint("MissingPermission")
    private void writeSuper73Register(byte[] registerId, byte[] payload) {
        if (!super73Bike || bikeGatt == null || bikeRegisterIdChar == null || bikeWriteChar == null || !hasBlePermissions()) {
            return;
        }
        writeBikeCharacteristic(bikeRegisterIdChar, registerId);
        handler.postDelayed(() -> writeBikeCharacteristic(bikeWriteChar, payload), 120);
    }

    @SuppressLint("MissingPermission")
    private void writeBikeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (bikeGatt == null || characteristic == null || value == null || !hasBlePermissions()) {
            return;
        }
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(value);
        boolean started = bikeGatt.writeCharacteristic(characteristic);
        appendLog("bike write start " + shortUuid(characteristic.getUuid()) + " " + toHex(value)
                + " started=" + started);
    }

    @SuppressLint("MissingPermission")
    private void renderBikeCharacteristics(BluetoothGatt gatt) {
        bikeCharsLayout.removeAllViews();
        for (BluetoothGattService service : gatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int props = characteristic.getProperties();
                boolean canNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                boolean canRead = (props & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
                if (!canNotify && !canRead) {
                    continue;
                }
                LinearLayout card = card();
                card.addView(text(service.getUuid() + "\n" + characteristic.getUuid()
                        + "\nnotify=" + canNotify + " read=" + canRead, 12, false, 0xFF1E2D28), matchWrap());
                LinearLayout buttons = row();
                if (canNotify) {
                    Button useButton = smallButton("Use Notify");
                    useButton.setOnClickListener(v -> {
                        bikeNotifyChar = characteristic;
                        enableNotify(gatt, characteristic);
                        updateUi();
                        appendLog("bike notify " + characteristic.getUuid());
                    });
                    buttons.addView(useButton, weightButton());
                }
                if (canRead) {
                    Button readButton = smallButton("Read");
                    readButton.setOnClickListener(v -> gatt.readCharacteristic(characteristic));
                    buttons.addView(readButton, weightButtonLeft());
                }
                card.addView(buttons, matchWrap());
                addWithTopMargin(bikeCharsLayout, card, dp(6));
            }
        }
    }

    private void handleBikeBytes(UUID uuid, byte[] value) {
        Float speed = parseSpeedKmh(uuid, value);
        String hex = toHex(value);
        String decoded = describeBikePayload(uuid, value, speed);
        runOnUiThread(() -> appendLog("bike " + shortUuid(uuid) + " " + hex + decoded));
        if (speed != null) {
            int rounded = Math.max(0, Math.min(99, Math.round(speed)));
            long now = System.currentTimeMillis();
            if (now - lastSpeedSendMs > 700 || Math.abs(rounded - manualSpeed) >= 1) {
                lastSpeedSendMs = now;
                manualSpeed = rounded;
                runOnUiThread(this::updateUi);
                sendSpeed(rounded, false);
            }
        }
    }

    private Float parseSpeedKmh(UUID uuid, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (SUPER73_NOTIFY_UUID.equals(uuid) && data.length >= 4
                && (data[0] & 0xFF) == 0x02 && (data[1] & 0xFF) == 0x01) {
            return u16le(data, 2) / 100.0f;
        }
        if (CSC_MEASUREMENT_UUID.equals(uuid) && data.length >= 7 && (data[0] & 0x01) != 0) {
            int revs = u32le(data, 1);
            int event = u16le(data, 5);
            if (lastWheelRevs != null && lastWheelEvent != null) {
                int deltaRevs = revs - lastWheelRevs;
                int deltaTicks = event - lastWheelEvent;
                if (deltaTicks < 0) {
                    deltaTicks += 65536;
                }
                lastWheelRevs = revs;
                lastWheelEvent = event;
                if (deltaRevs >= 0 && deltaTicks > 0) {
                    double seconds = deltaTicks / 1024.0;
                    double wheelCircumferenceM = 2.10;
                    return (float) ((deltaRevs * wheelCircumferenceM / seconds) * 3.6);
                }
            } else {
                lastWheelRevs = revs;
                lastWheelEvent = event;
            }
            return null;
        }

        float best = -1;
        int first = data[0] & 0xFF;
        if (first <= 80) {
            best = first;
        }
        if (data.length >= 2) {
            int u16 = u16le(data, 0);
            float tenth = u16 / 10.0f;
            float hundredth = u16 / 100.0f;
            if (tenth >= 0 && tenth <= 80) {
                best = tenth;
            } else if (hundredth >= 0 && hundredth <= 80) {
                best = hundredth;
            } else if (u16 >= 0 && u16 <= 80) {
                best = u16;
            }
        }
        return best >= 0 ? best : null;
    }

    private String describeBikePayload(UUID uuid, byte[] data, Float speed) {
        if (speed != null) {
            return " -> " + String.format(Locale.US, "%.1f km/h", speed);
        }
        if (SUPER73_NOTIFY_UUID.equals(uuid) && data != null && data.length >= 2) {
            int first = data[0] & 0xFF;
            int second = data[1] & 0xFF;
            if (first == 0x02 && second == 0x02 && data.length >= 8) {
                return " total=" + String.format(Locale.US, "%.1f km", u16le(data, 6) / 10.0f);
            }
            if (first == 0x02 && second == 0x03 && data.length >= 9) {
                return " range=" + (data[8] & 0xFF) + " km";
            }
            if (first == 0x03 && second == 0x00 && data.length >= 6) {
                return " settings assist=" + (data[2] & 0xFF)
                        + " light=" + (data[4] & 0xFF)
                        + " mode=" + (data[5] & 0xFF);
            }
            if (first == 0x04 && second == 0x01 && data.length >= 4) {
                return " voltage=" + String.format(Locale.US, "%.2f V", u16le(data, 2) / 1000.0f);
            }
        }
        return "";
    }

    private void startBadgeDemo() {
        if (!badgeReady) {
            appendLog("badge not ready, cannot start demo");
            return;
        }
        badgeDemoRunning = true;
        demoSpeed = Math.max(0, Math.min(35, manualSpeed));
        demoDirection = 1;
        appendLog("badge demo started");
        runBadgeDemoTick();
        updateUi();
    }

    private void stopBadgeDemo() {
        if (badgeDemoRunning) {
            appendLog("badge demo stopped");
        }
        badgeDemoRunning = false;
        handler.removeCallbacks(badgeDemoRunnable);
        updateUi();
    }

    private void runBadgeDemoTick() {
        if (!badgeDemoRunning) {
            return;
        }
        if (!badgeReady) {
            badgeDemoRunning = false;
            handler.removeCallbacks(badgeDemoRunnable);
            appendLog("badge demo stopped: badge not ready");
            updateUi();
            return;
        }
        manualSpeed = demoSpeed;
        updateUi();
        sendSpeed(demoSpeed, "demo");

        demoSpeed += demoDirection * 3;
        if (demoSpeed >= 36) {
            demoSpeed = 36;
            demoDirection = -1;
        } else if (demoSpeed <= 0) {
            demoSpeed = 0;
            demoDirection = 1;
        }
        handler.postDelayed(badgeDemoRunnable, 1000);
    }

    private void sendSpeed(int speed, boolean manual) {
        sendSpeed(speed, manual ? "manual" : "bike");
    }

    private void sendSpeed(int speed, String source) {
        if (!badgeReady) {
            appendLog("badge not ready, cannot send speed");
            return;
        }
        String text = String.format(Locale.US, "SPD %02d KMH", speed);
        appendLog(source + " " + text);
        ensureBadgeLiveScreen();
        queueBadgePacket(buildInterpretationTextPacket(7, "BIKE SPEED"));
        queueBadgePacket(buildInterpretationTextPacket(8, text));
        queueBadgePacket(buildWeatherPacket(String.format(Locale.US, "SPD%02d", speed), speed));
    }

    private void wakeBadgeLiveScreen() {
        if (!badgeReady) {
            appendLog("badge not ready, cannot wake live screen");
            return;
        }
        appendLog("wake badge live screen");
        forceBadgeLiveScreen();
        handler.postDelayed(() -> sendSpeed(manualSpeed, "wake-test"), 250);
    }

    private void ensureBadgeLiveScreen() {
        if (!interpretationScreenEntered) {
            queueBadgePacket(buildInterpretationControlPacket(1, 1));
            interpretationScreenEntered = true;
        }
        if (!interpretationEnabled) {
            queueBadgePacket(buildInterpretationControlPacket(6, 1));
            interpretationEnabled = true;
        }
    }

    private void forceBadgeLiveScreen() {
        queueBadgePacket(buildInterpretationControlPacket(1, 1));
        queueBadgePacket(buildInterpretationControlPacket(6, 1));
        interpretationScreenEntered = true;
        interpretationEnabled = true;
    }

    private byte[] buildBindPacket() {
        long now = System.currentTimeMillis() / 1000L;
        byte[] payload = new byte[13];
        payload[0] = 0x02;
        putLe48(payload, 1, now);
        putLe48(payload, 7, now);
        byte[] body = new byte[4 + payload.length];
        body[0] = 0x02;
        body[1] = 0x60;
        body[2] = (byte) (payload.length & 0xFF);
        body[3] = (byte) ((payload.length >> 8) & 0xFF);
        System.arraycopy(payload, 0, body, 4, payload.length);
        return withMarker(body);
    }

    private byte[] buildPacket(int command, byte[] payload, boolean needsResponse) {
        boolean split = payload.length + 6 > 20;
        byte flag = (byte) ((split ? 0x04 : 0x00) | (needsResponse ? 0x02 : 0x00));
        byte[] body = new byte[4 + payload.length];
        body[0] = flag;
        body[1] = (byte) (command & 0xFF);
        body[2] = (byte) (payload.length & 0xFF);
        body[3] = (byte) ((payload.length >> 8) & 0xFF);
        System.arraycopy(payload, 0, body, 4, payload.length);
        return withMarker(body);
    }

    private byte[] buildInterpretationControlPacket(int kind, int value) {
        byte[] payload = new byte[]{(byte) (kind & 0xFF), (byte) (value & 0xFF)};
        return buildPacket(0x8D, payload, true);
    }

    private byte[] buildInterpretationTextPacket(int kind, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[3 + textBytes.length];
        payload[0] = (byte) (kind & 0xFF);
        payload[1] = (byte) (textBytes.length & 0xFF);
        payload[2] = (byte) ((textBytes.length >> 8) & 0xFF);
        System.arraycopy(textBytes, 0, payload, 3, textBytes.length);
        return buildPacket(0x8D, payload, true);
    }

    private byte[] buildWeatherPacket(String city, int temp) {
        byte[] cityBytes = city.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[1 + cityBytes.length + 1 + 6];
        payload[0] = (byte) (cityBytes.length & 0xFF);
        System.arraycopy(cityBytes, 0, payload, 1, cityBytes.length);
        int offset = 1 + cityBytes.length;
        payload[offset] = 0x01;
        payload[offset + 1] = 0x00;
        payload[offset + 2] = 0x00;
        payload[offset + 3] = 0x00;
        payload[offset + 4] = (byte) (temp & 0xFF);
        payload[offset + 5] = 0x00;
        payload[offset + 6] = (byte) Math.max(temp, 30);
        return buildPacket(0x2B, payload, true);
    }

    private byte[] buildNavigationTextPacket(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + textBytes.length];
        payload[0] = 0x04;
        payload[1] = (byte) (textBytes.length & 0xFF);
        System.arraycopy(textBytes, 0, payload, 2, textBytes.length);
        return buildPacket(0xDF, payload, false);
    }

    private byte[] withMarker(byte[] body) {
        byte[] packet = new byte[2 + body.length];
        packet[0] = (byte) 0x9E;
        packet[1] = checksum(body);
        System.arraycopy(body, 0, packet, 2, body.length);
        return packet;
    }

    private byte checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum = (sum + (b & 0xFF)) & 0xFF;
        }
        return (byte) sum;
    }

    private void putLe48(byte[] target, int offset, long value) {
        for (int i = 0; i < 6; i++) {
            target[offset + i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
    }

    private void queueBadgePacket(byte[] packet) {
        for (int offset = 0; offset < packet.length; offset += 20) {
            int len = Math.min(20, packet.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(packet, offset, chunk, 0, len);
            badgeWriteQueue.offer(chunk);
        }
        if (!badgeSending) {
            sendNextBadgeChunk();
        }
    }

    @SuppressLint("MissingPermission")
    private void sendNextBadgeChunk() {
        if (badgeWriteQueue.isEmpty()) {
            badgeSending = false;
            return;
        }
        if (badgeGatt == null || badgeWriteChar == null || !hasBlePermissions()) {
            badgeWriteQueue.clear();
            badgeSending = false;
            return;
        }
        badgeSending = true;
        byte[] chunk = badgeWriteQueue.poll();
        badgeWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        badgeWriteChar.setValue(chunk);
        badgeGatt.writeCharacteristic(badgeWriteChar);
        handler.postDelayed(this::sendNextBadgeChunk, 80);
    }

    private void handleBadgeNotify(UUID uuid, byte[] value) {
        runOnUiThread(() -> appendLog("badge " + shortUuid(uuid) + " " + toHex(value)));
    }

    @SuppressLint("MissingPermission")
    private void setupBadgeNotifications(BluetoothGatt gatt, BluetoothGattService service) {
        badgeDescriptorQueue.clear();
        badgeDescriptorWriting = false;
        enqueueBadgeDescriptor(gatt, service.getCharacteristic(QIX_NOTIFY_UUID));
        enqueueBadgeDescriptor(gatt, service.getCharacteristic(QIX_CONTROL_UUID));
        writeNextBadgeDescriptor(gatt);
    }

    @SuppressLint("MissingPermission")
    private void enqueueBadgeDescriptor(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (gatt == null || characteristic == null || !hasBlePermissions()) {
            return;
        }
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            badgeDescriptorQueue.offer(descriptor);
        }
    }

    @SuppressLint("MissingPermission")
    private void writeNextBadgeDescriptor(BluetoothGatt gatt) {
        if (badgeDescriptorWriting || gatt == null || !hasBlePermissions()) {
            return;
        }
        BluetoothGattDescriptor descriptor = badgeDescriptorQueue.poll();
        if (descriptor == null) {
            return;
        }
        badgeDescriptorWriting = true;
        boolean started = gatt.writeDescriptor(descriptor);
        appendLog("badge cccd write " + shortUuid(descriptor.getCharacteristic().getUuid()) + " started=" + started);
        if (!started) {
            badgeDescriptorWriting = false;
            handler.postDelayed(() -> writeNextBadgeDescriptor(gatt), 120);
        }
    }

    @SuppressLint("MissingPermission")
    private void enableNotify(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (gatt == null || characteristic == null || !hasBlePermissions()) {
            return;
        }
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }
    }

    @SuppressLint("MissingPermission")
    private void safeDiscoverServices(BluetoothGatt gatt) {
        if (gatt != null && hasBlePermissions()) {
            gatt.discoverServices();
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt(BluetoothGatt gatt) {
        if (gatt == null || !hasBlePermissions()) {
            return;
        }
        try {
            gatt.disconnect();
            gatt.close();
        } catch (Exception ignored) {
        }
    }

    private void disconnectAll() {
        stopBadgeDemo();
        closeGatt(badgeGatt);
        closeGatt(bikeGatt);
        badgeGatt = null;
        bikeGatt = null;
        badgeReady = false;
        badgeConnected = false;
        bikeConnected = false;
        badgeWriteChar = null;
        bikeNotifyChar = null;
        bikeWriteChar = null;
        bikeRegisterIdChar = null;
        super73Bike = false;
        badgeWriteQueue.clear();
        badgeDescriptorQueue.clear();
        badgeSending = false;
        badgeDescriptorWriting = false;
        interpretationScreenEntered = false;
        interpretationEnabled = false;
        updateUi();
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMISSIONS);
        }
    }

    private int u16le(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int u32le(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte b : data) {
            builder.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return builder.toString();
    }

    private String shortUuid(UUID uuid) {
        String value = uuid.toString();
        if (value.startsWith("0000") && value.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return value.substring(4, 8);
        }
        return value.substring(0, 8);
    }

    private void appendLog(String line) {
        Log.d(LOG_TAG, line);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> appendLogUi(line));
            return;
        }
        appendLogUi(line);
    }

    private void appendLogUi(String line) {
        if (logText == null) {
            return;
        }
        String current = logText.getText().toString();
        String next = line + "\n" + current;
        if (next.length() > 6000) {
            next = next.substring(0, 6000);
        }
        logText.setText(next);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView cardText(int sp, boolean bold) {
        TextView view = text("", sp, bold, 0xFF20312C);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackgroundColor(0xFFFFFFFF);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private Button smallButton(String label) {
        Button button = button(label);
        button.setTextSize(12);
        return button;
    }

    private TextView section(String label) {
        TextView view = text(label, 16, true, 0xFF16352E);
        view.setPadding(0, dp(18), 0, dp(2));
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackgroundColor(0xFFFFFFFF);
        return card;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightButtonLeft() {
        LinearLayout.LayoutParams params = weightButton();
        params.leftMargin = dp(8);
        return params;
    }

    private void addWithTopMargin(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = topMargin;
        parent.addView(child, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
