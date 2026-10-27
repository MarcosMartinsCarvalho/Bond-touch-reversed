package com.marco.bondnotifyhaptics;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

public class BondHapticService extends Service {
    static final String ACTION_START = "com.marco.bondnotifyhaptics.START";
    static final String ACTION_CONNECT_SAVED = "com.marco.bondnotifyhaptics.CONNECT_SAVED";
    static final String ACTION_CONNECT_ADDRESS = "com.marco.bondnotifyhaptics.CONNECT_ADDRESS";
    static final String ACTION_DISCONNECT = "com.marco.bondnotifyhaptics.DISCONNECT";
    static final String ACTION_HAPTIC = "com.marco.bondnotifyhaptics.HAPTIC";
    static final String ACTION_TEST = "com.marco.bondnotifyhaptics.TEST";
    static final String ACTION_STATE = "com.marco.bondnotifyhaptics.STATE";

    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_EVENT = "event";
    static final String EXTRA_PATTERN = "pattern";
    static final String EXTRA_STRENGTH = "strength";
    static final String EXTRA_DURATION = "duration";
    static final String EXTRA_COLOR = "color";
    static final String EXTRA_PRIORITY = "priority";

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_ID = "bond_notify_connection";

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
    private static final byte CMD_PAIRED_CORRECT = 0x31;
    private static final int LED_ON = 0x01;
    private static final int LED_BREATHE = 0x02;
    private static final int LED_OFF = 0x04;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<BleOperation> opQueue = new ArrayDeque<>();
    private final ArrayDeque<HapticCommand> pendingHaptics = new ArrayDeque<>();

    private SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private boolean operationInFlight;
    private boolean ready;
    private boolean initQueued;
    private boolean reconnectEnabled;
    private int reconnectAttempt;
    private int localAuid;
    private String generation = "idle";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = BondConfig.prefs(this);
        localAuid = prefs.getInt(BondConfig.KEY_AUID, new java.util.Random().nextInt());
        if (!prefs.contains(BondConfig.KEY_AUID)) {
            prefs.edit().putInt(BondConfig.KEY_AUID, localAuid).apply();
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, statusNotification("Starting"));
        log("Service started.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null || ACTION_START.equals(action)) {
            reconnectEnabled = prefs.getBoolean(BondConfig.KEY_ENABLED, false);
            if (reconnectEnabled) {
                connectSaved();
            }
        } else if (ACTION_CONNECT_SAVED.equals(action)) {
            reconnectEnabled = true;
            prefs.edit().putBoolean(BondConfig.KEY_ENABLED, true).apply();
            connectSaved();
        } else if (ACTION_CONNECT_ADDRESS.equals(action)) {
            reconnectEnabled = true;
            prefs.edit()
                    .putBoolean(BondConfig.KEY_ENABLED, true)
                    .putString(BondConfig.KEY_ADDRESS, intent.getStringExtra(EXTRA_ADDRESS))
                    .putString(BondConfig.KEY_NAME, intent.getStringExtra(EXTRA_NAME))
                    .apply();
            connectSaved();
        } else if (ACTION_DISCONNECT.equals(action)) {
            reconnectEnabled = false;
            prefs.edit().putBoolean(BondConfig.KEY_ENABLED, false).apply();
            stopConnectionService();
            return START_NOT_STICKY;
        } else if (ACTION_TEST.equals(action)) {
            sendOrQueue(new HapticCommand("test", "double", 60, 100, 0xFFFFFF, 90));
        } else if (ACTION_HAPTIC.equals(action)) {
            HapticCommand command = new HapticCommand(
                    intent.getStringExtra(EXTRA_EVENT),
                    intent.getStringExtra(EXTRA_PATTERN),
                    intent.getIntExtra(EXTRA_STRENGTH, 55),
                    intent.getIntExtra(EXTRA_DURATION, 90),
                    intent.getIntExtra(EXTRA_COLOR, 0x37B8FF),
                    intent.getIntExtra(EXTRA_PRIORITY, 70));
            sendOrQueue(command);
        }
        updateForeground();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        closeGattOnly();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, BondHapticService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void connectSaved(Context context) {
        Intent intent = new Intent(context, BondHapticService.class).setAction(ACTION_CONNECT_SAVED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void connectAddress(Context context, String address, String name) {
        Intent intent = new Intent(context, BondHapticService.class)
                .setAction(ACTION_CONNECT_ADDRESS)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_NAME, name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void disconnect(Context context) {
        Intent intent = new Intent(context, BondHapticService.class).setAction(ACTION_DISCONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void test(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(new Intent(context, BondHapticService.class).setAction(ACTION_TEST));
        } else {
            context.startService(new Intent(context, BondHapticService.class).setAction(ACTION_TEST));
        }
    }

    static void haptic(Context context, HapticCommand command) {
        Intent intent = new Intent(context, BondHapticService.class)
                .setAction(ACTION_HAPTIC)
                .putExtra(EXTRA_EVENT, command.event)
                .putExtra(EXTRA_PATTERN, command.pattern)
                .putExtra(EXTRA_STRENGTH, command.strength)
                .putExtra(EXTRA_DURATION, command.durationMs)
                .putExtra(EXTRA_COLOR, command.color)
                .putExtra(EXTRA_PRIORITY, command.priority);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectSaved() {
        if (!hasBleConnect()) {
            log("Bluetooth permission missing.");
            return;
        }
        String address = prefs.getString(BondConfig.KEY_ADDRESS, null);
        if (address == null || adapter == null) {
            log("No saved band address.");
            return;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            closeGattOnly();
            ready = false;
            initQueued = false;
            generation = "connecting";
            updateForeground();
            log("Connecting to saved band " + address + ".");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, false, gattCallback);
            }
        } catch (Exception ex) {
            log("Connect failed: " + ex.getMessage());
            scheduleReconnect();
        }
    }

    private boolean hasBleConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            log("GATT state status=" + status + " newState=" + newState + ".");
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempt = 0;
                gatt = currentGatt;
                currentGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                generation = "disconnected";
                clearQueue();
                if (gatt == currentGatt) {
                    gatt = null;
                }
                updateForeground();
                if (reconnectEnabled) {
                    scheduleReconnect();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            log("Services discovered status=" + status + ".");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleServicesDiscovered(currentGatt);
            }
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
            finishOperation();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor descriptor, int status) {
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

    private void handleServicesDiscovered(BluetoothGatt currentGatt) {
        if (currentGatt.getService(LEGACY_USER_APP_SERVICE) == null) {
            generation = "unsupported";
            log("Band 4 legacy service missing.");
            updateForeground();
            return;
        }
        BluetoothGattCharacteristic api = findCharacteristic(LEGACY_DEVICE_INFO_SERVICE, LEGACY_API);
        if (api != null) {
            enqueueRead(api, "read API generation");
        } else {
            generation = "BT4 assumed";
            initializeLegacyBand();
        }
    }

    private void handleCharacteristicRead(BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("Read failed " + shortUuid(characteristic.getUuid()) + " status=" + status + ".");
            if (characteristic.getUuid().equals(LEGACY_API)) {
                generation = "BT4 assumed";
                initializeLegacyBand();
            }
            return;
        }
        UUID uuid = characteristic.getUuid();
        if (uuid.equals(LEGACY_API)) {
            generation = decodeGeneration(value);
            initializeLegacyBand();
        } else if (uuid.equals(FFA4_STATE)) {
            handleState(value);
        } else if (uuid.equals(STANDARD_BATTERY_LEVEL) || uuid.equals(LEGACY_BATTERY_LEVEL)) {
            if (value != null && value.length > 0) {
                log("Battery " + (value[0] & 0xFF) + "%.");
            }
        } else if (uuid.equals(STANDARD_FIRMWARE_REVISION)) {
            log("Firmware " + new String(value, StandardCharsets.UTF_8).trim() + ".");
        }
    }

    private void handleCharacteristicChanged(BluetoothGattCharacteristic characteristic, byte[] value) {
        UUID uuid = characteristic.getUuid();
        if (uuid.equals(FFA4_STATE)) {
            handleState(value);
        } else if (uuid.equals(FFA5_EVENT)) {
            handleEvent(value);
        }
    }

    private String decodeGeneration(byte[] value) {
        if (value == null || value.length == 0) {
            return "legacy unknown";
        }
        int marker = value[0] & 0xFF;
        if (marker == 0x44) {
            return "BT4 marker 0x44";
        }
        if (marker == 0x42) {
            return "BT2 marker 0x42";
        }
        if (marker == 0x45) {
            return "Mini marker 0x45";
        }
        return "legacy marker " + String.format(Locale.US, "0x%02X", marker);
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
            enqueueNotify(battery, true, "battery notify");
        }
        if (event != null) {
            enqueueNotify(event, true, "event notify");
        }
        if (state != null) {
            enqueueNotify(state, true, "state notify");
        }
        if (extra != null) {
            enqueueNotify(extra, true, "band4 extra notify");
        }
        if (state != null) {
            enqueueRead(state, "read state");
        }
    }

    private void handleState(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        int state = value[0] & 0xFF;
        log("State " + String.format(Locale.US, "0x%02X", state) + ".");
        if (state == 0x82) {
            ready = false;
            writeToken();
        } else if (state == 0x83) {
            ready = false;
            writeAuid();
        } else if (state == 0x85) {
            ready = true;
            log("Band ready.");
            queueFirmwareRead();
            flushPendingHaptics();
        } else if (state == 0x84) {
            ready = false;
            log("Band reported OOBE/AUID error; double tap during white flash if pairing.");
        }
        updateForeground();
    }

    private void handleEvent(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        int event = value[0] & 0xFF;
        if (event == 0x13) {
            writePairedCorrect();
        } else if (event == 0x11) {
            log("Band accepted AUID.");
        } else if (event == 0x12) {
            log("Band rejected AUID.");
        }
    }

    private void writeToken() {
        BluetoothGattCharacteristic token = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA1_TOKEN);
        if (token != null) {
            enqueueWrite(token, "12345678".getBytes(StandardCharsets.US_ASCII), "token");
        }
    }

    private void writeAuid() {
        BluetoothGattCharacteristic auid = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA2_AUID);
        if (auid != null) {
            byte[] payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(localAuid).array();
            enqueueWrite(auid, payload, "AUID");
        }
    }

    private void writePairedCorrect() {
        byte[] payload = new byte[20];
        payload[0] = CMD_PAIRED_CORRECT;
        payload[2] = 0x11;
        payload[3] = 0x22;
        payload[4] = 0x33;
        payload[5] = 0x44;
        sendCommandPayload(payload, "paired-correct");
    }

    private void queueFirmwareRead() {
        BluetoothGattCharacteristic firmware = findCharacteristic(STANDARD_DEVICE_INFO_SERVICE, STANDARD_FIRMWARE_REVISION);
        if (firmware != null) {
            enqueueRead(firmware, "firmware");
        }
    }

    private void sendOrQueue(HapticCommand command) {
        if (command.pattern == null || !BondConfig.isPattern(command.pattern)) {
            command.pattern = "single";
        }
        command.strength = BondConfig.clamp(command.strength, 0, 100);
        command.durationMs = BondConfig.clamp(command.durationMs, 25, 2000);
        if (gatt == null || !ready) {
            if (pendingHaptics.size() < 5) {
                pendingHaptics.add(command);
            }
            if (gatt == null && reconnectEnabled) {
                connectSaved();
            }
            log("Queued haptic until band is ready: " + command.event + ".");
            return;
        }
        sendPattern(command);
    }

    private void flushPendingHaptics() {
        while (!pendingHaptics.isEmpty() && ready && gatt != null) {
            sendPattern(pendingHaptics.poll());
        }
    }

    private void sendPattern(HapticCommand command) {
        switch (command.pattern) {
            case "double":
                queuePulse(0, command, "double 1");
                queuePulse(Math.max(160, command.durationMs + 105), command, "double 2");
                break;
            case "triple":
                queuePulse(0, command, "triple 1");
                queuePulse(Math.max(160, command.durationMs + 105), command, "triple 2");
                queuePulse(Math.max(320, (command.durationMs + 105) * 2), command, "triple 3");
                break;
            case "heartbeat":
                queuePulse(0, command, "heartbeat 1");
                queuePulse(170, command.withStrength(Math.max(10, command.strength - 25)).withDuration(Math.min(command.durationMs, 105)), "heartbeat 2");
                queuePulse(610, command, "heartbeat 3");
                queuePulse(780, command.withStrength(Math.max(10, command.strength - 25)).withDuration(Math.min(command.durationMs, 105)), "heartbeat 4");
                break;
            case "warning":
                queuePulse(0, command, "warning 1");
                queuePulse(180, command.withStrength(Math.min(100, command.strength + 10)), "warning 2");
                queuePulse(360, command.withStrength(Math.min(100, command.strength + 20)), "warning 3");
                break;
            case "long":
                queuePulse(0, command.withDuration(Math.max(command.durationMs, 700)), "long");
                break;
            case "long_tap":
                queuePulse(0, command.withDuration(Math.max(command.durationMs, 850)), "long tap long");
                queuePulse(Math.max(1050, command.durationMs + 380), command.withDuration(80), "long tap tap");
                break;
            case "breathe":
                sendBreathe(command.color);
                if (command.strength > 0) {
                    queuePulse(0, command, "breathe pulse");
                }
                break;
            default:
                queuePulse(0, command, "single");
                break;
        }
        log("Haptic " + command.event + " -> " + command.pattern + ".");
    }

    private void queuePulse(int delayMs, HapticCommand command, String label) {
        if (delayMs <= 0) {
            sendPulse(command, label);
        } else {
            mainHandler.postDelayed(() -> sendPulse(command, label), delayMs);
        }
    }

    private void sendPulse(HapticCommand command, String label) {
        int rgb = command.color & 0xFFFFFF;
        byte[] payload = buildLedMotorCommand(command.strength, command.durationMs, LED_ON,
                red(rgb), green(rgb), blue(rgb), 80, 0, 0, 0, Math.max(160, command.durationMs));
        sendCommandPayload(payload, label);
    }

    private void sendBreathe(int color) {
        int rgb = color & 0xFFFFFF;
        byte[] payload = buildLedMotorCommand(0, 0, LED_BREATHE, red(rgb), green(rgb), blue(rgb),
                75, 180, 360, 720, 2500);
        sendCommandPayload(payload, "breathe");
    }

    private void sendCommandPayload(byte[] payload, String label) {
        BluetoothGattCharacteristic command = findCharacteristic(LEGACY_USER_APP_SERVICE, FFA6_COMMAND);
        if (command == null) {
            return;
        }
        enqueueWrite(command, payload, "command " + label);
    }

    private byte[] buildLedMotorCommand(int strength, int vibrationMs, int ledMode, int red, int green, int blue,
                                        int brightness, int transitionMs, int holdMs, int sleepMs, int ledExecuteMs) {
        byte[] payload = new byte[37];
        payload[0] = CMD_LED_MOTOR_CTRL;
        payload[3] = (byte) BondConfig.clamp(strength, 0, 100);
        putLe16(payload, 4, BondConfig.clamp(vibrationMs, 0, 0xFFFF));
        payload[6] = (byte) ledMode;
        payload[7] = (byte) BondConfig.clamp(red, 0, 255);
        payload[8] = (byte) BondConfig.clamp(green, 0, 255);
        payload[9] = (byte) BondConfig.clamp(blue, 0, 255);
        payload[10] = (byte) BondConfig.clamp(brightness, 0, 100);
        putLe16(payload, 11, BondConfig.clamp(transitionMs, 0, 0xFFFF));
        putLe16(payload, 13, BondConfig.clamp(holdMs, 0, 0xFFFF));
        putLe16(payload, 15, BondConfig.clamp(sleepMs, 0, 0xFFFF));
        putLe16(payload, 17, BondConfig.clamp(ledExecuteMs, 0, 0xFFFF));
        return payload;
    }

    private BluetoothGattCharacteristic findCharacteristic(UUID serviceUuid, UUID characteristicUuid) {
        BluetoothGatt currentGatt = gatt;
        if (currentGatt == null) {
            return null;
        }
        BluetoothGattService service = currentGatt.getService(serviceUuid);
        return service == null ? null : service.getCharacteristic(characteristicUuid);
    }

    private void enqueueRead(BluetoothGattCharacteristic characteristic, String label) {
        enqueue(new BleOperation(label) {
            @SuppressLint("MissingPermission")
            @Override
            boolean run(BluetoothGatt currentGatt) {
                return currentGatt.readCharacteristic(characteristic);
            }
        });
    }

    private void enqueueWrite(BluetoothGattCharacteristic characteristic, byte[] payload, String label) {
        enqueue(new BleOperation(label) {
            @SuppressLint("MissingPermission")
            @Override
            boolean run(BluetoothGatt currentGatt) {
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
                boolean localSet = currentGatt.setCharacteristicNotification(characteristic, enable);
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                if (!localSet || descriptor == null) {
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
            started = false;
        }
        if (!started) {
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

    @SuppressLint("MissingPermission")
    private void stopConnectionService() {
        reconnectEnabled = false;
        closeGattOnly();
        log("Service stopped.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @SuppressLint("MissingPermission")
    private void closeGattOnly() {
        clearQueue();
        ready = false;
        initQueued = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    private void scheduleReconnect() {
        if (!reconnectEnabled) {
            return;
        }
        int delay = Math.min(60_000, 5_000 * (1 << Math.min(4, reconnectAttempt++)));
        log("Reconnect in " + (delay / 1000) + "s.");
        mainHandler.postDelayed(() -> {
            if (reconnectEnabled && gatt == null) {
                connectSaved();
            }
        }, delay);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bond connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the Bond bracelet connected for notification haptics.");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification statusNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Bond Notify Haptics")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();
    }

    private void updateForeground() {
        startForeground(NOTIFICATION_ID, statusNotification(statusText()));
        Intent state = new Intent(ACTION_STATE);
        state.setPackage(getPackageName());
        state.putExtra("ready", ready);
        state.putExtra("connected", gatt != null);
        state.putExtra("generation", generation);
        state.putExtra("log", prefs.getString(BondConfig.KEY_LAST_LOG, ""));
        sendBroadcast(state);
    }

    private String statusText() {
        if (ready) {
            return "Bracelet connected";
        }
        if (gatt != null) {
            return "Bracelet connecting";
        }
        return reconnectEnabled ? "Waiting for bracelet" : "Stopped";
    }

    private void log(String message) {
        String line = String.format(Locale.US, "%1$tH:%1$tM:%1$tS  %2$s", System.currentTimeMillis(), message);
        prefs.edit().putString(BondConfig.KEY_LAST_LOG, line).apply();
        updateForeground();
    }

    private String shortUuid(UUID uuid) {
        String value = uuid.toString();
        return value.length() < 8 ? value : value.substring(4, 8);
    }

    private void putLe16(byte[] payload, int offset, int value) {
        payload[offset] = (byte) (value & 0xFF);
        payload[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private int blue(int rgb) {
        return rgb & 0xFF;
    }

    abstract static class BleOperation {
        final String label;

        BleOperation(String label) {
            this.label = label;
        }

        abstract boolean run(BluetoothGatt currentGatt);
    }
}

final class HapticCommand {
    final String event;
    String pattern;
    int strength;
    int durationMs;
    final int color;
    final int priority;

    HapticCommand(String event, String pattern, int strength, int durationMs, int color, int priority) {
        this.event = event == null ? "notification" : event;
        this.pattern = pattern == null ? "single" : pattern;
        this.strength = strength;
        this.durationMs = durationMs;
        this.color = color;
        this.priority = priority;
    }

    HapticCommand withStrength(int newStrength) {
        return new HapticCommand(event, pattern, newStrength, durationMs, color, priority);
    }

    HapticCommand withDuration(int newDuration) {
        return new HapticCommand(event, pattern, strength, newDuration, color, priority);
    }
}
