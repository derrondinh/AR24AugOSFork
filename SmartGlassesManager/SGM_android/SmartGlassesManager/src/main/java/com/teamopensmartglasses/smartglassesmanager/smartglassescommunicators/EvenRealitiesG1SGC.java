package com.teamopensmartglasses.smartglassesmanager.smartglassescommunicators;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.gson.Gson;
import com.teamopensmartglasses.smartglassesmanager.cpp.L3cCpp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EvenRealitiesG1SGC extends SmartGlassesCommunicator {
    private static final String TAG = "WearableAi_EvenRealitiesG1SGC";
    public static final String SHARED_PREFS_NAME = "EvenRealitiesPrefs";
    public static final String LEFT_DEVICE_KEY = "SavedG1LeftName";
    public static final String RIGHT_DEVICE_KEY = "SavedG1RightName";


    private static final UUID UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_RX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private Context context;
    private BluetoothGatt leftGlassGatt;
    private BluetoothGatt rightGlassGatt;
    private BluetoothGattCharacteristic leftTxChar;
    private BluetoothGattCharacteristic rightTxChar;
    private BluetoothGattCharacteristic leftRxChar;
    private BluetoothGattCharacteristic rightRxChar;
    private final Handler handler = new Handler();
    private final Semaphore sendSemaphore = new Semaphore(1);
    private boolean isLeftConnected = false;
    private boolean isRightConnected = false;
    private int currentSeq = 0;
    private boolean stopper = false;
    private boolean debugStopper = false;

    private static final long DELAY_BETWEEN_SENDS_MS = 25;
    private static final long DELAY_BETWEEN_CHUNKS_SEND = 50;
    private static final long HEARTBEAT_INTERVAL_MS = 5000;

    //heartbeat sender
    private Handler heartbeatHandler = new Handler();
    private Runnable heartbeatRunnable;

    //white list sender
    private Handler whiteListHandler = new Handler();
    private boolean whiteListedAlready = false;

    //mic enable Handler
    private Handler micEnableHandler = new Handler();
    private boolean micEnabledAlready = false;

    //notification period sender
    private Handler notificationHandler = new Handler();
    private Runnable notificationRunnable;
    private boolean notifysStarted = false;
    private int notificationNum = 10;

    //text wall periodic sender
    private Handler textWallHandler = new Handler();
    private Runnable textWallRunnable;
    private boolean textWallsStarted = false;
    private int textWallNum = 10;

    //pairing logic
    private boolean isLeftPairing = false;
    private boolean isRightPairing = false;
    private boolean isLeftBonded = false;
    private boolean isRightBonded = false;
    private BluetoothDevice leftDevice = null;
    private BluetoothDevice rightDevice = null;
    private String preferredG1Id = null;
    private String pendingSavedG1LeftName = null;
    private String pendingSavedG1RightName = null;
    private String savedG1LeftName = null;
    private String savedG1RightName = null;

    //handler to turn off screen
    Handler goHomeHandler;
    Runnable goHomeRunnable;

    //remember when we connected
    private long lastConnectionTimestamp = 0;

    public EvenRealitiesG1SGC(Context context) {
        super();
        this.context = context;
        mConnectState = 0;
        loadPairedDeviceNames();
        goHomeHandler = new Handler();
    }

    private final BluetoothGattCallback leftGattCallback = createGattCallback("Left");
    private final BluetoothGattCallback rightGattCallback = createGattCallback("Right");

    private BluetoothGattCallback createGattCallback(String side) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Log.d(TAG, "ConnectionStateChanged");
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, side + " glass connected, discovering services...");
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if ("Left".equals(side)) isLeftConnected = false;
                    else isRightConnected = false;
                    updateConnectionState();
                    Log.d(TAG, side + " glass disconnected");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {

                    gatt.requestMtu(251); // Request a higher MTU size
                    Log.d(TAG, "Requested MTU size: 251");

                    BluetoothGattService uartService = gatt.getService(UART_SERVICE_UUID);

                    if (uartService != null) {
                        BluetoothGattCharacteristic txChar = uartService.getCharacteristic(UART_TX_CHAR_UUID);
                        BluetoothGattCharacteristic rxChar = uartService.getCharacteristic(UART_RX_CHAR_UUID);

                        if (txChar != null) {
                            if ("Left".equals(side)) leftTxChar = txChar;
                            else rightTxChar = txChar;
                            enableNotification(gatt, rxChar, side);
                            txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                            Log.d(TAG, side + " glass TX characteristic found");
                        }

                        if (rxChar != null) {

                            // Bond the device
                            if (gatt.getDevice().getBondState() != BluetoothDevice.BOND_BONDED) {
                                Log.d(TAG, "Creating bond with device: " + gatt.getDevice().getName());
                                gatt.getDevice().createBond();
                            } else {
                                Log.d(TAG, "Device already bonded: " + gatt.getDevice().getName());
                            }

                            if ("Left".equals(side)) leftRxChar = rxChar;
                            else rightRxChar = rxChar;
                            enableNotification(gatt, rxChar, side);
                            txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                            Log.d(TAG, side + " glass RX characteristic found");
                        }

                        // Request MTU size
                        gatt.requestMtu(251);
                        Log.d(TAG, "Requested MTU size: 251");

                        //no idea why but it's in the Even app - Cayden
//                        Log.d(TAG, "Sending 0xF4 Command");
//                        sendDataSequentially(new byte[] {(byte) 0xF4, (byte) 0x01});

                        //below has odd staggered times so they don't happen in sync
                        // Start MIC streaming
//                        setMicEnabled(true, 993); // Enable the MIC

                        //enable our AugmentOS notification key
                        sendWhiteListCommand(2038);

                        //start sending debug notifications
//                        startPeriodicNotifications(302);

                        //start sending debug notifications
//                        startPeriodicTextWall(302);

                        //start heartbeat
                        startHeartbeat(5000);
                    } else {
                        Log.e(TAG, side + " glass UART service not found");
                    }
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, side + " glass write successful");
                } else {
                    Log.e(TAG, side + " glass write failed with status: " + status);
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (characteristic.getUuid().equals(UART_RX_CHAR_UUID)) {
                        byte[] data = characteristic.getValue();
                        String deviceName = gatt.getDevice().getName();

                        // Handle MIC audio data
                        if (data.length > 0 && (data[0] & 0xFF) == 0xF1) {
                            int seq = data[1] & 0xFF; // Sequence number
                            byte[] audioData = Arrays.copyOfRange(data, 2, data.length); // Extract audio data
                            Log.d(TAG, "Audio data received. Seq: " + seq + ", Data: " + Arrays.toString(audioData) + ", from: " + deviceName);
                            // eg. LC3 to PCM
                            byte[] lc3 = Arrays.copyOfRange(audioData, 2, 202);
                            byte[] pcmData = L3cCpp.decodeLC3(lc3);
                            if (pcmData == null) {
                                throw new IllegalStateException("Failed to decode LC3 data");
                            }
                            Log.d(this.getClass().getSimpleName(), "============Lc3 data = " + Arrays.toString(lc3) + ", Pcm = " + Arrays.toString(pcmData));
                        }
                        // Only check head movements from the right sensor
                        else if (deviceName.contains("R_")) {
                            // Check for head up movement - initial F5 02 signal
                            if (data.length > 1 && (data[0] & 0xFF) == 0xF5 && (data[1] & 0xFF) == 0x02) {
                                Log.d(TAG, "HEAD DOWN MOVEMENT DETECTED");
                            }
                            // Check for head down movement - initial F5 03 signal
                            else if (data.length > 1 && (data[0] & 0xFF) == 0xF5 && (data[1] & 0xFF) == 0x03) {
                                Log.d(TAG, "HEAD UP MOVEMENT DETECTED");
                            }
                        }
                        // Handle other non-audio responses
                        else {
                            Log.d(TAG, "Received non-audio response: " + bytesToHex(data) + ", from: " + deviceName);
                        }

                        // Check if it's a heartbeat response
                        if (data.length > 0 && data[0] == 0x25) {
                            Log.d(TAG, "Heartbeat response received");
                        }
                    }
                });
            }

        };
    }

    private void enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, String side) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean result = gatt.writeDescriptor(descriptor);
            if (result) {
                Log.d(TAG, side + " SIDE," + "Descriptor write successful for characteristic: " + characteristic.getUuid());
                if ("Left".equals(side)) isLeftConnected = true;
                else isRightConnected = true;
                updateConnectionState();
            } else {
                Log.e(TAG, side + " SIDE," + "Failed to write descriptor for characteristic: " + characteristic.getUuid());
            }
        } else {
            Log.e(TAG, side + " SIDE," + "Descriptor not found for characteristic: " + characteristic.getUuid());
        }
    }

    private void updateConnectionState() {
        if (isLeftConnected && isRightConnected) {
            mConnectState = 2;
            Log.d(TAG, "Both glasses connected");
            lastConnectionTimestamp = System.currentTimeMillis();
            connectionEvent(2);
        } else if (isLeftConnected || isRightConnected) {
            mConnectState = 1;
            Log.d(TAG, "One glass connected");
            connectionEvent(1);
        } else {
            mConnectState = 0;
            Log.d(TAG, "No glasses connected");
            connectionEvent(0);
        }
    }

    private final BroadcastReceiver bondingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "Bonded with device: " + device.getName());
                    if (device.getName().contains("_L_")) {
                        isLeftBonded = true;
                        isLeftPairing = false;
                        pendingSavedG1LeftName = device.getName();
                    } else if (device.getName().contains("_R_")) {
                        isRightBonded = true;
                        isRightPairing = false;
                        pendingSavedG1RightName = device.getName();
                    }

                    // Restart scan for the next device
                    if (!isLeftBonded || !isRightBonded) {
                        // if (!(isLeftBonded && !isRightBonded)){// || !doPendingPairingIdsMatch()) {
                        Log.d(TAG, "Restarting scan to find remaining device...");
                        startScan(BluetoothAdapter.getDefaultAdapter());
                    } else if (isLeftBonded && isRightBonded && !doPendingPairingIdsMatch()) {
                        // We've connected to two different G1s...
                        // Let's unpair the right, try to pair to a different one
                        isRightBonded = false;
                        pendingSavedG1RightName = null;
                        Log.d(TAG, "Connected to two different G1s - retry right G1 arm");
                    } else {
                        Log.d(TAG, "Both devices bonded. Proceeding with connections...");
                        savedG1LeftName = pendingSavedG1LeftName;
                        savedG1RightName = pendingSavedG1RightName;
                        savePairedDeviceNames();
                        stopScan(BluetoothAdapter.getDefaultAdapter());
                        connectToGatt(leftDevice);
                        connectToGatt(rightDevice);
                    }
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    Log.d(TAG, "Bonding failed for device: " + device.getName());
                    if (device.getName().contains("_L_")) isLeftPairing = false;
                    if (device.getName().contains("_R_")) isRightPairing = false;

                    // Restart scanning to retry bonding
                    startScan(BluetoothAdapter.getDefaultAdapter());
                }
            }
        }
    };

    public boolean doPendingPairingIdsMatch() {
        String leftId = parsePairingIdFromDeviceName(pendingSavedG1LeftName);
        String rightId = parsePairingIdFromDeviceName(pendingSavedG1RightName);
        return leftId != null && leftId.equals(rightId);
    }
    public String parsePairingIdFromDeviceName(String input) {
        if (input == null || input.isEmpty()) return null;
        // Regular expression to match the number after "G1_"
        Pattern pattern = Pattern.compile("G1_(\\d+)_");
        Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            return matcher.group(1); // Group 1 contains the number
        }
        return null; // Return null if no match is found
    }

    private void savePairedDeviceNames() {
        if (savedG1LeftName != null && savedG1RightName != null) {
            context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(LEFT_DEVICE_KEY, savedG1LeftName)
                    .putString(RIGHT_DEVICE_KEY, savedG1RightName)
                    .apply();
            Log.d(TAG, "Saved paired device names: Left=" + savedG1LeftName + ", Right=" + savedG1RightName);
        }
    }

    private void loadPairedDeviceNames() {
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        savedG1LeftName = prefs.getString(LEFT_DEVICE_KEY, null);
        savedG1RightName = prefs.getString(RIGHT_DEVICE_KEY, null);
        Log.d(TAG, "Loaded paired device names: Left=" + savedG1LeftName + ", Right=" + savedG1RightName);
    }

    public static void deleteEvenSharedPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        Log.d(TAG, "Nuked EvenRealities SharedPreferences");
    }


    private void connectToGatt(BluetoothDevice device) {
        if (device.getName().contains("_L_") && leftGlassGatt == null) {
            Log.d(TAG, "Connect GATT to left side");
            leftGlassGatt = device.connectGatt(context, false, leftGattCallback);
        } else if (device.getName().contains("_R_") && rightGlassGatt == null) {
            Log.d(TAG, "Connect GATT to right side");
            rightGlassGatt = device.connectGatt(context, false, rightGattCallback);
        } else {
            Log.d(TAG, "Tried to connect to incorrect device: " + device.getName());
        }
    }

    private BluetoothAdapter.LeScanCallback leScanCallback = (device, rssi, scanRecord) -> {
        String name = device.getName();

        //check if G1 arm
        if (name == null || !name.contains("Even G1_")) {
            return;
        }

        //DEBUG check if ALEX's smart glasses
       if (name == null || !name.contains("91")) {
            Log.d(TAG, "NOT CAYDEN'S GLASSES");
            return;
        }

        boolean isLeft = name.contains("_L_");

        // Check if it's the correct G1, IF we've previously paired a G1
        if ((savedG1LeftName != null && savedG1RightName != null)) {
            if(!(name.contains(savedG1LeftName) || name.contains(savedG1RightName))){
                // it is not either
                return;
            }
        }

        //figure out which G1 arm it is
        if (isLeft){
            leftDevice = device;
        } else{
            rightDevice = device;
        }

        //check if we've already bonded/paired
        int bondState = device.getBondState();
        if (bondState != BluetoothDevice.BOND_BONDED) {
            // Stop scan before initiating bond
            stopScan(BluetoothAdapter.getDefaultAdapter());

            if (isLeft && !isLeftPairing && !isLeftBonded) {
                Log.d(TAG, "Bonding with Left Glass...");
                isLeftPairing = true;
                bondDevice(device);
            } else if (!isLeft && !isRightPairing && !isRightBonded) {
                Log.d(TAG, "Bonding with Right Glass...");
                isRightPairing = true;
                bondDevice(device);
            }
        } else { //only runs if we've already setup the bond previously
            // Mark the device as bonded
            if (isLeft) isLeftBonded = true;
            if (!isLeft) isRightBonded = true;

            // Attempt GATT connection only after both sides are bonded
            if (isLeftBonded && isRightBonded) {
                Log.d(TAG, "Both sides bonded. Ready to connect to GATT.");
                stopScan(BluetoothAdapter.getDefaultAdapter());
                attemptGattConnection(leftDevice);
                attemptGattConnection(rightDevice);
            }
        }
    };

    @Override
    public void connectToSmartGlasses() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Register bonding receiver
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        context.registerReceiver(bondingReceiver, filter);

        // Start scanning for devices
        startScan(bluetoothAdapter);
    }

    private void startScan(BluetoothAdapter bluetoothAdapter) {
        bluetoothAdapter.startLeScan(leScanCallback);
        Log.d(TAG, "Started scanning for devices...");
        handler.postDelayed(() -> stopScan(bluetoothAdapter), 60000); // Stop scan after 60 seconds
    }

    private void stopScan(BluetoothAdapter bluetoothAdapter) {
        bluetoothAdapter.stopLeScan(leScanCallback);
        Log.d(TAG, "Stopped scanning for devices");
    }

    private void bondDevice(BluetoothDevice device) {
        try {
            Log.d(TAG, "Attempting to bond with device: " + device.getName());
            Method method = device.getClass().getMethod("createBond");
            method.invoke(device);
        } catch (Exception e) {
            Log.e(TAG, "Bonding failed: " + e.getMessage());
        }
    }

    private void attemptGattConnection(BluetoothDevice device) {
        if (!isLeftBonded || !isRightBonded) {
            Log.d(TAG, "Cannot connect to GATT: Both devices are not bonded yet");
            return;
        }

        if (device.getName().contains("_L_") && leftGlassGatt == null) {
            Log.d(TAG, "Connecting to GATT for Left Glass...");
            leftGlassGatt = device.connectGatt(context, false, leftGattCallback);
            isLeftConnected = true;
        } else if (device.getName().contains("_R_") && rightGlassGatt == null) {
            Log.d(TAG, "Connecting to GATT for Right Glass...");
            rightGlassGatt = device.connectGatt(context, false, rightGattCallback);
            isRightConnected = true;
        }
    }

    private byte[] createTextPackage(String text, int currentPage, int totalPages, int screenStatus) {
        byte[] textBytes = text.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(9 + textBytes.length);
        buffer.put((byte) 0x4E);
        buffer.put((byte) (currentSeq++ & 0xFF));
        buffer.put((byte) 1);
        buffer.put((byte) 0);
        buffer.put((byte) screenStatus);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        buffer.put((byte) currentPage);
        buffer.put((byte) totalPages);
        buffer.put(textBytes);

        return buffer.array();
    }

    private void sendDataSequentially(byte[] data) {
        sendDataSequentially(data, false);
    }

//    private void sendDataSequentially(byte[] data, boolean onlyLeft) {
//        if (stopper) return;
//        stopper = true;
//
//        new Thread(() -> {
//            try {
//                if (leftGlassGatt != null && leftTxChar != null) {
//                    leftTxChar.setValue(data);
//                    leftGlassGatt.writeCharacteristic(leftTxChar);
//                    Thread.sleep(DELAY_BETWEEN_SENDS_MS);
//                }
//
//                if (!onlyLeft && rightGlassGatt != null && rightTxChar != null) {
//                    rightTxChar.setValue(data);
//                    rightGlassGatt.writeCharacteristic(rightTxChar);
//                    Thread.sleep(DELAY_BETWEEN_SENDS_MS);
//                }
//                stopper = false;
//            } catch (InterruptedException e) {
//                Log.e(TAG, "Error sending data: " + e.getMessage());
//            }
//        }).start();
//    }

    // Data class to represent a send request
    private static class SendRequest {
        final byte[] data;
        final boolean onlyLeft;

        SendRequest(byte[] data, boolean onlyLeft) {
            this.data = data;
            this.onlyLeft = onlyLeft;
        }
    }

    // Queue to hold pending requests
    private final ConcurrentLinkedQueue<SendRequest> sendQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isWorkerRunning = false;

    // Non-blocking function to add new send request
    private void sendDataSequentially(byte[] data, boolean onlyLeft) {
        sendQueue.offer(new SendRequest(data, onlyLeft));
        startWorkerIfNeeded();
    }

    // Start the worker thread if it's not already running
    private synchronized void startWorkerIfNeeded() {
        if (!isWorkerRunning) {
            isWorkerRunning = true;
            new Thread(this::processQueue).start();
        }
    }

    private static final long INITIAL_CONNECTION_DELAY_MS = 300; // Adjust this value as needed

    private void processQueue() {
        while (true) {
            SendRequest request = sendQueue.poll();
            if (request == null) {
                isWorkerRunning = false;
                break;
            }

            try {
                // Check if we need to wait after initial connection
                long timeSinceConnection = System.currentTimeMillis() - lastConnectionTimestamp;
                if (timeSinceConnection < INITIAL_CONNECTION_DELAY_MS) {
                    Thread.sleep(INITIAL_CONNECTION_DELAY_MS - timeSinceConnection);
                }

                // Send to left glass if available
                if (leftGlassGatt != null && leftTxChar != null) {
                    leftTxChar.setValue(request.data);
                    leftGlassGatt.writeCharacteristic(leftTxChar);
                    Thread.sleep(DELAY_BETWEEN_SENDS_MS);
                }

                // Send to right glass if needed and available
                if (!request.onlyLeft && rightGlassGatt != null && rightTxChar != null) {
                    rightTxChar.setValue(request.data);
                    rightGlassGatt.writeCharacteristic(rightTxChar);
                    Thread.sleep(DELAY_BETWEEN_SENDS_MS);
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Error sending data: " + e.getMessage());
                // Optionally re-add the failed request to the queue
                // sendQueue.offer(request);
            }
        }
    }

//    @Override
//    public void displayReferenceCardSimple(String title, String body, int lingerTimeMs) {
//        displayReferenceCardSimple(title, body, lingerTimeMs);
//    }

    private static final int NOTIFICATION = 0x4B; // Notification command
    private String createNotificationJson(String appIdentifier, String title, String subtitle, String message) {
        long currentTime = System.currentTimeMillis() / 1000L; // Unix timestamp in seconds
        String currentDate = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()); // Date format for 'date' field

        NCSNotification ncsNotification = new NCSNotification(
                notificationNum++,  // Increment sequence ID for uniqueness
                1,             // type (e.g., 1 = notification type)
                appIdentifier,
                title,
                subtitle,
                message,
                (int) currentTime,  // Cast long to int to match Python
                currentDate,        // Add the current date to the notification
                "AugmentOS" // display_name
        );

        Notification notification = new Notification(ncsNotification, "Add");

        Gson gson = new Gson();
        return gson.toJson(notification);
    }


    class Notification {
        NCSNotification ncs_notification;
        String type;

        public Notification() {
            // Default constructor
        }

        public Notification(NCSNotification ncs_notification, String type) {
            this.ncs_notification = ncs_notification;
            this.type = type;
        }
    }

    class NCSNotification {
        int msg_id;
        int type;
        String app_identifier;
        String title;
        String subtitle;
        String message;
        int time_s;  // Changed from long to int for consistency
        String date; // Added to match Python's date field
        String display_name;

        public NCSNotification(int msg_id, int type, String app_identifier, String title, String subtitle, String message, int time_s, String date, String display_name) {
            this.msg_id = msg_id;
            this.type = type;
            this.app_identifier = app_identifier;
            this.title = title;
            this.subtitle = subtitle;
            this.message = message;
            this.time_s = time_s;
            this.date = date; // Initialize the date field
            this.display_name = display_name;
        }
    }




    private List<byte[]> createNotificationChunks(String json) {
        final int MAX_CHUNK_SIZE = 176; // 180 - 4 header bytes
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int totalChunks = (int) Math.ceil((double) jsonBytes.length / MAX_CHUNK_SIZE);

        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            int start = i * MAX_CHUNK_SIZE;
            int end = Math.min(start + MAX_CHUNK_SIZE, jsonBytes.length);
            byte[] payloadChunk = Arrays.copyOfRange(jsonBytes, start, end);

            // Create the header
            byte[] header = new byte[] {
                    (byte) NOTIFICATION,
                    0x00, // notify_id (can be updated as needed)
                    (byte) totalChunks,
                    (byte) i
            };

            // Combine header and payload
            ByteBuffer chunk = ByteBuffer.allocate(header.length + payloadChunk.length);
            chunk.put(header);
            chunk.put(payloadChunk);

            chunks.add(chunk.array());
        }

        return chunks;
    }

    @Override
    public void displayReferenceCardSimple(String title, String body, int lingerTime) {
        if (!isConnected()) {
            Log.d(TAG, "Not connected to glasses");
            return;
        }

        List<byte[]> chunks = createTextWallChunks(title + "\n\n" + body);
        sendChunks(chunks);
        Log.d(TAG, "Send simple reference card");

        homeScreenInNSeconds(lingerTime);
    }

    @Override
    public void destroy() {
        Log.d(TAG, "EvenRealitiesG1SGC ONDESTROY");
        //disable the microphone
        setMicEnabled(false, 0);

        //stop sending heartbeat
        stopHeartbeat();

        // Stop periodic notifications
        stopPeriodicNotifications();

        // Stop periodic text wall
//        stopPeriodicNotifications();

        if (leftGlassGatt != null) {
            leftGlassGatt.disconnect();
            leftGlassGatt.close();
            leftGlassGatt = null;
        }
        if (rightGlassGatt != null) {
            rightGlassGatt.disconnect();
            rightGlassGatt.close();
            rightGlassGatt = null;
        }

        if (bondingReceiver != null) {
            context.unregisterReceiver(bondingReceiver);
        }

        stopScan(BluetoothAdapter.getDefaultAdapter());

        if (handler != null)
            handler.removeCallbacksAndMessages(null);
        if (heartbeatHandler != null)
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        if (whiteListHandler != null)
            whiteListHandler.removeCallbacksAndMessages(null);
        if (micEnableHandler != null)
            micEnableHandler.removeCallbacksAndMessages(null);
        if (notificationHandler != null)
            notificationHandler.removeCallbacks(notificationRunnable);
        if (textWallHandler != null)
            textWallHandler.removeCallbacks(textWallRunnable);
        if (goHomeHandler != null)
            goHomeHandler.removeCallbacks(goHomeRunnable);
    }


    @Override
    public boolean isConnected() {
        return mConnectState == 2;
    }

    // Remaining methods
    @Override
    public void displayCenteredText(String text) {}

    @Override
    public void showNaturalLanguageCommandScreen(String prompt, String naturalLanguageInput) {}

    @Override
    public void updateNaturalLanguageCommandScreen(String naturalLanguageArgs) {}

    @Override
    public void scrollingTextViewIntermediateText(String text) {}

    @Override
    public void scrollingTextViewFinalText(String text) {}

    @Override
    public void stopScrollingTextViewMode() {}

    @Override
    public void displayPromptView(String title, String[] options) {}

    @Override
    public void displayTextLine(String text) {}

    @Override
    public void displayBitmap(Bitmap bmp) {}

    public void blankScreen() {}

    public void displayDoubleTextWall(String textTop, String textBottom) {
        List<byte[]> chunks = createTextWallChunks(textTop + "\n\n" + textBottom);
        sendChunks(chunks);
        Log.d(TAG, "Send double text wall");
    }

    public void showHomeScreen() {
        Log.d(TAG, "EVEN SHOWING HOME SCREEN");
        displayTextWall(" ");
    }

    @Override
    public void setFontSize(SmartGlassesFontSize fontSize) {}

    public void displayRowsCard(String[] rowStrings) {}

    public void displayBulletList(String title, String[] bullets) {}

    public void displayReferenceCardImage(String title, String body, String imgUrl) {}

    public void displayTextWall(String a) {
        List<byte[]> chunks = createTextWallChunks(a);
        sendChunks(chunks);
        Log.d(TAG, "Sent text wall");
    }

    public void setFontSizes() {}

    // Heartbeat methods
    private byte[] constructHeartbeat() {
        ByteBuffer buffer = ByteBuffer.allocate(6);
        buffer.put((byte) 0x25);
        buffer.put((byte) 6);
        buffer.put((byte) (currentSeq & 0xFF));
        buffer.put((byte) 0x00);
        buffer.put((byte) 0x04);
        buffer.put((byte) (currentSeq++ & 0xFF));
        return buffer.array();
    }

    private void startHeartbeat(int delay) {
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        };
        heartbeatHandler.postDelayed(heartbeatRunnable, delay);
    }

    private void sendWhiteListCommand(int delay) {
        if (whiteListedAlready){
            return;
        }
        whiteListedAlready = true;

        Log.d(TAG, "Sending whitelist command");
        whiteListHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<byte[]> chunks = getWhitelistChunks();
                for (byte[] chunk : chunks) {
                    Log.d(TAG, "Sending this chunk for white list:" + bytesToUtf8(chunk));
                    sendDataSequentially(chunk, false);

                    // Sleep for 100 milliseconds between sending each chunk
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, delay);
    }

    private void stopHeartbeat() {
        if (heartbeatHandler != null) {
            heartbeatHandler.removeCallbacksAndMessages(null);
            heartbeatHandler.removeCallbacksAndMessages(heartbeatRunnable);
            heartbeatHandler = null;
        }
    }

    private void sendHeartbeat() {
        byte[] heartbeatPacket = constructHeartbeat();
        Log.d(TAG, "Sending heartbeat: " + bytesToHex(heartbeatPacket));

        sendDataSequentially(heartbeatPacket, false);

//        new Thread(() -> {
//            try {
//                if (leftGlassGatt != null && leftTxChar != null) {
//                    leftTxChar.setValue(heartbeatPacket);
//                    leftGlassGatt.writeCharacteristic(leftTxChar);
//                }
//                if (rightGlassGatt != null && rightTxChar != null) {
//                    rightTxChar.setValue(heartbeatPacket);
//                    rightGlassGatt.writeCharacteristic(rightTxChar);
//                }
//            } catch (Exception e) {
//                Log.e(TAG, "Error sending heartbeat: " + e.getMessage());
//            }
//        }).start();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    //microphone stuff
    public void setMicEnabled(boolean enable, int delay) {
        micEnableHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isConnected()) {
                    Log.d(TAG, "Tryna start mic: Not connected to glasses");
                    return;
                }

                byte command = 0x0E; // Command for MIC control
                byte enableByte = (byte) (enable ? 1 : 0); // 1 to enable, 0 to disable

                ByteBuffer buffer = ByteBuffer.allocate(2);
                buffer.put(command);
                buffer.put(enableByte);

                sendDataSequentially(buffer.array());
                Log.d(TAG, "Sent MIC command: " + bytesToHex(buffer.array()));
            }
        }, delay);
    }

    //notifications
    private void startPeriodicNotifications(int delay) {
        if (notifysStarted){
            return;
        }
        notifysStarted = true;

        notificationRunnable = new Runnable() {
            @Override
            public void run() {
                // Send notification
                sendPeriodicNotification();

                // Schedule the next notification
                notificationHandler.postDelayed(this, 12000);
            }
        };

        // Start the first notification after 5 seconds
        notificationHandler.postDelayed(notificationRunnable, delay);
    }

    private void sendPeriodicNotification() {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send notification: Not connected to glasses");
            return;
        }

        // Example notification data (replace with your actual data)
//        String json = createNotificationJson("com.augment.os", "QuestionAnswerer", "How much caffeine in dark chocolate?", "25 to 50 grams per piece");
        String json = createNotificationJson("com.augment.os", "QuestionAnswerer", "How much caffeine in dark chocolate?", "25 to 50 grams per piece");
        Log.d(TAG, "the JSON to send: " + json);
        List<byte[]> chunks = createNotificationChunks(json);
//        Log.d(TAG, "THE CHUNKS:");
//        Log.d(TAG, chunks.get(0).toString());
//        Log.d(TAG, chunks.get(1).toString());
        for (byte[] chunk : chunks) {
            Log.d(TAG, "Sent chunk to glasses: " + bytesToUtf8(chunk));
        }

        // Send each chunk with a short sleep between each send
        for (byte[] chunk : chunks) {
            sendDataSequentially(chunk);

            // Sleep for 100 milliseconds between sending each chunk
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Log.d(TAG, "Sent periodic notification");
    }

    //text wall debug
    private void startPeriodicTextWall(int delay) {
        if (textWallsStarted){
            return;
        }
        textWallsStarted = true;

        textWallRunnable = new Runnable() {
            @Override
            public void run() {
                // Send notification
                sendPeriodicTextWall();

                // Schedule the next notification
                textWallHandler.postDelayed(this, 12000);
            }
        };

        // Start the first text wall send after 5 seconds
        textWallHandler.postDelayed(textWallRunnable, delay);
    }

    // Constants for text wall display
    private static final int TEXT_COMMAND = 0x4E;  // Text command
    private static final int DISPLAY_WIDTH = 488;  // Display width in pixels
    private static final int FONT_SIZE = 16;      // Font size
    private static final int LINES_PER_SCREEN = 9; // Lines per screen
    private static final int MAX_CHUNK_SIZE = 176; // Maximum chunk size for BLE packets

    private int textSeqNum = 0; // Sequence number for text packets

    private List<byte[]> createTextWallChunks(String text) {
        // Split text into lines based on display width and font size
        List<String> lines = splitIntoLines(text);

        // Calculate total pages
        int totalPages = (int) Math.ceil((double) lines.size() / LINES_PER_SCREEN);
        List<byte[]> allChunks = new ArrayList<>();

        // Process each page
        for (int page = 0; page < totalPages; page++) {
            // Get lines for current page
            int startLine = page * LINES_PER_SCREEN;
            int endLine = Math.min(startLine + LINES_PER_SCREEN, lines.size());
            List<String> pageLines = lines.subList(startLine, endLine);

            // Combine lines for this page
            StringBuilder pageText = new StringBuilder();
            for (String line : pageLines) {
                pageText.append(line).append("\n");
            }

            byte[] textBytes = pageText.toString().getBytes(StandardCharsets.UTF_8);
            int totalChunks = (int) Math.ceil((double) textBytes.length / MAX_CHUNK_SIZE);

            // Create chunks for this page
            for (int i = 0; i < totalChunks; i++) {
                int start = i * MAX_CHUNK_SIZE;
                int end = Math.min(start + MAX_CHUNK_SIZE, textBytes.length);
                byte[] payloadChunk = Arrays.copyOfRange(textBytes, start, end);

                // Create header with protocol specifications
                byte screenStatus = 0x71; // New content (0x01) + Text Show (0x70)
                byte[] header = new byte[] {
                        (byte) TEXT_COMMAND,    // Command type
                        (byte) textSeqNum,      // Sequence number
                        (byte) totalChunks,     // Total packages
                        (byte) i,               // Current package number
                        screenStatus,           // Screen status
                        0x00,                   // new_char_pos0 (high)
                        0x00,                   // new_char_pos1 (low)
                        (byte) page,            // Current page number
                        (byte) totalPages       // Max page number
                };

                // Combine header and payload
                ByteBuffer chunk = ByteBuffer.allocate(header.length + payloadChunk.length);
                chunk.put(header);
                chunk.put(payloadChunk);

                allChunks.add(chunk.array());
            }

            // Increment sequence number for next page
            textSeqNum = (textSeqNum + 1) % 256;
        }

        return allChunks;
    }

    private List<String> splitIntoLines(String text) {
        // Replace specific symbols
        text = text.replace("⬆", "^").replace("⟶", "-");
        List<String> lines = new ArrayList<>();
        String[] rawLines = text.split("\n"); // Split by newlines first
        int charsPerLine = DISPLAY_WIDTH / (FONT_SIZE / 2); // Rough estimate

//        Log.d(TAG, "Characters per line: " + Arrays.toString(rawLines));

        for (String rawLine : rawLines) {
            if (rawLine.isEmpty()) {
                // Add an empty line for \n
                lines.add("");
                continue;
            }

            String[] words = rawLine.split("\\s+");
            StringBuilder currentLine = new StringBuilder();

            for (String word : words) {
                if (currentLine.length() + word.length() + 1 > charsPerLine) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                } else {
                    if (currentLine.length() > 0) {
                        currentLine.append(" ");
                    }
                    currentLine.append(word);
                }
            }

            if (currentLine.length() > 0) {
                lines.add(currentLine.toString().strip());
            }
//            Log.d(TAG, "Current lines: " + lines);
        }

        // Ensure there are at least 2 lines with "-"
        int dashLineCount = 0;
        for (String line : lines) {
            if (line.contains("-")) {
                dashLineCount++;
            }
        }

        // If there are fewer than 2 lines with "-", add empty lines at the top
        while (dashLineCount < 2) {
            lines.add(0, ""); // Add an empty line at the top
            dashLineCount++;
        }

//        Log.d(TAG, "Final split text into " + lines.size() + " lines: " + lines);
        return lines;
    }

    private void sendPeriodicTextWall() {
        if (!isConnected()) {
            Log.d(TAG, "Cannot send text wall: Not connected to glasses");
            return;
        }

        Log.d(TAG, "^^^^^^^^^^^^^ SENDING DEBUG TEXT WALL");

        // Example text wall content - replace with your actual text content
        String sampleText = "This is an example of a text wall that will be displayed on the glasses. " +
                "It demonstrates how text can be split into multiple pages and displayed sequentially. " +
                "Each page contains multiple lines, and each line is carefully formatted to fit the display width. " +
                "The text continues across multiple pages, showing how longer content can be handled effectively.";

        List<byte[]> chunks = createTextWallChunks(sampleText);

        // Send each chunk with a delay between sends
        for (byte[] chunk : chunks) {
            sendDataSequentially(chunk);

            try {
                Thread.sleep(150); // 150ms delay between chunks
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Log.d(TAG, "Sent text wall");
    }

    private static String bytesToUtf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void stopPeriodicNotifications() {
        if (notificationHandler != null && notificationRunnable != null) {
            notificationHandler.removeCallbacks(notificationRunnable);
            Log.d(TAG, "Stopped periodic notifications");
        }
    }

    // handle white list stuff
    private static final int WHITELIST_CMD = 0x04; // Command ID for whitelist
    public List<byte[]> getWhitelistChunks() {
        // Define the hardcoded whitelist JSON
        List<AppInfo> apps = new ArrayList<>();
        apps.add(new AppInfo("com.augment.os", "AugmentOS"));
        String whitelistJson = createWhitelistJson(apps);

        Log.d(TAG, "Creating chunks for hardcoded whitelist: " + whitelistJson);

        // Convert JSON to bytes and split into chunks
        return createWhitelistChunks(whitelistJson);
    }

    private String createWhitelistJson(List<AppInfo> apps) {
        JSONArray appList = new JSONArray();
        try {
            // Add each app to the list
            for (AppInfo app : apps) {
                JSONObject appJson = new JSONObject();
                appJson.put("id", app.getId());
                appJson.put("name", app.getName());
                appList.put(appJson);
            }

            JSONObject whitelistJson = new JSONObject();
            whitelistJson.put("calendar_enable", false);
            whitelistJson.put("call_enable", false);
            whitelistJson.put("msg_enable", false);
            whitelistJson.put("ios_mail_enable", false);

            JSONObject appObject = new JSONObject();
            appObject.put("list", appList);
            appObject.put("enable", true);

            whitelistJson.put("app", appObject);

            return whitelistJson.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Error creating whitelist JSON: " + e.getMessage());
            return "{}";
        }
    }

    // Simple class to hold app info
    class AppInfo {
        private String id;
        private String name;

        public AppInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
    }

    // Helper function to split JSON into chunks
    private List<byte[]> createWhitelistChunks(String json) {
        final int MAX_CHUNK_SIZE = 180 - 4; // Reserve space for the header
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int totalChunks = (int) Math.ceil((double) jsonBytes.length / MAX_CHUNK_SIZE);

        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            int start = i * MAX_CHUNK_SIZE;
            int end = Math.min(start + MAX_CHUNK_SIZE, jsonBytes.length);
            byte[] payloadChunk = Arrays.copyOfRange(jsonBytes, start, end);

            // Create the header: [WHITELIST_CMD, total_chunks, chunk_index]
            byte[] header = new byte[] {
                    (byte) WHITELIST_CMD,  // Command ID
                    (byte) totalChunks,   // Total number of chunks
                    (byte) i              // Current chunk index
            };

            // Combine header and payload
            ByteBuffer buffer = ByteBuffer.allocate(header.length + payloadChunk.length);
            buffer.put(header);
            buffer.put(payloadChunk);

            chunks.add(buffer.array());
        }

        return chunks;
    }

    @Override
    public void displayCustomContent(String content){
        Log.d(TAG, "DISPLAY CUSTOM CONTENT");
    }

    private void sendChunks(List<byte[]> chunks){
        // Send each chunk with a delay between sends
        for (byte[] chunk : chunks) {
            sendDataSequentially(chunk);

//            try {
//                Thread.sleep(DELAY_BETWEEN_CHUNKS_SEND); // delay between chunks
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
        }
    }

    public void homeScreenInNSeconds(int n){
        if (n == -1){
            return;
        }

        //disconnect after slight delay, so our above text gets a chance to show up
        goHomeHandler.removeCallbacksAndMessages(goHomeRunnable);
        goHomeHandler.removeCallbacksAndMessages(null);
        goHomeRunnable = new Runnable() {
            @Override
            public void run() {
                showHomeScreen();
            }};
        goHomeHandler.postDelayed(goHomeRunnable, n * 1000);
    }

}