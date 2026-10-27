package com.marco.bondbandbridge;

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
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_BLE_PERMISSIONS = 1001;
    private static final int REQ_AUDIO_PERMISSION = 1002;
    private static final int REQ_PLAYBACK_CAPTURE = 1003;
    private static final int BRIDGE_PORT = 7878;
    private static final int PHONE_BASS_SAMPLE_RATE = 44100;
    private static final int PHONE_BASS_CUTOFF_HZ = 140;

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID STANDARD_BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID STANDARD_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID STANDARD_DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID STANDARD_FIRMWARE_REVISION = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");

    private static final UUID LEGACY_DEVICE_INFO_SERVICE = UUID.fromString("d3c00001-add3-1d4b-0000-0000b5006bb1");
    private static final UUID LEGACY_USER_APP_SERVICE = UUID.fromString("d3c00002-add3-1d4b-0001-0000b5006bb1");
    private static final UUID LEGACY_BATTERY_LEVEL = UUID.fromString("d3c0ff06-add3-1d4b-0000-0000b5006bb1");
    private static final UUID LEGACY_API = UUID.fromString("d3c0ff10-add3-1d4b-0000-0000b5006bb1");
    private static final UUID FFA1_TOKEN = UUID.fromString("d3c0ffa1-add3-1d4b-0001-0000b5006bb1");
    private static final UUID FFA2_AUID = UUID.fromString("d3c0ffa2-add3-1d4b-0001-0000b5006bb1");
    private static final UUID FFA4_STATE = UUID.fromString("d3c0ffa4-add3-1d4b-0001-0000b5006bb1");
    private static final UUID FFA5_EVENT = UUID.fromString("d3c0ffa5-add3-1d4b-0001-0000b5006bb1");
    private static final UUID FFA6_COMMAND = UUID.fromString("d3c0ffa6-add3-1d4b-0001-0000b5006bb1");
    private static final UUID FFA8_BAND4_EXTRA = UUID.fromString("d3c0ffa8-add3-1d4b-0001-0000b5006bb1");

    private static final byte CMD_LED_MOTOR_CTRL = 0x26;
    private static final byte CMD_CONFIG = 0x27;
    private static final byte CMD_PAIRED_CORRECT = 0x31;
    private static final int LED_ON = 0x01;
    private static final int LED_BREATHE = 0x02;
    private static final int LED_OFF = 0x04;

    private static final int CONFIG_DOUBLE_TAP_COLOR = 0x24;
    private static final int CONFIG_TOUCH_COLOR = 0x25;
    private static final int CONFIG_TOUCH_WARNING_COLOR = 0x26;
    private static final int CONFIG_ERROR_COLOR = 0x27;
    private static final int CONFIG_CHARGING_COLOR = 0x28;
    private static final int CONFIG_CHARGE_DONE_COLOR = 0x29;

    private static final String PREFS = "bond_bridge";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_NAME = "name";
    private static final String KEY_AUID = "auid";
    private static final String KEY_MIN_STRENGTH = "haptic_min_strength";
    private static final String KEY_MAX_STRENGTH = "haptic_max_strength";
    private static final String KEY_MIN_DURATION = "haptic_min_duration";
    private static final String KEY_MAX_DURATION = "haptic_max_duration";
    private static final String KEY_INTENSITY_SCALE = "haptic_intensity_scale";
    private static final String KEY_GLOBAL_COOLDOWN = "haptic_global_cooldown";
    private static final String KEY_BURST_LIMIT = "haptic_burst_limit";
    private static final String KEY_COLORS_ENABLED = "haptic_colors";
    private static final String KEY_PREDICTIVE_ENABLED = "haptic_predictive";
    private static final String KEY_SCREEN_OFF_LOCKS = "haptic_screen_off_locks";
    private static final String KEY_PROFILE = "haptic_profile";
    private static final String KEY_PHONE_BASS_SENSITIVITY = "phone_bass_sensitivity";
    private static final String KEY_PHONE_BASS_TRIGGER = "phone_bass_trigger";
    private static final String KEY_PHONE_BASS_BEAT_THRESHOLD = "phone_bass_beat_threshold";
    private static final String KEY_PHONE_BASS_COOLDOWN = "phone_bass_cooldown";
    private static final String KEY_PHONE_BASS_PULSE_LIMIT = "phone_bass_pulse_limit";
    private static final String KEY_PHONE_BASS_MIN_STRENGTH = "phone_bass_min_strength";
    private static final String KEY_PHONE_BASS_MAX_STRENGTH = "phone_bass_max_strength";
    private static final String KEY_PHONE_BASS_DURATION = "phone_bass_duration";
    private static final String KEY_PHONE_BASS_PATTERN = "phone_bass_pattern";
    private static final String KEY_PHONE_BASS_COLORS = "phone_bass_colors";
    private static final String KEY_PHONE_BASS_COLOR = "phone_bass_color";

    private static final String[] PHONE_BASS_PATTERNS = {
            "single", "double", "triple", "warning", "ramp", "rise", "heartbeat"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> scanResults = new LinkedHashMap<>();
    private final ArrayDeque<BleOperation> opQueue = new ArrayDeque<>();
    private final Map<String, Long> bridgeLastEventMs = new HashMap<>();
    private final ArrayDeque<Long> bridgeRecentEventMs = new ArrayDeque<>();

    private SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection phoneBassProjection;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean scanning;
    private boolean operationInFlight;
    private boolean ready;
    private boolean initQueued;
    private boolean firmwareReadQueued;
    private boolean badApplePlaying;
    private volatile boolean bridgeServerRunning;
    private boolean colorsEnabled;
    private boolean predictiveEnabled;
    private boolean screenOffLocksEnabled;
    private volatile boolean phoneBassRunning;
    private int localAuid;
    private int badAppleToken;
    private int minStrength;
    private int maxStrength;
    private int minDurationMs;
    private int maxDurationMs;
    private int intensityScale;
    private int globalCooldownMs;
    private int burstLimit;
    private int selectedColor = 0xFFFFFF;
    private int phoneBassSensitivity;
    private int phoneBassTriggerLevel;
    private int phoneBassBeatThreshold;
    private int phoneBassCooldownMs;
    private int phoneBassPulseLimit;
    private int phoneBassMinStrength;
    private int phoneBassMaxStrength;
    private int phoneBassPulseDurationMs;
    private int phoneBassColor;
    private String connectedAddress;
    private String generation = "unknown";
    private String selectedColorName = "White";
    private String hapticProfile;
    private String phoneBassPattern;
    private boolean phoneBassColorsEnabled;
    private double phoneBassLatestLevel;
    private double phoneBassLatestStrength;
    private double phoneBassFastLevel;
    private double phoneBassSlowLevel = 10.0;
    private double phoneBassLowPass;
    private Thread bridgeServerThread;
    private Thread phoneBassThread;
    private AudioRecord phoneBassRecord;
    private MediaProjection.Callback phoneBassProjectionCallback;

    private LinearLayout resultsLayout;
    private TextView statusText;
    private TextView bridgeText;
    private TextView hapticSettingsText;
    private TextView phoneBassText;
    private TextView phoneBassSettingsText;
    private TextView selectedColorText;
    private TextView logText;
    private Button permissionButton;
    private Button audioPermissionButton;
    private Button scanButton;
    private Button stopScanButton;
    private Button reconnectButton;
    private Button buzzSoftButton;
    private Button buzzStrongButton;
    private Button buzzColorButton;
    private Button pulseButton;
    private Button badAppleButton;
    private Button stopBadAppleButton;
    private Button bassStartButton;
    private Button bassStopButton;
    private Button bassTestButton;
    private Button bassPatternButton;
    private Button bassColorButton;
    private Button bassResetButton;
    private Button bassSensitivityDownButton;
    private Button bassSensitivityUpButton;
    private Button bassTriggerDownButton;
    private Button bassTriggerUpButton;
    private Button bassThresholdDownButton;
    private Button bassThresholdUpButton;
    private Button bassCooldownDownButton;
    private Button bassCooldownUpButton;
    private Button bassStrengthDownButton;
    private Button bassStrengthUpButton;
    private Button bassDurationDownButton;
    private Button bassDurationUpButton;
    private Button showColorButton;
    private Button hideColorButton;
    private Button breatheColorButton;
    private Button setTouchColorButton;
    private Button setDoubleTapColorButton;
    private Button setWarningColorButton;
    private Button setErrorColorButton;
    private Button forgetButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        localAuid = prefs.getInt(KEY_AUID, new Random().nextInt());
        if (!prefs.contains(KEY_AUID)) {
            prefs.edit().putInt(KEY_AUID, localAuid).apply();
        }
        loadHapticSettings();
        loadPhoneBassSettings();

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        }

        buildUi();
        updateButtons();
        applyPowerLocks();
        startBridgeServer();
        log("App started. Local AUID " + String.format(Locale.US, "0x%08X", localAuid) + ".");
        log("Close the official Bond app first, then wake/tap the band and scan.");
        log("Phone bridge listens on port " + BRIDGE_PORT + ". Keep this app open for screen-off bridge mode.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBridgeServer();
        stopPhoneBass();
        releasePowerLocks();
        stopScan();
        closeGatt();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_PERMISSIONS) {
            if (hasBlePermissions()) {
                log("Bluetooth permissions granted.");
            } else {
                log("Bluetooth permissions are still missing.");
            }
        } else if (requestCode == REQ_AUDIO_PERMISSION) {
            if (hasAudioPermission()) {
                log("Audio permission granted. Press Start Phone Audio Bass again to approve playback capture.");
            } else {
                log("Audio permission is still missing.");
            }
        }
        updateButtons();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PLAYBACK_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            log("Phone audio capture permission was cancelled.");
            updateButtons();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjectionManager == null) {
            log("Playback capture is not available on this Android version.");
            updateButtons();
            return;
        }
        phoneBassProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        if (phoneBassProjection == null) {
            log("Phone audio capture permission did not return a projection.");
            updateButtons();
            return;
        }
        phoneBassProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                log("Phone audio capture permission stopped.");
                stopPhoneBass();
            }
        };
        phoneBassProjection.registerCallback(phoneBassProjectionCallback, mainHandler);
        log("Phone audio capture permission granted.");
        startPhoneBass();
    }

    private void buildUi() {
        int outerPad = dp(18);
        int gap = dp(10);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(outerPad, outerPad, outerPad, outerPad);
        root.setBackgroundColor(0xFFF6F8FB);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Bond Band Bridge");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF172033);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Local BLE control for Bond Touch Band 4 / Pop");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF546071);
        subtitle.setPadding(0, dp(3), 0, dp(13));
        root.addView(subtitle, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(0xFF172033);
        statusText.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusText.setBackground(makeBlockColor(0xFFFFFFFF));
        root.addView(statusText, matchWrap());

        bridgeText = new TextView(this);
        bridgeText.setTextSize(13);
        bridgeText.setTextColor(0xFF344054);
        bridgeText.setPadding(dp(14), dp(10), dp(14), dp(10));
        bridgeText.setBackground(makeBlockColor(0xFFFFFFFF));
        addWithTopMargin(root, bridgeText, dp(8));

        permissionButton = actionButton("Grant Bluetooth Permissions");
        permissionButton.setOnClickListener(v -> requestBlePermissions());
        addWithTopMargin(root, permissionButton, gap);

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.setGravity(Gravity.CENTER);
        scanButton = actionButton("Scan for Bond Band");
        scanButton.setOnClickListener(v -> startScan());
        stopScanButton = actionButton("Stop Scan");
        stopScanButton.setOnClickListener(v -> stopScan());
        scanRow.addView(scanButton, weightButton());
        scanRow.addView(stopScanButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, scanRow, gap);

        reconnectButton = actionButton("Connect Saved Band");
        reconnectButton.setOnClickListener(v -> connectSaved());
        addWithTopMargin(root, reconnectButton, gap);

        forgetButton = quietButton("Forget Saved Band");
        forgetButton.setOnClickListener(v -> {
            prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply();
            connectedAddress = null;
            log("Saved band removed.");
            updateButtons();
        });
        addWithTopMargin(root, forgetButton, dp(6));

        TextView resultsTitle = sectionTitle("Scan Results");
        addWithTopMargin(root, resultsTitle, dp(18));
        resultsLayout = new LinearLayout(this);
        resultsLayout.setOrientation(LinearLayout.VERTICAL);
        addWithTopMargin(root, resultsLayout, dp(4));

        TextView actionsTitle = sectionTitle("Band Actions");
        addWithTopMargin(root, actionsTitle, dp(18));

        LinearLayout buzzRow = new LinearLayout(this);
        buzzRow.setOrientation(LinearLayout.HORIZONTAL);
        buzzSoftButton = actionButton("Soft Buzz");
        buzzSoftButton.setOnClickListener(v -> sendVibration(38, 900, LED_OFF, 0, 0, 0, 0, 0));
        buzzStrongButton = actionButton("Strong Buzz");
        buzzStrongButton.setOnClickListener(v -> sendVibration(100, 2000, LED_OFF, 0, 0, 0, 0, 0));
        buzzRow.addView(buzzSoftButton, weightButton());
        buzzRow.addView(buzzStrongButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, buzzRow, gap);

        LinearLayout patternRow = new LinearLayout(this);
        patternRow.setOrientation(LinearLayout.HORIZONTAL);
        buzzColorButton = actionButton("Color Buzz");
        buzzColorButton.setOnClickListener(v -> sendColorVibration(80, selectedColor));
        pulseButton = actionButton("Triple Pulse");
        pulseButton.setOnClickListener(v -> sendTriplePulse());
        patternRow.addView(buzzColorButton, weightButton());
        patternRow.addView(pulseButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, patternRow, gap);

        LinearLayout songRow = new LinearLayout(this);
        songRow.setOrientation(LinearLayout.HORIZONTAL);
        badAppleButton = actionButton("Bad Apple Haptic Test");
        badAppleButton.setOnClickListener(v -> playBadAppleHaptics());
        stopBadAppleButton = actionButton("Stop Pattern");
        stopBadAppleButton.setOnClickListener(v -> stopBadAppleHaptics());
        songRow.addView(badAppleButton, weightButton());
        songRow.addView(stopBadAppleButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, songRow, gap);

        TextView bassTitle = sectionTitle("Phone Audio Bass Haptics");
        addWithTopMargin(root, bassTitle, dp(18));
        phoneBassText = new TextView(this);
        phoneBassText.setTextSize(14);
        phoneBassText.setTypeface(Typeface.MONOSPACE);
        phoneBassText.setTextColor(0xFF172033);
        phoneBassText.setPadding(dp(12), dp(10), dp(12), dp(10));
        phoneBassText.setBackground(makeBlockColor(0xFFFFFFFF));
        addWithTopMargin(root, phoneBassText, dp(4));

        phoneBassSettingsText = new TextView(this);
        phoneBassSettingsText.setTextSize(12);
        phoneBassSettingsText.setTextColor(0xFF344054);
        phoneBassSettingsText.setPadding(dp(12), dp(10), dp(12), dp(10));
        phoneBassSettingsText.setBackground(makeBlockColor(0xFFFFFFFF));
        addWithTopMargin(root, phoneBassSettingsText, dp(6));

        audioPermissionButton = actionButton("Grant Audio Capture Permission");
        audioPermissionButton.setOnClickListener(v -> beginPhoneAudioCapturePermission());
        addWithTopMargin(root, audioPermissionButton, dp(6));

        LinearLayout bassRunRow = new LinearLayout(this);
        bassRunRow.setOrientation(LinearLayout.HORIZONTAL);
        bassStartButton = actionButton("Start Phone Audio Bass");
        bassStartButton.setOnClickListener(v -> startPhoneBass());
        bassStopButton = actionButton("Stop Phone Bass");
        bassStopButton.setOnClickListener(v -> stopPhoneBass());
        bassRunRow.addView(bassStartButton, weightButton());
        bassRunRow.addView(bassStopButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, bassRunRow, gap);

        LinearLayout bassModeRow = new LinearLayout(this);
        bassModeRow.setOrientation(LinearLayout.HORIZONTAL);
        bassTestButton = actionButton("Bass Test Pulse");
        bassTestButton.setOnClickListener(v -> sendPhoneBassTestPulse());
        bassPatternButton = actionButton("Pattern");
        bassPatternButton.setOnClickListener(v -> cyclePhoneBassPattern());
        bassColorButton = actionButton("Bass Color");
        bassColorButton.setOnClickListener(v -> togglePhoneBassColors());
        bassModeRow.addView(bassTestButton, weightButton());
        bassModeRow.addView(bassPatternButton, weightButtonWithLeftMargin());
        bassModeRow.addView(bassColorButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, bassModeRow, dp(6));

        LinearLayout bassSensRow = new LinearLayout(this);
        bassSensRow.setOrientation(LinearLayout.HORIZONTAL);
        bassSensitivityDownButton = actionButton("Sens -");
        bassSensitivityDownButton.setOnClickListener(v -> adjustPhoneBassSensitivity(-5));
        bassSensitivityUpButton = actionButton("Sens +");
        bassSensitivityUpButton.setOnClickListener(v -> adjustPhoneBassSensitivity(5));
        bassTriggerDownButton = actionButton("Trigger -");
        bassTriggerDownButton.setOnClickListener(v -> adjustPhoneBassTrigger(-2));
        bassTriggerUpButton = actionButton("Trigger +");
        bassTriggerUpButton.setOnClickListener(v -> adjustPhoneBassTrigger(2));
        bassSensRow.addView(bassSensitivityDownButton, weightButton());
        bassSensRow.addView(bassSensitivityUpButton, weightButtonWithLeftMargin());
        bassSensRow.addView(bassTriggerDownButton, weightButtonWithLeftMargin());
        bassSensRow.addView(bassTriggerUpButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, bassSensRow, dp(6));

        LinearLayout bassGateRow = new LinearLayout(this);
        bassGateRow.setOrientation(LinearLayout.HORIZONTAL);
        bassThresholdDownButton = actionButton("Beat -");
        bassThresholdDownButton.setOnClickListener(v -> adjustPhoneBassBeatThreshold(-5));
        bassThresholdUpButton = actionButton("Beat +");
        bassThresholdUpButton.setOnClickListener(v -> adjustPhoneBassBeatThreshold(5));
        bassCooldownDownButton = actionButton("Cool -");
        bassCooldownDownButton.setOnClickListener(v -> adjustPhoneBassCooldown(-10));
        bassCooldownUpButton = actionButton("Cool +");
        bassCooldownUpButton.setOnClickListener(v -> adjustPhoneBassCooldown(10));
        bassGateRow.addView(bassThresholdDownButton, weightButton());
        bassGateRow.addView(bassThresholdUpButton, weightButtonWithLeftMargin());
        bassGateRow.addView(bassCooldownDownButton, weightButtonWithLeftMargin());
        bassGateRow.addView(bassCooldownUpButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, bassGateRow, dp(6));

        LinearLayout bassOutputRow = new LinearLayout(this);
        bassOutputRow.setOrientation(LinearLayout.HORIZONTAL);
        bassStrengthDownButton = actionButton("Power -");
        bassStrengthDownButton.setOnClickListener(v -> adjustPhoneBassStrength(-4));
        bassStrengthUpButton = actionButton("Power +");
        bassStrengthUpButton.setOnClickListener(v -> adjustPhoneBassStrength(4));
        bassDurationDownButton = actionButton("Dur -");
        bassDurationDownButton.setOnClickListener(v -> adjustPhoneBassDuration(-5));
        bassDurationUpButton = actionButton("Dur +");
        bassDurationUpButton.setOnClickListener(v -> adjustPhoneBassDuration(5));
        bassOutputRow.addView(bassStrengthDownButton, weightButton());
        bassOutputRow.addView(bassStrengthUpButton, weightButtonWithLeftMargin());
        bassOutputRow.addView(bassDurationDownButton, weightButtonWithLeftMargin());
        bassOutputRow.addView(bassDurationUpButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, bassOutputRow, dp(6));

        bassResetButton = quietButton("Reset Bass Defaults");
        bassResetButton.setOnClickListener(v -> resetPhoneBassDefaults());
        addWithTopMargin(root, bassResetButton, dp(6));

        TextView colorTitle = sectionTitle("Color Lab");
        addWithTopMargin(root, colorTitle, dp(18));
        selectedColorText = new TextView(this);
        selectedColorText.setTextSize(14);
        selectedColorText.setTypeface(Typeface.DEFAULT_BOLD);
        selectedColorText.setTextColor(0xFF172033);
        selectedColorText.setPadding(0, 0, 0, dp(4));
        addWithTopMargin(root, selectedColorText, dp(4));

        LinearLayout colorRowOne = new LinearLayout(this);
        colorRowOne.setOrientation(LinearLayout.HORIZONTAL);
        colorRowOne.addView(colorButton("White", 0xFFFFFF), weightButton());
        colorRowOne.addView(colorButton("Red", 0xFF0000), weightButtonWithLeftMargin());
        colorRowOne.addView(colorButton("Green", 0x00FF00), weightButtonWithLeftMargin());
        addWithTopMargin(root, colorRowOne, dp(6));

        LinearLayout colorRowTwo = new LinearLayout(this);
        colorRowTwo.setOrientation(LinearLayout.HORIZONTAL);
        colorRowTwo.addView(colorButton("Blue", 0x0000FF), weightButton());
        colorRowTwo.addView(colorButton("Pink", 0xFF2D95), weightButtonWithLeftMargin());
        colorRowTwo.addView(colorButton("Cyan", 0x00D4FF), weightButtonWithLeftMargin());
        addWithTopMargin(root, colorRowTwo, dp(6));

        LinearLayout ledRow = new LinearLayout(this);
        ledRow.setOrientation(LinearLayout.HORIZONTAL);
        showColorButton = actionButton("Show Color");
        showColorButton.setOnClickListener(v -> showSelectedColor());
        hideColorButton = actionButton("Hide Color");
        hideColorButton.setOnClickListener(v -> hideColor());
        ledRow.addView(showColorButton, weightButton());
        ledRow.addView(hideColorButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, ledRow, gap);

        breatheColorButton = actionButton("Breathe Color");
        breatheColorButton.setOnClickListener(v -> breatheSelectedColor());
        addWithTopMargin(root, breatheColorButton, dp(6));

        TextView configTitle = sectionTitle("Stored Color Settings");
        addWithTopMargin(root, configTitle, dp(18));
        LinearLayout configRowOne = new LinearLayout(this);
        configRowOne.setOrientation(LinearLayout.HORIZONTAL);
        setTouchColorButton = actionButton("Set Touch");
        setTouchColorButton.setOnClickListener(v -> writeColorConfig(CONFIG_TOUCH_COLOR, "TOUCH_COLOR"));
        setDoubleTapColorButton = actionButton("Set Double Tap");
        setDoubleTapColorButton.setOnClickListener(v -> writeColorConfig(CONFIG_DOUBLE_TAP_COLOR, "DOUBLE_TAP_COLOR"));
        configRowOne.addView(setTouchColorButton, weightButton());
        configRowOne.addView(setDoubleTapColorButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, configRowOne, gap);

        LinearLayout configRowTwo = new LinearLayout(this);
        configRowTwo.setOrientation(LinearLayout.HORIZONTAL);
        setWarningColorButton = actionButton("Set Warning");
        setWarningColorButton.setOnClickListener(v -> writeColorConfig(CONFIG_TOUCH_WARNING_COLOR, "TOUCH_WARNING_COLOR"));
        setErrorColorButton = actionButton("Set Error");
        setErrorColorButton.setOnClickListener(v -> writeColorConfig(CONFIG_ERROR_COLOR, "ERROR_COLOR"));
        configRowTwo.addView(setWarningColorButton, weightButton());
        configRowTwo.addView(setErrorColorButton, weightButtonWithLeftMargin());
        addWithTopMargin(root, configRowTwo, dp(6));

        TextView logTitle = sectionTitle("Protocol Log");
        addWithTopMargin(root, logTitle, dp(18));
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(0xFF172033);
        logText.setMinLines(18);
        logText.setMovementMethod(new ScrollingMovementMethod());
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        logText.setBackground(makeBlockColor(0xFFFFFFFF));
        addWithTopMargin(root, logText, dp(4));

        Button clearLog = quietButton("Clear Log");
        clearLog.setOnClickListener(v -> logText.setText(""));
        addWithTopMargin(root, clearLog, gap);

        setContentView(scrollView);
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF344054);
        return title;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button quietButton(String text) {
        Button button = actionButton(text);
        button.setTextColor(0xFF344054);
        return button;
    }

    private Button colorButton(String name, int rgb) {
        Button button = actionButton(name);
        button.setTextColor(isLightColor(rgb) ? 0xFF111827 : 0xFFFFFFFF);
        button.setBackgroundColor(0xFF000000 | rgb);
        button.setOnClickListener(v -> setSelectedColor(name, rgb));
        return button;
    }

    private boolean isLightColor(int rgb) {
        int red = colorRed(rgb);
        int green = colorGreen(rgb);
        int blue = colorBlue(rgb);
        return red * 299 + green * 587 + blue * 114 > 160000;
    }

    private void setSelectedColor(String name, int rgb) {
        selectedColorName = name;
        selectedColor = rgb & 0xFFFFFF;
        log("Selected color " + selectedColorName + " #" + colorHex(selectedColor) + ".");
        updateButtons();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightButtonWithLeftMargin() {
        LinearLayout.LayoutParams params = weightButton();
        params.leftMargin = dp(8);
        return params;
    }

    private void addWithTopMargin(LinearLayout root, View view, int topMargin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = topMargin;
        root.addView(view, params);
    }

    private android.graphics.drawable.GradientDrawable makeBlockColor(int color) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(dp(1), 0xFFE2E8F0);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
            }, REQ_BLE_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BLE_PERMISSIONS);
        }
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERMISSION);
    }

    private void beginPhoneAudioCapturePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log("Phone playback capture requires Android 10 or newer.");
            return;
        }
        if (!hasAudioPermission()) {
            requestAudioPermission();
            return;
        }
        if (mediaProjectionManager == null) {
            log("MediaProjection service is not available.");
            return;
        }
        log("Opening Android phone-audio capture permission prompt.");
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQ_PLAYBACK_CAPTURE);
    }

    private void loadPhoneBassSettings() {
        phoneBassSensitivity = prefs.getInt(KEY_PHONE_BASS_SENSITIVITY, 65);
        phoneBassTriggerLevel = prefs.getInt(KEY_PHONE_BASS_TRIGGER, 18);
        phoneBassBeatThreshold = prefs.getInt(KEY_PHONE_BASS_BEAT_THRESHOLD, 128);
        phoneBassCooldownMs = prefs.getInt(KEY_PHONE_BASS_COOLDOWN, 95);
        phoneBassPulseLimit = prefs.getInt(KEY_PHONE_BASS_PULSE_LIMIT, 30);
        phoneBassMinStrength = prefs.getInt(KEY_PHONE_BASS_MIN_STRENGTH, 18);
        phoneBassMaxStrength = prefs.getInt(KEY_PHONE_BASS_MAX_STRENGTH, 82);
        phoneBassPulseDurationMs = prefs.getInt(KEY_PHONE_BASS_DURATION, 65);
        phoneBassPattern = prefs.getString(KEY_PHONE_BASS_PATTERN, "single");
        phoneBassColorsEnabled = prefs.getBoolean(KEY_PHONE_BASS_COLORS, true);
        phoneBassColor = prefs.getInt(KEY_PHONE_BASS_COLOR, 0x37B8FF);
        sanitizePhoneBassSettings();
    }

    private void savePhoneBassSettings() {
        sanitizePhoneBassSettings();
        prefs.edit()
                .putInt(KEY_PHONE_BASS_SENSITIVITY, phoneBassSensitivity)
                .putInt(KEY_PHONE_BASS_TRIGGER, phoneBassTriggerLevel)
                .putInt(KEY_PHONE_BASS_BEAT_THRESHOLD, phoneBassBeatThreshold)
                .putInt(KEY_PHONE_BASS_COOLDOWN, phoneBassCooldownMs)
                .putInt(KEY_PHONE_BASS_PULSE_LIMIT, phoneBassPulseLimit)
                .putInt(KEY_PHONE_BASS_MIN_STRENGTH, phoneBassMinStrength)
                .putInt(KEY_PHONE_BASS_MAX_STRENGTH, phoneBassMaxStrength)
                .putInt(KEY_PHONE_BASS_DURATION, phoneBassPulseDurationMs)
                .putString(KEY_PHONE_BASS_PATTERN, phoneBassPattern)
                .putBoolean(KEY_PHONE_BASS_COLORS, phoneBassColorsEnabled)
                .putInt(KEY_PHONE_BASS_COLOR, phoneBassColor)
                .apply();
        updateButtons();
    }

    private void sanitizePhoneBassSettings() {
        phoneBassSensitivity = clamp(phoneBassSensitivity, 1, 200);
        phoneBassTriggerLevel = clamp(phoneBassTriggerLevel, 1, 100);
        phoneBassBeatThreshold = clamp(phoneBassBeatThreshold, 105, 250);
        phoneBassCooldownMs = clamp(phoneBassCooldownMs, 45, 2000);
        phoneBassPulseLimit = clamp(phoneBassPulseLimit, 4, 80);
        phoneBassMinStrength = clamp(phoneBassMinStrength, 0, 100);
        phoneBassMaxStrength = clamp(phoneBassMaxStrength, phoneBassMinStrength, 100);
        phoneBassPulseDurationMs = clamp(phoneBassPulseDurationMs, 20, 500);
        phoneBassColor &= 0xFFFFFF;
        if (!isPhoneBassPattern(phoneBassPattern)) {
            phoneBassPattern = "single";
        }
    }

    private boolean isPhoneBassPattern(String pattern) {
        if (pattern == null) {
            return false;
        }
        for (String candidate : PHONE_BASS_PATTERNS) {
            if (candidate.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private void adjustPhoneBassSensitivity(int delta) {
        phoneBassSensitivity += delta;
        savePhoneBassSettings();
    }

    private void adjustPhoneBassTrigger(int delta) {
        phoneBassTriggerLevel += delta;
        savePhoneBassSettings();
    }

    private void adjustPhoneBassBeatThreshold(int delta) {
        phoneBassBeatThreshold += delta;
        savePhoneBassSettings();
    }

    private void adjustPhoneBassCooldown(int delta) {
        phoneBassCooldownMs += delta;
        savePhoneBassSettings();
    }

    private void adjustPhoneBassStrength(int delta) {
        phoneBassMinStrength += delta;
        phoneBassMaxStrength += delta;
        savePhoneBassSettings();
    }

    private void adjustPhoneBassDuration(int delta) {
        phoneBassPulseDurationMs += delta;
        savePhoneBassSettings();
    }

    private void cyclePhoneBassPattern() {
        int index = 0;
        for (int i = 0; i < PHONE_BASS_PATTERNS.length; i++) {
            if (PHONE_BASS_PATTERNS[i].equals(phoneBassPattern)) {
                index = i;
                break;
            }
        }
        phoneBassPattern = PHONE_BASS_PATTERNS[(index + 1) % PHONE_BASS_PATTERNS.length];
        savePhoneBassSettings();
    }

    private void togglePhoneBassColors() {
        phoneBassColorsEnabled = !phoneBassColorsEnabled;
        if (phoneBassColorsEnabled) {
            phoneBassColor = selectedColor & 0xFFFFFF;
        }
        savePhoneBassSettings();
    }

    private void resetPhoneBassDefaults() {
        phoneBassSensitivity = 65;
        phoneBassTriggerLevel = 18;
        phoneBassBeatThreshold = 128;
        phoneBassCooldownMs = 95;
        phoneBassPulseLimit = 30;
        phoneBassMinStrength = 18;
        phoneBassMaxStrength = 82;
        phoneBassPulseDurationMs = 65;
        phoneBassPattern = "single";
        phoneBassColorsEnabled = true;
        phoneBassColor = 0x37B8FF;
        savePhoneBassSettings();
    }

    private void loadHapticSettings() {
        minStrength = 0;
        maxStrength = 100;
        minDurationMs = 10;
        maxDurationMs = 5000;
        intensityScale = 100;
        globalCooldownMs = 0;
        burstLimit = 40;
        colorsEnabled = true;
        predictiveEnabled = false;
        screenOffLocksEnabled = prefs.getBoolean(KEY_SCREEN_OFF_LOCKS, true);
        hapticProfile = "Minecraft";
        sanitizeHapticSettings();
    }

    private void saveHapticSettings() {
        sanitizeHapticSettings();
        prefs.edit()
                .putInt(KEY_MIN_STRENGTH, minStrength)
                .putInt(KEY_MAX_STRENGTH, maxStrength)
                .putInt(KEY_MIN_DURATION, minDurationMs)
                .putInt(KEY_MAX_DURATION, maxDurationMs)
                .putInt(KEY_INTENSITY_SCALE, intensityScale)
                .putInt(KEY_GLOBAL_COOLDOWN, globalCooldownMs)
                .putInt(KEY_BURST_LIMIT, burstLimit)
                .putBoolean(KEY_COLORS_ENABLED, colorsEnabled)
                .putBoolean(KEY_PREDICTIVE_ENABLED, predictiveEnabled)
                .putBoolean(KEY_SCREEN_OFF_LOCKS, screenOffLocksEnabled)
                .putString(KEY_PROFILE, hapticProfile)
                .apply();
        updateButtons();
    }

    private void sanitizeHapticSettings() {
        minStrength = clamp(minStrength, 0, 100);
        maxStrength = clamp(maxStrength, minStrength, 100);
        minDurationMs = clamp(minDurationMs, 10, 2000);
        maxDurationMs = clamp(maxDurationMs, minDurationMs, 5000);
        intensityScale = clamp(intensityScale, 20, 180);
        globalCooldownMs = clamp(globalCooldownMs, 0, 2000);
        burstLimit = clamp(burstLimit, 4, 60);
        if (hapticProfile == null || hapticProfile.trim().isEmpty()) {
            hapticProfile = "Balanced";
        }
    }

    private void adjustMinStrength(int delta) {
        minStrength += delta;
        if (minStrength > maxStrength) {
            maxStrength = minStrength;
        }
        saveHapticSettings();
    }

    private void adjustMaxStrength(int delta) {
        maxStrength += delta;
        if (maxStrength < minStrength) {
            minStrength = maxStrength;
        }
        saveHapticSettings();
    }

    private void adjustMinDuration(int delta) {
        minDurationMs += delta;
        if (minDurationMs > maxDurationMs) {
            maxDurationMs = minDurationMs;
        }
        saveHapticSettings();
    }

    private void adjustMaxDuration(int delta) {
        maxDurationMs += delta;
        if (maxDurationMs < minDurationMs) {
            minDurationMs = maxDurationMs;
        }
        saveHapticSettings();
    }

    private void adjustIntensityScale(int delta) {
        intensityScale += delta;
        saveHapticSettings();
    }

    private void adjustGlobalCooldown(int delta) {
        globalCooldownMs += delta;
        saveHapticSettings();
    }

    private void adjustBurstLimit(int delta) {
        burstLimit += delta;
        saveHapticSettings();
    }

    private void toggleColors() {
        colorsEnabled = !colorsEnabled;
        saveHapticSettings();
    }

    private void togglePredictive() {
        predictiveEnabled = !predictiveEnabled;
        saveHapticSettings();
    }

    private void toggleScreenOffLocks() {
        screenOffLocksEnabled = !screenOffLocksEnabled;
        saveHapticSettings();
        applyPowerLocks();
    }

    private void cycleHapticProfile() {
        if ("Balanced".equals(hapticProfile)) {
            hapticProfile = "Soft";
            minStrength = 10;
            maxStrength = 70;
            intensityScale = 75;
            globalCooldownMs = 140;
            burstLimit = 14;
        } else if ("Soft".equals(hapticProfile)) {
            hapticProfile = "Strong";
            minStrength = 30;
            maxStrength = 100;
            intensityScale = 125;
            globalCooldownMs = 70;
            burstLimit = 22;
        } else {
            hapticProfile = "Balanced";
            minStrength = 20;
            maxStrength = 100;
            intensityScale = 100;
            globalCooldownMs = 90;
            burstLimit = 18;
        }
        saveHapticSettings();
    }

    private void resetHapticDefaults() {
        hapticProfile = "Balanced";
        minStrength = 20;
        maxStrength = 100;
        minDurationMs = 35;
        maxDurationMs = 900;
        intensityScale = 100;
        globalCooldownMs = 90;
        burstLimit = 18;
        colorsEnabled = true;
        predictiveEnabled = true;
        screenOffLocksEnabled = true;
        saveHapticSettings();
        applyPowerLocks();
    }

    private String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private void updateButtons() {
        boolean hasAdapter = adapter != null;
        boolean permissions = hasBlePermissions();
        boolean saved = prefs.contains(KEY_ADDRESS);
        boolean connected = gatt != null;

        String address = prefs.getString(KEY_ADDRESS, null);
        String savedName = prefs.getString(KEY_NAME, "saved band");
        String state = ready ? "READY" : connected ? "CONNECTED / AUTHING" : scanning ? "SCANNING" : "IDLE";
        String savedLine = address == null ? "No saved band" : "Saved: " + savedName + " (" + address + ")";
        statusText.setText("State: " + state + "\nGeneration: " + generation + "\n" + savedLine);
        if (bridgeText != null) {
            bridgeText.setText("Minecraft bridge: " + (bridgeServerRunning ? "LISTENING" : "STOPPED")
                    + "\nPhone endpoint: http://" + localIpAddress() + ":" + BRIDGE_PORT + "/event"
                    + "\nUSB option: adb forward tcp:" + BRIDGE_PORT + " tcp:" + BRIDGE_PORT
                    + " then use http://127.0.0.1:" + BRIDGE_PORT + "/event");
        }
        if (selectedColorText != null) {
            selectedColorText.setText("Selected: " + selectedColorName + " #" + colorHex(selectedColor));
        }
        if (phoneBassText != null) {
            int bassLevel = clamp((int) Math.round(phoneBassLatestLevel), 0, 100);
            int lastStrength = clamp((int) Math.round(phoneBassLatestStrength), 0, 100);
            phoneBassText.setText("Phone audio bass: " + (phoneBassRunning ? "LISTENING" : "STOPPED")
                    + "\n" + levelBar(bassLevel)
                    + "\nLevel " + bassLevel + "   Last strength " + lastStrength);
        }
        if (phoneBassSettingsText != null) {
            phoneBassSettingsText.setText("Pattern " + phoneBassPattern
                    + "   Sens " + phoneBassSensitivity
                    + "   Trigger " + phoneBassTriggerLevel
                    + "   Beat " + phoneBassBeatThreshold + "%"
                    + "\nCooldown " + phoneBassCooldownMs + "ms"
                    + "   Limit " + phoneBassPulseLimit + "/10s"
                    + "   Power " + phoneBassMinStrength + "-" + phoneBassMaxStrength
                    + "   Dur " + phoneBassPulseDurationMs + "ms"
                    + "\nColor " + (phoneBassColorsEnabled ? "#" + colorHex(phoneBassColor) : "OFF"));
        }
        if (bassPatternButton != null) {
            bassPatternButton.setText("Pattern: " + phoneBassPattern);
        }
        if (bassColorButton != null) {
            bassColorButton.setText("Bass Color: " + (phoneBassColorsEnabled ? "ON" : "OFF"));
        }

        permissionButton.setEnabled(!permissions);
        audioPermissionButton.setEnabled(!phoneBassRunning);
        scanButton.setEnabled(hasAdapter && permissions && !scanning);
        stopScanButton.setEnabled(scanning);
        reconnectButton.setEnabled(hasAdapter && permissions && saved && !connected);
        forgetButton.setEnabled(saved);
        buzzSoftButton.setEnabled(connected);
        buzzStrongButton.setEnabled(connected);
        buzzColorButton.setEnabled(connected);
        pulseButton.setEnabled(connected);
        badAppleButton.setEnabled(connected && !badApplePlaying);
        stopBadAppleButton.setEnabled(connected && badApplePlaying);
        bassStartButton.setEnabled(!phoneBassRunning);
        bassStopButton.setEnabled(phoneBassRunning);
        bassTestButton.setEnabled(connected);
        bassPatternButton.setEnabled(true);
        bassColorButton.setEnabled(true);
        bassResetButton.setEnabled(true);
        bassSensitivityDownButton.setEnabled(true);
        bassSensitivityUpButton.setEnabled(true);
        bassTriggerDownButton.setEnabled(true);
        bassTriggerUpButton.setEnabled(true);
        bassThresholdDownButton.setEnabled(true);
        bassThresholdUpButton.setEnabled(true);
        bassCooldownDownButton.setEnabled(true);
        bassCooldownUpButton.setEnabled(true);
        bassStrengthDownButton.setEnabled(true);
        bassStrengthUpButton.setEnabled(true);
        bassDurationDownButton.setEnabled(true);
        bassDurationUpButton.setEnabled(true);
        showColorButton.setEnabled(connected);
        hideColorButton.setEnabled(connected);
        breatheColorButton.setEnabled(connected);
        setTouchColorButton.setEnabled(connected);
        setDoubleTapColorButton.setEnabled(connected);
        setWarningColorButton.setEnabled(connected);
        setErrorColorButton.setEnabled(connected);
    }

    private String levelBar(int level) {
        int filled = clamp(level / 5, 0, 20);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            builder.append(i < filled ? '#' : '.');
        }
        return builder.append(']').toString();
    }

    private void log(String message) {
        mainHandler.post(() -> {
            String line = String.format(Locale.US, "%1$tH:%1$tM:%1$tS  %2$s\n", System.currentTimeMillis(), message);
            logText.append(line);
            int scrollAmount = logText.getLayout() == null ? 0
                    : logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight();
            if (scrollAmount > 0) {
                logText.scrollTo(0, scrollAmount);
            } else {
                logText.scrollTo(0, 0);
            }
        });
    }

    private void startBridgeServer() {
        if (bridgeServerRunning) {
            return;
        }
        bridgeServerRunning = true;
        bridgeServerThread = new Thread(this::runBridgeServer, "BondPhoneBridge");
        bridgeServerThread.setDaemon(true);
        bridgeServerThread.start();
        updateButtons();
    }

    private void stopBridgeServer() {
        bridgeServerRunning = false;
        Thread thread = bridgeServerThread;
        if (thread != null) {
            thread.interrupt();
            bridgeServerThread = null;
        }
    }

    private void runBridgeServer() {
        try (ServerSocket server = new ServerSocket(BRIDGE_PORT)) {
            server.setSoTimeout(800);
            log("Phone bridge listening on port " + BRIDGE_PORT + ".");
            mainHandler.post(this::updateButtons);
            while (bridgeServerRunning) {
                try {
                    handleBridgeSocket(server.accept());
                } catch (SocketTimeoutException ignored) {
                    // Lets the loop notice bridgeServerRunning changes.
                }
            }
        } catch (IOException ex) {
            if (bridgeServerRunning) {
                log("Phone bridge stopped: " + ex.getMessage());
            }
        } finally {
            bridgeServerRunning = false;
            mainHandler.post(this::updateButtons);
        }
    }

    private void handleBridgeSocket(Socket socket) {
        try (Socket closeableSocket = socket;
             BufferedInputStream input = new BufferedInputStream(closeableSocket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(closeableSocket.getOutputStream())) {
            closeableSocket.setTcpNoDelay(true);
            closeableSocket.setSoTimeout(1200);
            String headers = readHttpHeaders(input);
            if (headers.isEmpty()) {
                return;
            }
            String[] lines = headers.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                writeHttpResponse(output, 400, "Bad Request", "Bad request\n");
                return;
            }

            String method = request[0];
            String path = request[1];
            int contentLength = parseContentLength(lines);
            byte[] bodyBytes = new byte[Math.max(0, contentLength)];
            int offset = 0;
            while (offset < bodyBytes.length) {
                int read = input.read(bodyBytes, offset, bodyBytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            String body = new String(bodyBytes, 0, offset, StandardCharsets.UTF_8);

            if ("GET".equals(method)) {
                writeHttpResponse(output, 200, "OK", "Bond phone bridge listening\n");
                return;
            }
            if (!"POST".equals(method) || !"/event".equals(path)) {
                writeHttpResponse(output, 404, "Not Found", "Use POST /event\n");
                return;
            }

            handleBridgePayload(body);
            writeHttpResponse(output, 200, "OK", "queued\n");
        } catch (IOException ex) {
            log("Bridge socket error: " + ex.getMessage());
        }
    }

    private String readHttpHeaders(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        while (headers.size() < 8192) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            headers.write(value);
            if ((matched == 0 && value == '\r')
                    || (matched == 1 && value == '\n')
                    || (matched == 2 && value == '\r')
                    || (matched == 3 && value == '\n')) {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        return headers.toString(StandardCharsets.US_ASCII.name());
    }

    private int parseContentLength(String[] lines) {
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if ("content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                try {
                    return Math.max(0, Integer.parseInt(line.substring(colon + 1).trim()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private void writeHttpResponse(BufferedOutputStream output, int code, String reason, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bodyBytes);
        output.flush();
    }

    private void handleBridgePayload(String body) {
        try {
            JSONObject payload = new JSONObject(body);
            mainHandler.post(() -> handleBridgeEvent(payload));
        } catch (Exception ex) {
            log("Bridge JSON error: " + ex.getMessage() + " body=" + body);
        }
    }

    private void handleBridgeEvent(JSONObject payload) {
        String event = payload.optString("event", "game_event");
        HapticSpec spec = bridgeSpecFromPayload(payload);
        if (spec == null) {
            spec = specForEvent(event, payload);
        }
        if (!allowBridgeEvent(event, spec)) {
            return;
        }
        if (!payload.optBoolean("final", false) && !payload.has("pattern")) {
            spec = tuneSpec(event, spec);
        }
        if (gatt == null) {
            log("Game event ignored because the band is not connected: " + event);
            return;
        }
        if (opQueue.size() > 8 && spec.priority < 70) {
            log("Game event dropped because BLE queue is busy: " + event);
            return;
        }
        log("Game event " + event + " -> " + spec.pattern + " "
                + spec.strength + "% " + spec.durationMs + "ms"
                + (spec.rgb >= 0 ? " #" + colorHex(spec.rgb) : ""));
        sendBridgePattern(spec);
    }

    private HapticSpec bridgeSpecFromPayload(JSONObject payload) {
        if (!payload.optBoolean("final", false) && !payload.has("pattern")) {
            return null;
        }
        String pattern = payload.optString("pattern", "single").trim();
        if (pattern.isEmpty()) {
            pattern = "single";
        }
        int strength = clamp(payload.optInt("strength", 45), 0, 100);
        int duration = clamp(payload.optInt("duration", payload.optInt("durationMs", 85)), 10, 5000);
        int cooldown = clamp(payload.optInt("cooldown", payload.optInt("cooldownMs", 0)), 0, 10_000);
        int priority = clamp(payload.optInt("priority", 50), 0, 100);
        int rgb = parseColor(payload.optString("color", ""));
        return new HapticSpec(pattern, strength, duration, rgb, cooldown, priority);
    }

    private boolean allowBridgeEvent(String event, HapticSpec spec) {
        long now = System.currentTimeMillis();
        while (!bridgeRecentEventMs.isEmpty() && now - bridgeRecentEventMs.peekFirst() > 10_000) {
            bridgeRecentEventMs.removeFirst();
        }
        if (bridgeRecentEventMs.size() >= burstLimit && spec.priority < 80) {
            log("Game event burst-limited: " + event);
            return false;
        }
        long last = bridgeLastEventMs.getOrDefault(event, -100_000L);
        int cooldown = Math.max(globalCooldownMs, spec.cooldownMs);
        if (now - last < cooldown) {
            return false;
        }
        bridgeLastEventMs.put(event, now);
        bridgeRecentEventMs.addLast(now);
        return true;
    }

    private HapticSpec tuneSpec(String event, HapticSpec spec) {
        int strength = spec.strength;
        int duration = spec.durationMs;
        if (predictiveEnabled && ("step".equals(event) || "sprint_step".equals(event) || "mining".equals(event))) {
            strength = Math.max(8, strength - 6);
            duration = Math.min(duration, 60);
        }
        strength = Math.round(strength * (intensityScale / 100f));
        if (strength > 0) {
            strength = clamp(strength, minStrength, maxStrength);
        }
        duration = clamp(duration, minDurationMs, maxDurationMs);
        int rgb = colorsEnabled ? spec.rgb : -1;
        return new HapticSpec(spec.pattern, strength, duration, rgb, spec.cooldownMs, spec.priority);
    }

    private HapticSpec specForEvent(String event, JSONObject payload) {
        switch (event) {
            case "damage":
                return pulse("single", 82 + (int) (payload.optDouble("amount", 0) * 4), 135, 0xFF3030, 180, 90);
            case "heal":
                return pulse("single", 38, 90, 0x36D675, 450, 45);
            case "low_health":
                return pulse("heartbeat", 72, 120, 0xFF2020, 1600, 85);
            case "death":
                return pulse("long", 100, 900, 0x660000, 2500, 100);
            case "respawn":
                return pulse("double", 55, 120, 0xFFFFFF, 900, 75);
            case "hunger_low":
                return pulse("double", 44, 95, 0xFFD33D, 2400, 50);
            case "air_low":
                return pulse("double", 74, 95, 0x37B8FF, 550, 80);
            case "level_up":
                return pulse("triple", 64, 100, 0x7DFF4D, 1100, 70);
            case "xp_gain":
                return pulse("single", 26, 45, 0x7DFF4D, 350, 25);
            case "step":
                return pulse("single", 18, 32, -1, 110, 15);
            case "sprint_step":
                return pulse("single", 28, 38, -1, 90, 18);
            case "jump":
                return pulse("single", 38, 60, -1, 160, 35);
            case "falling":
                return pulse("ramp", 52 + (int) (Math.abs(payload.optDouble("speedY", 0)) * 18), 70, 0xB38CFF, 420, 55);
            case "land":
                double drop = payload.optDouble("drop", 0);
                return pulse("single", 76 + (int) (Math.max(0, drop - 2) * 7), 150 + (int) (Math.max(0, drop - 2) * 18), 0xC48A4A, 220, 85);
            case "elytra_start":
                return pulse("ramp", 48, 90, 0x5DDCFF, 850, 45);
            case "water_enter":
                return pulse("single", 42, 85, 0x37B8FF, 600, 45);
            case "lava_enter":
                return pulse("triple", 100, 135, 0xFF6B00, 300, 100);
            case "fire":
                return pulse("double", 88, 90, 0xFF4500, 500, 90);
            case "sleep":
                return pulse("breathe", 28, 110, 0x776CFF, 1200, 30);
            case "dimension_change":
                return pulse("long", 72, 180, 0xB38CFF, 1500, 75);
            case "weather_thunder":
                return pulse("long", 86, 170, 0xFFFFFF, 2800, 70);
            case "weather_rain":
                return pulse("double", 26, 55, 0x37B8FF, 2800, 25);
            case "night":
                return pulse("breathe", 34, 100, 0x776CFF, 3200, 30);
            case "mine_start":
                return pulse("single", 30, 45, -1, 130, 28);
            case "mining":
                return pulse("single", 22, 38, -1, 250, 12);
            case "attack_swing":
                return pulse("single", 54, 70, 0xFF3030, 150, 55);
            case "bow_shot":
                return pulse("single", 68 + (int) Math.min(22, payload.optDouble("chargeTicks", 0) / 2), 95, 0xFFD33D, 140, 65);
            case "fishing_cast":
                return pulse("single", 40, 85, 0x37B8FF, 650, 35);
            case "blocking":
                return pulse("single", 52, 80, 0xFFFFFF, 420, 45);
            case "eating":
                return pulse("double", 34, 75, 0xFFD33D, 420, 25);
            case "drinking":
                return pulse("double", 38, 75, 0x37B8FF, 420, 25);
            case "creeper_close":
                return pulse("triple", 100, 105, 0x55FF55, 280, 100);
            case "creeper_warning":
                double distance = payload.optDouble("distance", 8);
                return pulse("double", 66 + (int) (Math.max(0, 8 - distance) * 6), 100, 0x4DFF4D, 700, 75);
            case "hostile_nearby":
                return pulse("double", 54 + (int) Math.min(30, payload.optDouble("count", 0) * 5), 90, 0xFF3030, 1300, 60);
            case "effect_added":
                return pulse("single", 36, 70, 0xB38CFF, 420, 25);
            case "session_start":
                return pulse("single", 40, 90, 0xFFFFFF, 2200, 40);
            default:
                return pulse("single", payload.optInt("strength", 45), payload.optInt("duration", 85), parseColor(payload.optString("color", "")), 500, 30);
        }
    }

    private HapticSpec pulse(String pattern, int strength, int durationMs, int rgb, int cooldownMs, int priority) {
        return new HapticSpec(pattern, strength, durationMs, rgb, cooldownMs, priority);
    }

    private void sendBridgePattern(HapticSpec spec) {
        switch (spec.pattern) {
            case "none":
                return;
            case "double":
                queueBridgePulse(0, spec, "1/2");
                queueBridgePulse(Math.max(160, spec.durationMs + 105), spec, "2/2");
                break;
            case "triple":
                queueBridgePulse(0, spec, "1/3");
                queueBridgePulse(Math.max(160, spec.durationMs + 105), spec, "2/3");
                queueBridgePulse(Math.max(320, (spec.durationMs + 105) * 2), spec, "3/3");
                break;
            case "heartbeat":
                queueBridgePulse(0, spec.withStrength(Math.max(minStrength, spec.strength)), "heartbeat a");
                queueBridgePulse(170, spec.withStrength(Math.max(minStrength, spec.strength - 25)).withDuration(Math.min(spec.durationMs, 105)), "heartbeat b");
                queueBridgePulse(610, spec, "heartbeat c");
                queueBridgePulse(780, spec.withStrength(Math.max(minStrength, spec.strength - 25)).withDuration(Math.min(spec.durationMs, 105)), "heartbeat d");
                break;
            case "long":
                queueBridgePulse(0, spec.withDuration(Math.max(spec.durationMs, 700)), "long");
                break;
            case "ramp":
                for (int i = 0; i < 5; i++) {
                    int localStrength = clamp(spec.strength - 40 + i * 15, minStrength, maxStrength);
                    queueBridgePulse(i * 160, spec.withStrength(localStrength).withDuration(Math.min(spec.durationMs, 120)), "ramp " + (i + 1));
                }
                break;
            case "rise":
                for (int i = 0; i < 4; i++) {
                    int localStrength = clamp(spec.strength - 30 + i * 12, minStrength, maxStrength);
                    queueBridgePulse(i * 95, spec.withStrength(localStrength).withDuration(Math.min(spec.durationMs, 95)), "rise " + (i + 1));
                }
                break;
            case "fall":
                for (int i = 0; i < 4; i++) {
                    int localStrength = clamp(spec.strength - i * 12, minStrength, maxStrength);
                    queueBridgePulse(i * 105, spec.withStrength(localStrength).withDuration(Math.min(spec.durationMs, 110)), "fall " + (i + 1));
                }
                break;
            case "wave":
                queueBridgePulse(0, spec.withStrength(clamp(spec.strength - 12, minStrength, maxStrength)).withDuration(Math.min(spec.durationMs, 90)), "wave 1");
                queueBridgePulse(110, spec.withStrength(clamp(spec.strength + 8, minStrength, maxStrength)).withDuration(Math.min(spec.durationMs + 20, 140)), "wave 2");
                queueBridgePulse(250, spec.withStrength(clamp(spec.strength - 8, minStrength, maxStrength)).withDuration(Math.min(spec.durationMs, 110)), "wave 3");
                break;
            case "stutter":
                for (int i = 0; i < 4; i++) {
                    queueBridgePulse(i * 80, spec.withDuration(Math.min(spec.durationMs, 55)), "stutter " + (i + 1));
                }
                break;
            case "warning":
                queueBridgePulse(0, spec, "warning 1");
                queueBridgePulse(180, spec.withStrength(clamp(spec.strength + 10, minStrength, maxStrength)), "warning 2");
                queueBridgePulse(360, spec.withStrength(clamp(spec.strength + 20, minStrength, maxStrength)), "warning 3");
                break;
            case "breathe":
                sendBridgeBreathe(spec.rgb >= 0 ? spec.rgb : selectedColor);
                if (spec.strength > 0) {
                    queueBridgePulse(0, spec, "breathe pulse");
                }
                break;
            default:
                queueBridgePulse(0, spec, "single");
                break;
        }
    }

    private void queueBridgePulse(int delayMs, HapticSpec spec, String label) {
        if (delayMs <= 0) {
            sendBridgePulse(spec, label);
        } else {
            mainHandler.postDelayed(() -> sendBridgePulse(spec, label), delayMs);
        }
    }

    private void sendBridgePulse(HapticSpec spec, String label) {
        int rgb = spec.rgb & 0xFFFFFF;
        int ledMode = spec.rgb >= 0 ? LED_ON : LED_OFF;
        int ledMs = spec.rgb >= 0 ? Math.max(160, spec.durationMs) : 0;
        byte[] payload = buildLedMotorCommand(
                spec.strength,
                spec.durationMs,
                ledMode,
                spec.rgb >= 0 ? colorRed(rgb) : 0,
                spec.rgb >= 0 ? colorGreen(rgb) : 0,
                spec.rgb >= 0 ? colorBlue(rgb) : 0,
                spec.rgb >= 0 ? 80 : 0,
                0,
                0,
                0,
                ledMs);
        sendCommandPayload(payload, "game " + label);
    }

    private void sendBridgeBreathe(int rgb) {
        int cleanRgb = rgb & 0xFFFFFF;
        byte[] payload = buildLedMotorCommand(
                0,
                0,
                LED_BREATHE,
                colorRed(cleanRgb),
                colorGreen(cleanRgb),
                colorBlue(cleanRgb),
                75,
                180,
                360,
                720,
                2500);
        sendCommandPayload(payload, "game breathe #" + colorHex(cleanRgb));
    }

    private int parseColor(String value) {
        if (value == null) {
            return -1;
        }
        String clean = value.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.startsWith("0x") || clean.startsWith("0X")) {
            clean = clean.substring(2);
        }
        if (clean.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(clean, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private JSONObject testPayload(String event) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("source", "phone_test");
            payload.put("event", event);
            payload.put("amount", 3.0);
            payload.put("drop", 4.5);
            payload.put("distance", 2.4);
            payload.put("count", 3);
            payload.put("speedY", -0.9);
            payload.put("chargeTicks", 20);
        } catch (Exception ignored) {
        }
        return payload;
    }

    @SuppressLint("MissingPermission")
    private void startPhoneBass() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log("Phone playback capture requires Android 10 or newer.");
            return;
        }
        if (!hasAudioPermission()) {
            requestAudioPermission();
            return;
        }
        if (phoneBassProjection == null) {
            beginPhoneAudioCapturePermission();
            return;
        }
        if (phoneBassRunning) {
            return;
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                PHONE_BASS_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            log("Phone bass could not get an audio buffer size: " + minBuffer);
            return;
        }
        int bufferSize = Math.max(minBuffer * 2, PHONE_BASS_SAMPLE_RATE / 8);
        AudioRecord record = createPhonePlaybackRecorder(bufferSize);
        if (record == null) {
            log("Phone bass could not initialize phone playback capture.");
            return;
        }

        phoneBassRecord = record;
        phoneBassRunning = true;
        phoneBassLatestLevel = 0;
        phoneBassLatestStrength = 0;
        phoneBassFastLevel = 0;
        phoneBassSlowLevel = 10.0;
        phoneBassLowPass = 0;
        phoneBassThread = new Thread(() -> runPhoneBassLoop(record, bufferSize), "PhoneBassHaptics");
        phoneBassThread.start();
        log("Phone audio bass detector started. Some apps block playback capture, so test with a few music/video apps if the meter stays flat.");
        updateButtons();
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createPhonePlaybackRecorder(int bufferSize) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || phoneBassProjection == null) {
            return null;
        }
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(phoneBassProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PHONE_BASS_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                return record;
            }
            record.release();
        } catch (Exception ex) {
            log("Playback capture setup failed: " + ex.getMessage());
        }
        return null;
    }

    private void runPhoneBassLoop(AudioRecord record, int bufferSize) {
        short[] samples = new short[Math.max(1024, bufferSize / 2)];
        double alpha = 1.0 - Math.exp(-2.0 * Math.PI * PHONE_BASS_CUTOFF_HZ / PHONE_BASS_SAMPLE_RATE);
        ArrayDeque<Long> recentPulses = new ArrayDeque<>();
        long lastPulseMs = -100_000L;
        long lastUiMs = 0;
        try {
            record.startRecording();
            while (phoneBassRunning && !Thread.currentThread().isInterrupted()) {
                int count = record.read(samples, 0, samples.length);
                if (count <= 0) {
                    continue;
                }
                double level = analyzePhoneBass(samples, count, alpha);
                long now = System.currentTimeMillis();
                phoneBassLatestLevel = level;
                phoneBassFastLevel += 0.35 * (level - phoneBassFastLevel);
                phoneBassSlowLevel += 0.03 * (level - phoneBassSlowLevel);

                while (!recentPulses.isEmpty() && now - recentPulses.peekFirst() > 10_000) {
                    recentPulses.removeFirst();
                }

                boolean cooldownReady = now - lastPulseMs >= phoneBassCooldownMs;
                boolean beatReady = phoneBassFastLevel >= phoneBassTriggerLevel
                        && phoneBassFastLevel >= phoneBassSlowLevel * phoneBassBeatThreshold / 100.0;
                boolean strongSustain = phoneBassFastLevel >= Math.min(100, phoneBassTriggerLevel + 28);
                boolean shouldPulse = cooldownReady
                        && recentPulses.size() < phoneBassPulseLimit
                        && (beatReady || strongSustain);

                if (shouldPulse) {
                    double intensity = Math.max(0.0, Math.min(1.0,
                            (phoneBassFastLevel - phoneBassTriggerLevel) / Math.max(1.0, 100.0 - phoneBassTriggerLevel)));
                    int strength = phoneBassMinStrength
                            + (int) Math.round((phoneBassMaxStrength - phoneBassMinStrength) * intensity);
                    int duration = phoneBassPulseDurationMs + (int) Math.round(35 * intensity);
                    strength = clamp(strength, phoneBassMinStrength, phoneBassMaxStrength);
                    duration = clamp(duration, 20, 500);
                    phoneBassLatestStrength = strength;
                    lastPulseMs = now;
                    recentPulses.addLast(now);
                    sendPhoneBassPulse(strength, duration, level);
                }

                if (now - lastUiMs > 120) {
                    lastUiMs = now;
                    mainHandler.post(this::updateButtons);
                }
            }
        } catch (Exception ex) {
            if (phoneBassRunning) {
                log("Phone bass capture stopped: " + ex.getMessage());
            }
        } finally {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            try {
                record.release();
            } catch (Exception ignored) {
            }
            if (phoneBassRecord == record) {
                phoneBassRecord = null;
            }
            phoneBassRunning = false;
            mainHandler.post(this::updateButtons);
        }
    }

    private double analyzePhoneBass(short[] samples, int count, double alpha) {
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            double mono = samples[i] / 32768.0;
            phoneBassLowPass += alpha * (mono - phoneBassLowPass);
            sum += phoneBassLowPass * phoneBassLowPass;
        }
        double rms = Math.sqrt(sum / Math.max(1, count));
        double sensitivityScale = 0.45 + phoneBassSensitivity / 42.0;
        return Math.max(0.0, Math.min(100.0, rms * 1000.0 * sensitivityScale));
    }

    private void stopPhoneBass() {
        phoneBassRunning = false;
        AudioRecord record = phoneBassRecord;
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
        Thread thread = phoneBassThread;
        if (thread != null) {
            thread.interrupt();
            phoneBassThread = null;
        }
        stopPhoneBassProjection();
        updateButtons();
    }

    private void stopPhoneBassProjection() {
        MediaProjection projection = phoneBassProjection;
        phoneBassProjection = null;
        if (projection != null) {
            try {
                if (phoneBassProjectionCallback != null) {
                    projection.unregisterCallback(phoneBassProjectionCallback);
                }
            } catch (Exception ignored) {
            }
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
        }
        phoneBassProjectionCallback = null;
    }

    private void sendPhoneBassTestPulse() {
        int strength = Math.max(35, phoneBassMinStrength);
        int rgb = phoneBassColorsEnabled ? phoneBassColor : -1;
        log("Sending phone bass test pulse.");
        sendBridgePattern(new HapticSpec(phoneBassPattern, strength, phoneBassPulseDurationMs, rgb, 0, 90));
    }

    private void sendPhoneBassPulse(int strength, int duration, double bassLevel) {
        int rgb = phoneBassColorsEnabled ? phoneBassColor : -1;
        mainHandler.post(() -> {
            if (gatt == null) {
                return;
            }
            HapticSpec spec = new HapticSpec(
                    phoneBassPattern,
                    strength,
                    duration,
                    rgb,
                    Math.max(0, phoneBassCooldownMs - 10),
                    74);
            sendBridgePattern(spec);
        });
    }

    private void applyPowerLocks() {
        if (!screenOffLocksEnabled) {
            releasePowerLocks();
            return;
        }
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BondBandBridge:GameBridge");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
                log("Screen-off CPU bridge lock acquired.");
            }
        } catch (Exception ex) {
            log("Could not acquire CPU lock: " + ex.getMessage());
        }
        try {
            if (wifiLock == null) {
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wifiManager.createWifiLock(mode, "BondBandBridgeGameBridge");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                log("Screen-off Wi-Fi bridge lock acquired.");
            }
        } catch (Exception ex) {
            log("Could not acquire Wi-Fi lock: " + ex.getMessage());
        }
    }

    private void releasePowerLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private String localIpAddress() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
        return "127.0.0.1";
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (adapter == null) {
            log("Bluetooth adapter not available.");
            return;
        }
        if (!adapter.isEnabled()) {
            log("Bluetooth is disabled. Opening Bluetooth settings.");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        if (adapter.getBluetoothLeScanner() == null) {
            log("BLE scanner not available yet. Toggle Bluetooth and try again.");
            return;
        }

        scanResults.clear();
        resultsLayout.removeAllViews();
        scanning = true;
        ready = false;
        updateButtons();
        log("Scanning for Bond BLE advertisements. Tap/wake the band now.");
        adapter.getBluetoothLeScanner().startScan(scanCallback);
        mainHandler.postDelayed(() -> {
            if (scanning) {
                stopScan();
            }
        }, 15000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning || adapter == null || adapter.getBluetoothLeScanner() == null || !hasBlePermissions()) {
            scanning = false;
            updateButtons();
            return;
        }
        adapter.getBluetoothLeScanner().stopScan(scanCallback);
        scanning = false;
        log("Scan stopped.");
        updateButtons();
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
            log("Scan failed: " + errorCode);
            updateButtons();
        }
    };

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null || !hasBlePermissions()) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        String name = advertisementName(result);
        String address = device.getAddress();
        String savedAddress = prefs.getString(KEY_ADDRESS, null);
        boolean matchesSaved = savedAddress != null && savedAddress.equals(address);
        boolean matchesBond = isBondBandName(name);
        if (!matchesSaved && !matchesBond) {
            return;
        }
        if (scanResults.containsKey(address)) {
            return;
        }
        scanResults.put(address, result);
        mainHandler.post(() -> addScanResultButton(result, name, matchesSaved));
        log("Found " + safeName(name) + " " + address + " RSSI " + result.getRssi()
                + (matchesSaved ? " (saved)" : ""));
    }

    @SuppressLint("MissingPermission")
    private String advertisementName(ScanResult result) {
        ScanRecord scanRecord = result.getScanRecord();
        String name = scanRecord == null ? null : scanRecord.getDeviceName();
        if (name == null && result.getDevice() != null) {
            name = result.getDevice().getName();
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
                || normalized.startsWith("BOND")
                || normalized.equals("BOND MORE");
    }

    private String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "(unnamed)" : name;
    }

    @SuppressLint("MissingPermission")
    private void addScanResultButton(ScanResult result, String name, boolean saved) {
        BluetoothDevice device = result.getDevice();
        String label = safeName(name) + "  " + device.getAddress() + "  RSSI " + result.getRssi();
        if (saved) {
            label += "  saved";
        }
        Button button = actionButton(label);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(v -> connectToDevice(device, safeName(name)));
        addWithTopMargin(resultsLayout, button, dp(6));
    }

    @SuppressLint("MissingPermission")
    private void connectSaved() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        String address = prefs.getString(KEY_ADDRESS, null);
        if (address == null) {
            log("No saved band address yet.");
            return;
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        connectToDevice(device, prefs.getString(KEY_NAME, "Bond band"));
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device, String name) {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        stopScan();
        closeGatt();
        connectedAddress = device.getAddress();
        prefs.edit()
                .putString(KEY_ADDRESS, connectedAddress)
                .putString(KEY_NAME, safeName(name))
                .apply();
        ready = false;
        initQueued = false;
        firmwareReadQueued = false;
        generation = "connecting";
        updateButtons();
        log("Connecting to " + safeName(name) + " " + connectedAddress + ".");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(this, false, gattCallback);
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        clearQueue();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        ready = false;
        operationInFlight = false;
        updateButtons();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            log("GATT connection state status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = currentGatt;
                log("Connected. Discovering services.");
                mainHandler.post(MainActivity.this::updateButtons);
                currentGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected.");
                ready = false;
                badApplePlaying = false;
                badAppleToken++;
                generation = "disconnected";
                clearQueue();
                if (gatt == currentGatt) {
                    gatt = null;
                }
                mainHandler.post(MainActivity.this::updateButtons);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            log("Services discovered status=" + status + ".");
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }
            dumpServices(currentGatt);
            handleServicesDiscovered(currentGatt);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic, int status) {
            handleCharacteristicRead(characteristic, characteristic.getValue(), status);
            finishOperation();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            handleCharacteristicRead(characteristic, value, status);
            finishOperation();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic, int status) {
            log("Write " + shortUuid(characteristic.getUuid()) + " status=" + status);
            finishOperation();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor descriptor, int status) {
            log("Descriptor write " + shortUuid(descriptor.getCharacteristic().getUuid()) + " status=" + status);
            finishOperation();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic) {
            handleCharacteristicChanged(characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleCharacteristicChanged(characteristic, value);
        }
    };

    private void dumpServices(BluetoothGatt currentGatt) {
        List<BluetoothGattService> services = currentGatt.getServices();
        log("Service count: " + services.size());
        for (BluetoothGattService service : services) {
            log("Service " + service.getUuid());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                log("  Char " + characteristic.getUuid()
                        + " props=" + String.format(Locale.US, "0x%02X", characteristic.getProperties()));
            }
        }
    }

    private void handleServicesDiscovered(BluetoothGatt currentGatt) {
        if (currentGatt.getService(LEGACY_USER_APP_SERVICE) == null) {
            log("Expected legacy Band 4 service was not found. This app currently targets the Band 4/legacy protocol.");
            generation = "unsupported";
            mainHandler.post(this::updateButtons);
            return;
        }

        BluetoothGattCharacteristic api = findCharacteristic(LEGACY_DEVICE_INFO_SERVICE, LEGACY_API);
        if (api != null) {
            log("Reading API/generation characteristic.");
            enqueueRead(api, "read API generation");
        } else {
            generation = "BT4 assumed";
            log("API/generation characteristic missing; continuing as Band 4 compatible.");
            initializeLegacyBand();
        }
    }

    private void handleCharacteristicRead(BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        UUID uuid = characteristic.getUuid();
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("Read " + shortUuid(uuid) + " failed status=" + status);
            if (uuid.equals(LEGACY_API) && !initQueued) {
                generation = "BT4 assumed";
                initializeLegacyBand();
            }
            return;
        }
        log("Read " + shortUuid(uuid) + " = " + hex(value));
        if (uuid.equals(LEGACY_API)) {
            generation = decodeGeneration(value);
            log("Decoded generation: " + generation + ".");
            initializeLegacyBand();
        } else if (uuid.equals(FFA4_STATE)) {
            handleState(value, "read");
        } else if (uuid.equals(STANDARD_BATTERY_LEVEL) || uuid.equals(LEGACY_BATTERY_LEVEL)) {
            if (value != null && value.length > 0) {
                log("Battery raw level byte: " + (value[0] & 0xFF) + "% / raw " + hex(value));
            }
        } else if (uuid.equals(STANDARD_FIRMWARE_REVISION)) {
            log("Firmware revision: " + printable(value));
        }
    }

    private void handleCharacteristicChanged(BluetoothGattCharacteristic characteristic, byte[] value) {
        UUID uuid = characteristic.getUuid();
        log("Notify " + shortUuid(uuid) + " = " + hex(value));
        if (uuid.equals(FFA4_STATE)) {
            handleState(value, "notify");
        } else if (uuid.equals(FFA5_EVENT)) {
            handleEvent(value);
        } else if (uuid.equals(STANDARD_BATTERY_LEVEL) || uuid.equals(LEGACY_BATTERY_LEVEL)) {
            if (value != null && value.length > 0) {
                log("Battery notify raw: " + (value[0] & 0xFF) + "% / " + hex(value));
            }
        }
    }

    private String decodeGeneration(byte[] value) {
        if (value == null || value.length == 0) {
            return "unknown";
        }
        int marker = value[0] & 0xFF;
        if (marker == 0x42) {
            return "BT2 marker 0x42";
        }
        if (marker == 0x44) {
            return "BT4 marker 0x44";
        }
        if (marker == 0x45) {
            return "Mini marker 0x45";
        }
        return "legacy/unknown marker " + String.format(Locale.US, "0x%02X", marker);
    }

    private void initializeLegacyBand() {
        if (initQueued) {
            return;
        }
        initQueued = true;

        BluetoothGattCharacteristic battery = findCharacteristic(STANDARD_BATTERY_SERVICE, STANDARD_BATTERY_LEVEL);
        if (battery == null) {
            battery = findCharacteristic(LEGACY_DEVICE_INFO_SERVICE, LEGACY_BATTERY_LEVEL);
        }
        BluetoothGattCharacteristic event = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA5_EVENT);
        BluetoothGattCharacteristic state = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA4_STATE);
        BluetoothGattCharacteristic extra = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA8_BAND4_EXTRA);

        if (battery != null) {
            enqueueRead(battery, "read battery");
            enqueueNotify(battery, true, "enable battery notify");
        } else {
            log("Battery characteristic not found.");
        }

        if (event != null) {
            enqueueNotify(event, true, "enable FFA5 event notify");
        } else {
            log("FFA5 event characteristic missing.");
        }
        if (state != null) {
            enqueueNotify(state, true, "enable FFA4 state notify");
        } else {
            log("FFA4 state characteristic missing.");
        }
        if (extra != null) {
            enqueueNotify(extra, true, "enable FFA8 Band 4 notify");
        } else {
            log("FFA8 extra characteristic not present; continuing.");
        }
        if (state != null) {
            enqueueRead(state, "read FFA4 state");
        }
        mainHandler.post(this::updateButtons);
    }

    private void handleState(byte[] value, String source) {
        if (value == null || value.length == 0) {
            log("Empty state payload from " + source + ".");
            return;
        }
        int state = value[0] & 0xFF;
        log("State " + String.format(Locale.US, "0x%02X", state) + " " + stateName(state) + " from " + source + ".");
        switch (state) {
            case 0x82:
                ready = false;
                writeToken();
                break;
            case 0x83:
                ready = false;
                writeAuid();
                break;
            case 0x84:
                ready = false;
                log("Band reports OOBE/AUID error. If it flashes, double tap the band and watch for FFA5 event 0x13.");
                break;
            case 0x85:
                ready = true;
                log("Band is READY. FFA6 vibration commands are enabled.");
                queueFirmwareReadOnce();
                break;
            case 0x86:
            case 0x88:
                log("Band is busy. Wait for the next state notification.");
                break;
            default:
                log("Unknown state. Keeping the connection open for more notifications.");
                break;
        }
        mainHandler.post(this::updateButtons);
    }

    private String stateName(int state) {
        switch (state) {
            case 0x82:
                return "WAITING_TOKEN";
            case 0x83:
                return "WAITING_AUID";
            case 0x84:
                return "OOBE_OR_AUID_ERROR";
            case 0x85:
                return "CONNECTED";
            case 0x86:
                return "MSG_BUSY_OR_WAITING_TOUCH";
            case 0x88:
                return "QUEUE_EXECUTING";
            default:
                return "UNKNOWN";
        }
    }

    private void handleEvent(byte[] value) {
        if (value == null || value.length == 0) {
            log("Empty event payload.");
            return;
        }
        int eventId = value[0] & 0xFF;
        log("Event " + String.format(Locale.US, "0x%02X", eventId) + " " + eventName(eventId) + ".");
        if (eventId == 0x13) {
            log("Band requested pair confirmation. Writing paired-correct command.");
            writePairedCorrect();
        } else if (eventId == 0x11) {
            log("Band accepted AUID.");
        } else if (eventId == 0x12) {
            log("Band rejected AUID. Try forgetting saved band, reconnecting, and double tapping during the white flash.");
        } else if (eventId == 0x16 || eventId == 0x17) {
            log("Touch/double-tap event detected from band.");
        }
    }

    private String eventName(int eventId) {
        switch (eventId) {
            case 0x11:
                return "AUID_RIGHT";
            case 0x12:
                return "AUID_ERROR";
            case 0x13:
                return "PAIRED_SIGNAL";
            case 0x14:
                return "SEND_MSG";
            case 0x15:
                return "SEND_MSG_TIMEOUT";
            case 0x16:
                return "TAP_DETECTED_SIGNAL";
            case 0x17:
                return "NO_ACTION_DOUBLE_TAP_DETECTED";
            default:
                return "UNKNOWN";
        }
    }

    private void writeToken() {
        BluetoothGattCharacteristic token = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA1_TOKEN);
        if (token == null) {
            log("FFA1 token characteristic missing.");
            return;
        }
        byte[] payload = "12345678".getBytes(StandardCharsets.US_ASCII);
        log("Writing auth token to FFA1: " + hex(payload));
        enqueueWrite(token, payload, "write token");
    }

    private void writeAuid() {
        BluetoothGattCharacteristic auid = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA2_AUID);
        if (auid == null) {
            log("FFA2 AUID characteristic missing.");
            return;
        }
        byte[] payload = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(localAuid)
                .array();
        log("Writing local AUID to FFA2: " + hex(payload));
        enqueueWrite(auid, payload, "write AUID");
    }

    private void writePairedCorrect() {
        BluetoothGattCharacteristic command = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA6_COMMAND);
        if (command == null) {
            log("FFA6 command characteristic missing.");
            return;
        }
        byte[] payload = new byte[20];
        payload[0] = CMD_PAIRED_CORRECT;
        payload[2] = 0x11;
        payload[3] = 0x22;
        payload[4] = 0x33;
        payload[5] = 0x44;
        log("Writing paired-correct to FFA6: " + hex(payload));
        enqueueWrite(command, payload, "write paired-correct");
    }

    private void queueFirmwareReadOnce() {
        if (firmwareReadQueued) {
            return;
        }
        firmwareReadQueued = true;
        BluetoothGattCharacteristic firmware = findCharacteristic(STANDARD_DEVICE_INFO_SERVICE, STANDARD_FIRMWARE_REVISION);
        if (firmware != null) {
            enqueueRead(firmware, "read firmware revision");
        }
    }

    private void sendTriplePulse() {
        if (gatt == null) {
            log("No GATT connection.");
            return;
        }
        log("Sending triple pulse.");
        sendVibration(90, 260, LED_OFF, 0, 0, 0, 0, 0);
        mainHandler.postDelayed(() -> sendVibration(90, 260, LED_OFF, 0, 0, 0, 0, 0), 620);
        mainHandler.postDelayed(() -> sendVibration(90, 260, LED_OFF, 0, 0, 0, 0, 0), 1240);
    }

    private void playBadAppleHaptics() {
        if (gatt == null) {
            log("No GATT connection.");
            return;
        }
        badApplePlaying = true;
        int token = ++badAppleToken;
        updateButtons();

        int sixteenthMs = 109;
        int phraseUnits = 32;
        int phraseCount = 8;
        int[] hits = {0, 2, 4, 6, 8, 10, 12, 14, 16, 19, 21, 23, 24, 26, 28, 30};
        log("Starting Bad Apple haptic test: " + phraseCount + " phrases at about 138 BPM.");

        for (int phrase = 0; phrase < phraseCount; phrase++) {
            for (int i = 0; i < hits.length; i++) {
                int eventIndex = phrase * hits.length + i;
                int unit = phrase * phraseUnits + hits[i];
                int delayMs = unit * sixteenthMs;
                int durationMs = badAppleDuration(eventIndex, phrase, i);
                int strength = badAppleStrength(eventIndex, phrase, i);
                mainHandler.postDelayed(() -> {
                    if (badApplePlaying && token == badAppleToken && gatt != null) {
                        sendVibration(strength, durationMs, LED_OFF, 0, 0, 0, 0, 0);
                    }
                }, delayMs);
            }
        }

        int totalMs = phraseCount * phraseUnits * sixteenthMs + 450;
        mainHandler.postDelayed(() -> {
            if (token == badAppleToken) {
                badApplePlaying = false;
                log("Bad Apple haptic test finished.");
                updateButtons();
            }
        }, totalMs);
    }

    private int badAppleDuration(int eventIndex, int phrase, int hitIndex) {
        if (hitIndex == 0 || hitIndex == 8) {
            return 155;
        }
        if ((phrase & 1) == 1 && (hitIndex == 9 || hitIndex == 11)) {
            return 185;
        }
        return eventIndex % 5 == 0 ? 135 : 82;
    }

    private int badAppleStrength(int eventIndex, int phrase, int hitIndex) {
        if (hitIndex == 0 || hitIndex == 8) {
            return 100;
        }
        if ((phrase & 1) == 1 && (hitIndex == 9 || hitIndex == 11)) {
            return 86;
        }
        return eventIndex % 3 == 0 ? 72 : 52;
    }

    private void stopBadAppleHaptics() {
        if (!badApplePlaying) {
            return;
        }
        badAppleToken++;
        badApplePlaying = false;
        clearQueue();
        log("Stopped Bad Apple haptic test and cleared pending BLE writes.");
        updateButtons();
    }

    private void sendColorVibration(int strength, int rgb) {
        int cleanRgb = rgb & 0xFFFFFF;
        byte[] payload = buildLedMotorCommand(
                strength,
                2000,
                LED_ON,
                colorRed(cleanRgb),
                colorGreen(cleanRgb),
                colorBlue(cleanRgb),
                80,
                0,
                0,
                0,
                2000);
        sendCommandPayload(payload, "color vibration #" + colorHex(cleanRgb));
    }

    private void showSelectedColor() {
        int rgb = selectedColor & 0xFFFFFF;
        byte[] payload = buildLedMotorCommand(
                0,
                0,
                LED_ON,
                colorRed(rgb),
                colorGreen(rgb),
                colorBlue(rgb),
                80,
                0,
                0,
                0,
                0xFFFF);
        sendCommandPayload(payload, "show color #" + colorHex(rgb));
    }

    private void hideColor() {
        byte[] payload = buildLedMotorCommand(0, 0, LED_OFF, 0, 0, 0, 0, 0, 0, 0, 0);
        sendCommandPayload(payload, "hide color");
    }

    private void breatheSelectedColor() {
        int rgb = selectedColor & 0xFFFFFF;
        byte[] payload = buildLedMotorCommand(
                0,
                0,
                LED_BREATHE,
                colorRed(rgb),
                colorGreen(rgb),
                colorBlue(rgb),
                80,
                250,
                500,
                1000,
                10000);
        sendCommandPayload(payload, "breathe color #" + colorHex(rgb));
    }

    private void sendVibration(int strength, int vibrationMs, int ledMode, int red, int green, int blue, int brightness, int ledExecuteMs) {
        byte[] payload = buildLedMotorCommand(strength, vibrationMs, ledMode, red, green, blue, brightness,
                0, 0, 0, ledExecuteMs);
        sendCommandPayload(payload, "vibration");
    }

    private void sendCommandPayload(byte[] payload, String label) {
        BluetoothGattCharacteristic command = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA6_COMMAND);
        if (command == null) {
            log("Cannot write command: FFA6 command characteristic missing.");
            return;
        }
        if (!ready) {
            log("Band is not marked READY yet; sending anyway in case auth already completed.");
        }
        log("Writing " + label + " to FFA6: " + hex(payload));
        enqueueWrite(command, payload, "write " + label);
    }

    private void writeColorConfig(int configId, String configName) {
        int rgb = selectedColor & 0xFFFFFF;
        byte[] payload = buildWriteColorConfigCommand(configId, rgb);
        sendCommandPayload(payload, "config " + configName + " #" + colorHex(rgb));
    }

    private byte[] buildWriteColorConfigCommand(int configId, int rgb) {
        byte[] payload = new byte[24];
        payload[0] = CMD_CONFIG;
        payload[1] = 0x00;
        payload[2] = (byte) configId;
        payload[3] = 0x01;
        payload[4] = (byte) ((rgb >> 24) & 0xFF);
        payload[5] = (byte) ((rgb >> 16) & 0xFF);
        payload[6] = (byte) ((rgb >> 8) & 0xFF);
        payload[7] = (byte) (rgb & 0xFF);
        return payload;
    }

    private byte[] buildLedMotorCommand(int strength, int vibrationMs, int ledMode, int red, int green, int blue,
                                        int brightness, int transitionMs, int holdMs, int sleepMs, int ledExecuteMs) {
        byte[] payload = new byte[37];
        payload[0] = CMD_LED_MOTOR_CTRL;
        payload[1] = 0x00;
        payload[2] = 0x00;
        payload[3] = (byte) clamp(strength, 0, 100);
        putLe16(payload, 4, clamp(vibrationMs, 0, 0xFFFF));
        payload[6] = (byte) ledMode;
        payload[7] = (byte) clamp(red, 0, 255);
        payload[8] = (byte) clamp(green, 0, 255);
        payload[9] = (byte) clamp(blue, 0, 255);
        payload[10] = (byte) clamp(brightness, 0, 100);
        putLe16(payload, 11, clamp(transitionMs, 0, 0xFFFF));
        putLe16(payload, 13, clamp(holdMs, 0, 0xFFFF));
        putLe16(payload, 15, clamp(sleepMs, 0, 0xFFFF));
        putLe16(payload, 17, clamp(ledExecuteMs, 0, 0xFFFF));
        return payload;
    }

    private int colorRed(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private int colorGreen(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private int colorBlue(int rgb) {
        return rgb & 0xFF;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void putLe16(byte[] payload, int offset, int value) {
        payload[offset] = (byte) (value & 0xFF);
        payload[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private BluetoothGattCharacteristic findCharacteristic(UUID serviceUuid, UUID characteristicUuid) {
        BluetoothGatt currentGatt = gatt;
        if (currentGatt == null) {
            return null;
        }
        BluetoothGattService service = currentGatt.getService(serviceUuid);
        if (service == null) {
            return null;
        }
        return service.getCharacteristic(characteristicUuid);
    }

    private void enqueueRead(BluetoothGattCharacteristic characteristic, String label) {
        enqueue(new BleOperation(label) {
            @SuppressLint("MissingPermission")
            @Override
            boolean run(BluetoothGatt currentGatt) {
                log("Queue op: " + label);
                return currentGatt.readCharacteristic(characteristic);
            }
        });
    }

    private void enqueueWrite(BluetoothGattCharacteristic characteristic, byte[] payload, String label) {
        enqueue(new BleOperation(label) {
            @SuppressLint("MissingPermission")
            @Override
            boolean run(BluetoothGatt currentGatt) {
                log("Queue op: " + label + " -> " + shortUuid(characteristic.getUuid()));
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(payload);
                return currentGatt.writeCharacteristic(characteristic);
            }
        });
    }

    private void enqueueNotify(BluetoothGattCharacteristic characteristic, boolean enable, String label) {
        enqueue(new BleOperation(label) {
            @SuppressLint("MissingPermission")
            @Override
            boolean run(BluetoothGatt currentGatt) {
                log("Queue op: " + label + " -> " + shortUuid(characteristic.getUuid()));
                boolean localSet = currentGatt.setCharacteristicNotification(characteristic, enable);
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                if (!localSet || descriptor == null) {
                    log("Notification setup missing CCCD/localSet=" + localSet + " for " + shortUuid(characteristic.getUuid()));
                    return false;
                }
                descriptor.setValue(enable
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                return currentGatt.writeDescriptor(descriptor);
            }
        });
    }

    private void enqueue(BleOperation operation) {
        mainHandler.post(() -> {
            opQueue.add(operation);
            processQueue();
        });
    }

    @SuppressLint("MissingPermission")
    private void processQueue() {
        if (operationInFlight) {
            return;
        }
        BluetoothGatt currentGatt = gatt;
        if (currentGatt == null) {
            clearQueue();
            return;
        }
        BleOperation operation = opQueue.poll();
        if (operation == null) {
            return;
        }
        operationInFlight = true;
        boolean started;
        try {
            started = operation.run(currentGatt);
        } catch (RuntimeException ex) {
            log("BLE op threw: " + operation.label + " / " + ex.getMessage());
            started = false;
        }
        if (!started) {
            log("BLE op did not start: " + operation.label);
            operationInFlight = false;
            mainHandler.postDelayed(this::processQueue, 120);
        }
    }

    private void finishOperation() {
        mainHandler.post(() -> {
            operationInFlight = false;
            processQueue();
        });
    }

    private void clearQueue() {
        mainHandler.post(() -> {
            opQueue.clear();
            operationInFlight = false;
        });
    }

    private static final class HapticSpec {
        final String pattern;
        final int strength;
        final int durationMs;
        final int rgb;
        final int cooldownMs;
        final int priority;

        HapticSpec(String pattern, int strength, int durationMs, int rgb, int cooldownMs, int priority) {
            this.pattern = pattern;
            this.strength = strength;
            this.durationMs = durationMs;
            this.rgb = rgb;
            this.cooldownMs = cooldownMs;
            this.priority = priority;
        }

        HapticSpec withStrength(int newStrength) {
            return new HapticSpec(pattern, newStrength, durationMs, rgb, cooldownMs, priority);
        }

        HapticSpec withDuration(int newDurationMs) {
            return new HapticSpec(pattern, strength, newDurationMs, rgb, cooldownMs, priority);
        }
    }

    private abstract static class BleOperation {
        final String label;

        BleOperation(String label) {
            this.label = label;
        }

        abstract boolean run(BluetoothGatt currentGatt);
    }

    private String shortUuid(UUID uuid) {
        String text = uuid.toString();
        if (text.startsWith("d3c0")) {
            return text.substring(0, 8);
        }
        if (text.startsWith("0000")) {
            return text.substring(4, 8);
        }
        return text;
    }

    private String colorHex(int rgb) {
        return String.format(Locale.US, "%06X", rgb & 0xFFFFFF);
    }

    private String hex(byte[] value) {
        if (value == null) {
            return "(null)";
        }
        if (value.length == 0) {
            return "(empty)";
        }
        List<String> parts = new ArrayList<>(value.length);
        for (byte b : value) {
            parts.add(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return String.join(" ", parts);
    }

    private String printable(byte[] value) {
        if (value == null) {
            return "";
        }
        return new String(value, StandardCharsets.UTF_8).replace('\0', ' ').trim();
    }
}
