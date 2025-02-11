package com.teamopensmartglasses.convoscope;

import static com.teamopensmartglasses.augmentoslib.AugmentOSGlobalConstants.AugmentOSManagerPackageName;
import static com.teamopensmartglasses.convoscope.BatteryOptimizationHelper.handleBatteryOptimization;
import static com.teamopensmartglasses.convoscope.BatteryOptimizationHelper.isSystemApp;
import static com.teamopensmartglasses.convoscope.Constants.BUTTON_EVENT_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.DIARIZE_QUERY_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.LLM_QUERY_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.REQUEST_APP_BY_PACKAGE_NAME_DOWNLOAD_LINK_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.UI_POLL_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.GEOLOCATION_STREAM_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.SET_USER_SETTINGS_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.GET_USER_SETTINGS_ENDPOINT;
import static com.teamopensmartglasses.convoscope.Constants.explicitAgentQueriesKey;
import static com.teamopensmartglasses.convoscope.Constants.explicitAgentResultsKey;
import static com.teamopensmartglasses.convoscope.Constants.glassesCardTitle;
import static com.teamopensmartglasses.convoscope.Constants.notificationFilterKey;
import static com.teamopensmartglasses.convoscope.Constants.shouldUpdateSettingsKey;
import static com.teamopensmartglasses.convoscope.Constants.displayRequestsKey;
import static com.teamopensmartglasses.convoscope.Constants.wakeWordTimeKey;
import static com.teamopensmartglasses.convoscope.Constants.augmentOsMainServiceNotificationId;
import static com.teamopensmartglasses.convoscope.statushelpers.JsonHelper.convertJsonToMap;
import static com.teamopensmartglasses.smartglassesmanager.SmartGlassesAndroidService.getSmartGlassesDeviceFromModelName;
import static com.teamopensmartglasses.smartglassesmanager.SmartGlassesAndroidService.savePreferredWearable;
import static com.teamopensmartglasses.smartglassesmanager.smartglassescommunicators.EvenRealitiesG1SGC.deleteEvenSharedPreferences;
import static com.teamopensmartglasses.smartglassesmanager.smartglassescommunicators.EvenRealitiesG1SGC.savePreferredG1DeviceId;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.posthog.java.PostHog;
import com.teamopensmartglasses.augmentoslib.DataStreamType;
import com.teamopensmartglasses.augmentoslib.PhoneNotification;
import com.teamopensmartglasses.augmentoslib.ThirdPartyApp;
import com.teamopensmartglasses.augmentoslib.ThirdPartyAppType;
import com.teamopensmartglasses.augmentoslib.events.NotificationEvent;
import com.teamopensmartglasses.augmentoslib.events.SubscribeDataStreamRequestEvent;
import com.teamopensmartglasses.augmentoslib.events.TextWallViewRequestEvent;
import com.teamopensmartglasses.convoscope.comms.AugmentOsActionsCallback;
import com.teamopensmartglasses.convoscope.comms.AugmentosBlePeripheral;
import com.teamopensmartglasses.convoscope.events.AugmentosSmartGlassesDisconnectedEvent;
import com.teamopensmartglasses.convoscope.events.GoogleAuthFailedEvent;
import com.teamopensmartglasses.convoscope.convoscopebackend.BackendServerComms;
import com.teamopensmartglasses.convoscope.convoscopebackend.VolleyJsonCallback;
import com.teamopensmartglasses.convoscope.events.NewScreenImageEvent;
import com.teamopensmartglasses.convoscope.events.NewScreenTextEvent;
import com.teamopensmartglasses.convoscope.events.ThirdPartyAppErrorEvent;
import com.teamopensmartglasses.convoscope.events.SignOutEvent;
import com.teamopensmartglasses.convoscope.events.TriggerSendStatusToAugmentOsManagerEvent;
import com.teamopensmartglasses.convoscope.statushelpers.BatteryStatusHelper;
import com.teamopensmartglasses.convoscope.statushelpers.DeviceInfo;
import com.teamopensmartglasses.convoscope.statushelpers.GsmStatusHelper;
import com.teamopensmartglasses.convoscope.statushelpers.WifiStatusHelper;
import com.teamopensmartglasses.convoscope.tpa.TPASystem;
import com.teamopensmartglasses.convoscope.ui.AugmentosUi;

import com.teamopensmartglasses.smartglassesmanager.SmartGlassesAndroidService;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.BrightnessLevelEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.DisplayGlassesDashboardEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.GlassesBluetoothSearchDiscoverEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.GlassesBluetoothSearchStopEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.BatteryLevelEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.GlassesDisplayPowerEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.GlassesHeadDownEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.GlassesHeadUpEvent;
import com.teamopensmartglasses.smartglassesmanager.eventbusmessages.SetSensingEnabledEvent;
import com.teamopensmartglasses.smartglassesmanager.speechrecognition.SpeechRecSwitchSystem;
import com.teamopensmartglasses.smartglassesmanager.supportedglasses.SmartGlassesDevice;

import com.teamopensmartglasses.augmentoslib.events.DiarizationOutputEvent;
import com.teamopensmartglasses.augmentoslib.events.GlassesTapOutputEvent;
import com.teamopensmartglasses.augmentoslib.events.SmartGlassesConnectedEvent;
import com.teamopensmartglasses.augmentoslib.events.SmartRingButtonOutputEvent;
import com.teamopensmartglasses.augmentoslib.events.SpeechRecOutputEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
//SpeechRecIntermediateOutputEvent
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.teamopensmartglasses.smartglassesmanager.utils.EnvHelper;

import android.app.DownloadManager;
import android.net.Uri;
import android.os.Environment;


public class AugmentosService extends Service implements AugmentOsActionsCallback {
    public final String TAG = "AugmentOS_AugmentOSService";

    private final IBinder binder = new LocalBinder();

    private static final String POSTHOG_API_KEY = "phc_J7nhqRlkNVoUjKxQZnpYtqRoyEeLl3gFCwYsajxFvpc";
    private static final String POSTHOG_HOST = "https://us.i.posthog.com";
    private FirebaseAuth firebaseAuth;
    private FirebaseAuth.AuthStateListener authStateListener;
    private FirebaseAuth.IdTokenListener idTokenListener;

    private final String notificationAppName = "AugmentOS Core";
    private final String notificationDescription = "Running in foreground";
    private final String myChannelId = "augmentos_core";
    public static final String ACTION_START_CORE = "ACTION_START_CORE";
    public static final String ACTION_STOP_CORE = "ACTION_STOP_CORE";

    public static final String ACTION_START_FOREGROUND_SERVICE = "MY_ACTION_START_FOREGROUND_SERVICE";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "MY_ACTION_STOP_FOREGROUND_SERVICE";

    private BatteryStatusHelper batteryStatusHelper;
    private WifiStatusHelper wifiStatusHelper;
    private GsmStatusHelper gsmStatusHelper;



    //Convoscope stuff
    String authToken = "";
    private BackendServerComms backendServerComms;
    ArrayList<String> responsesBuffer;
    ArrayList<String> transcriptsBuffer;
    ArrayList<String> responsesToShare;
    private final Handler csePollLoopHandler = new Handler(Looper.getMainLooper());
    private Runnable cseRunnableCode;
    private final Handler displayPollLoopHandler = new Handler(Looper.getMainLooper());
    private final Handler locationSendingLoopHandler = new Handler(Looper.getMainLooper());
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Handler screenCaptureHandler = new Handler();
    private Runnable screenCaptureRunnable;
    private Runnable uiPollRunnableCode;
    private Runnable displayRunnableCode;
    private Runnable locationSendingRunnableCode;
    private long lastDataSentTime = 0;
    private final long POLL_INTERVAL_ACTIVE = 200; // 200ms when actively sending data
    private final long POLL_INTERVAL_INACTIVE = 5000; // 5000ms (5s) when inactive
    private final long DATA_SENT_THRESHOLD = 90000; // 90 seconds
    private LocationSystem locationSystem;
    static final String deviceId = "android";
    public String proactiveAgents = "proactive_agent_insights";
    public String explicitAgent = "explicit_agent_insights";
    public String definerAgent = "intelligent_entity_definitions";
    public String languageLearningAgent = "language_learning";
    public String llWordSuggestUpgradeAgent = "ll_word_suggest_upgrade";
    public String llContextConvoAgent = "ll_context_convo";
    public String adhdStmbAgent = "adhd_stmb_agent_summaries";
    public double previousLat = 0;
    public double previousLng = 0;

    //language learning buffer stuff
    private LinkedList<DefinedWord> definedWords = new LinkedList<>();
    private LinkedList<STMBSummary> adhdStmbSummaries = new LinkedList<>();
    private LinkedList<LLUpgradeResponse> llUpgradeResponses = new LinkedList<>();
    private LinkedList<LLCombineResponse> llCombineResponses = new LinkedList<>();
    private LinkedList<ContextConvoResponse> contextConvoResponses = new LinkedList<>();
    private final long llDefinedWordsShowTime = 40 * 1000; // define in milliseconds
    private final long llContextConvoResponsesShowTime = 3 * 60 * 1000; // define in milliseconds
    private final long locationSendTime = 1000 * 10; // define in milliseconds
    private final long adhdSummaryShowTime = 10 * 60 * 1000; // define in milliseconds
    private final long llUpgradeShowTime = 5 * 60 * 1000; // define in milliseconds
    private final long llCombineShowTime = 5 * 60 * 1000; // define in milliseconds
    private final int maxDefinedWordsShow = 4;
    private final int maxLLCombineShow = 5;
    private final int maxAdhdStmbShowNum = 3;
    private final int maxContextConvoResponsesShow = 2;
    private final int maxLLUpgradeResponsesShow = 2;
    private final int charsPerTranscript = 90;
    private final int charsPerHanziTranscript = 36;

    long previousWakeWordTime = -1; // Initialize this at -1
    int numConsecutiveAuthFailures = 0;
    private long currTime = 0;
    private long lastPressed = 0;
    private long lastTapped = 0;

    //clear screen to start
    public boolean clearedScreenYet = false;

    String currentLiveCaption = "";
    String finalLiveCaption = "";
    String llCurrentString = "";

    private String translationText = "";
    private String liveCaptionText = "";

    private String finalLiveCaptionText = "";
    private String finalTranslationText = "";

    // Double clicking constants
    private final long doublePressTimeConst = 420;
    private final long doubleTapTimeConst = 600;
    private boolean segmenterLoaded = false;
    private boolean segmenterLoading = false;
    private boolean hasUserBeenNotified = false;

    public TPASystem tpaSystem;

    PostHog postHog;

    private String userId;

    private AugmentosBlePeripheral blePeripheral;

    public AugmentosSmartGlassesService smartGlassesService;
    private boolean isSmartGlassesServiceBound = false;
    private final List<Runnable> serviceReadyListeners = new ArrayList<>();
    private NotificationSystem notificationSystem;

    private Integer batteryLevel;
    private Integer brightnessLevel;

    private boolean showingDashboardNow = false;
    private boolean contextualDashboardEnabled;

    public AugmentosService() {
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AugmentosSmartGlassesService.LocalBinder binder = (AugmentosSmartGlassesService.LocalBinder) service;
            smartGlassesService = (AugmentosSmartGlassesService) binder.getService();
            isSmartGlassesServiceBound = true;
            tpaSystem.setSmartGlassesService(smartGlassesService);
            for (Runnable action : serviceReadyListeners) {
                action.run();
            }
            serviceReadyListeners.clear();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG,"SMART GLASSES SERVICE DISCONNECTED!!!!");
            isSmartGlassesServiceBound = false;
            smartGlassesService = null;
            tpaSystem.setSmartGlassesService(smartGlassesService);

            // TODO: For now, stop all apps on disconnection
            // TODO: Future: Make this nicer
            tpaSystem.stopAllThirdPartyApps();
            sendStatusToAugmentOsManager();
        }
    };

    @Subscribe
    public void onAugmentosSmartGlassesDisconnectedEvent(AugmentosSmartGlassesDisconnectedEvent event){
        // TODO: For now, stop all apps on disconnection
        // TODO: Future: Make this nicer
        tpaSystem.stopAllThirdPartyApps();
        sendStatusToAugmentOsManager();
    }

    public void onTriggerSendStatusToAugmentOsManagerEvent(TriggerSendStatusToAugmentOsManagerEvent event) {
        sendStatusToAugmentOsManager();
    }

    @Subscribe
    public void onGlassesHeadUpEvent(GlassesHeadUpEvent event){
        EventBus.getDefault().post(new DisplayGlassesDashboardEvent());
    }

    @Subscribe
    public void onGlassesHeadDownEvent(GlassesHeadDownEvent event){
        if (smartGlassesService != null)
            smartGlassesService.windowManager.hideDashboard();
    }


    @Subscribe
    public void onGlassesTapSideEvent(GlassesTapOutputEvent event) {
        int numTaps = event.numTaps;
        boolean sideOfGlasses = event.sideOfGlasses;
        long time = event.timestamp;

        Log.d(TAG, "GLASSES TAPPED X TIMES: " + numTaps + " SIDEOFGLASSES: " + sideOfGlasses);
        if (smartGlassesService == null) return;
        if (numTaps == 2 || numTaps == 3) {
            if (smartGlassesService.windowManager.isDashboardShowing()) {
                smartGlassesService.windowManager.hideDashboard();
            } else {
                Log.d(TAG, "GOT A DOUBLE+ TAP");
                EventBus.getDefault().post(new DisplayGlassesDashboardEvent());
            }
        }
    }

    @Subscribe
    public void onThirdPartyAppErrorEvent(ThirdPartyAppErrorEvent event) {
        if (blePeripheral != null) {
            blePeripheral.sendNotifyManager(event.text, "error");
        }
        if (tpaSystem != null) {
            tpaSystem.stopThirdPartyAppByPackageName(event.packageName);
        }
        if (smartGlassesService != null) {
            smartGlassesService.windowManager.showAppLayer("system", () -> AugmentosSmartGlassesService.sendReferenceCard("App error", event.text), 10);
        }
        sendStatusToAugmentOsManager();
    }

    //TODO NO MORE PASTA
    public ArrayList<String> notificationList = new ArrayList<String>();
    @Subscribe
    public void onDisplayGlassesDashboardEvent(DisplayGlassesDashboardEvent event) {
        if (!contextualDashboardEnabled) {
            return;
        }

        // Get current time and date
        SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm", Locale.getDefault());
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());
        String currentTime = timeFormat.format(new Date());
        String currentDate = dateFormat.format(new Date());

        // Get battery level
//            IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
//            Intent batteryStatus = this.registerReceiver(null, iFilter);
//            int level = batteryStatus != null ?
//                    batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
//            int scale = batteryStatus != null ?
//                    batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
//            float batteryPct = level * 100 / (float)scale;

        // Build dashboard string with fancy formatting
        StringBuilder dashboard = new StringBuilder();
        //     dashboard.append("Dashboard - AugmentOS\n");
        dashboard.append(String.format(Locale.getDefault(), "│ %s, %s, %d%%\n", currentDate, currentTime, batteryLevel));
        //dashboard.append(String.format("│ Date      │ %s\n", currentDate));
//            dashboard.append(String.format("│ Battery │ %.0f%%\n", batteryPct));
//            dashboard.append("│ BLE       │ ON\n");

        boolean recentNotificationFound = false;
        ArrayList<PhoneNotification> notifications = notificationSystem.getNotificationQueue();
        PhoneNotification mostRecentNotification = null;
        long mostRecentTime = 0;
        long now = System.currentTimeMillis();

        for (PhoneNotification notification : notifications) {
            long notificationTime = notification.getTimestamp();
            if ((notificationTime + 5000) > now) {  // 5 seconds in milliseconds
                if (mostRecentTime == 0 || notificationTime > mostRecentTime) {
                    mostRecentTime = notificationTime;
                    mostRecentNotification = notification;
                }
            }
        }

        if (mostRecentNotification != null) {
            dashboard.append(String.format("│ %s - %s\n",
                    mostRecentNotification.getTitle(),
                    mostRecentNotification.getText()));
            recentNotificationFound = true;
        }

        // If no recent notification was found, display from the list
        if (!recentNotificationFound) {
            int notificationCount = Math.min(2, notificationList.size());
            for (int i = 0; i < notificationCount; i++) {
                dashboard.append(String.format("│ %s\n", notificationList.get(i)));
            }
        }

        // Send to text wall
        if (smartGlassesService != null) {
            smartGlassesService.windowManager.showDashboard(()->smartGlassesService.sendTextWall(dashboard.toString()), -1);
        }
        Log.d(TAG, "Dashboard displayed: " + dashboard.toString());
    }

    @Subscribe
    public void onGlassBatteryLevelEvent(BatteryLevelEvent event) {
//        Log.d(TAG, "BATTERY received");
        batteryLevel = event.batteryLevel;
        sendStatusToAugmentOsManager();
    }

    @Subscribe
    public void onBrightnessLevelEvent(BrightnessLevelEvent event) {
//        Log.d(TAG, "BRIGHTNESS received");
        brightnessLevel = event.brightnessLevel;
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(this.getResources().getString(com.teamopensmartglasses.smartglassesmanager.R.string.SHARED_PREF_BRIGHTNESS), String.valueOf(brightnessLevel))
                .apply();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void onCreate() {
        super.onCreate();

//        createNotificationChannel(); // New method to ensure one-time channel creation
//        startForeground(augmentOsMainServiceNotificationId, updateNotification());
        EnvHelper.init(this);
        //setup event bus subscribers
        EventBus.getDefault().register(this);

        getUserId();
        postHog = new PostHog.Builder(POSTHOG_API_KEY).host(POSTHOG_HOST).build();

        Map<String, Object> props = new HashMap<>();
        props.put("timestamp", System.currentTimeMillis());
        props.put("device_info", DeviceInfo.getDeviceInfo());
        postHog.capture(userId, "augmentos_service_started", props);

        //make responses holder
        responsesBuffer = new ArrayList<>();
        responsesToShare = new ArrayList<>();
        responsesBuffer.add("Welcome to AugmentOS.");

        //make responses holder
        transcriptsBuffer = new ArrayList<>();

        //setup backend comms
        backendServerComms = new BackendServerComms(this);
        batteryStatusHelper = new BatteryStatusHelper(this);
        wifiStatusHelper = new WifiStatusHelper(this);
        gsmStatusHelper = new GsmStatusHelper(this);

        notificationSystem = new NotificationSystem(this);

        contextualDashboardEnabled = getContextualDashboardEnabled();
        //startNotificationService();

        //what is the preferred wearable?
        String preferredWearable = AugmentosSmartGlassesService.getPreferredWearable(this);

        // load pinyin converter in the background
        // new Thread(this::loadSegmenter).start();
//        if (
//            (super.getSelectedLiveCaptionsTranslation(this) == 1 && getChosenTranscribeLanguage(this).equals("Chinese (Pinyin)")) ||
//            (super.getSelectedLiveCaptionsTranslation(this) == 2 && (getChosenSourceLanguage(this).equals("Chinese (Pinyin)") ||
//                getChosenTargetLanguage(this).equals("Chinese (Pinyin)") && getChosenTranscribeLanguage(this).equals(getChosenSourceLanguage(this)))
//            )
//        ) {
//            new Thread(this::loadSegmenter).start();
//        }

        // Init TPA broadcast receivers
        tpaSystem = new TPASystem(this, smartGlassesService);

        // Initialize BLE Peripheral
        blePeripheral = new AugmentosBlePeripheral(this, this);
        if (!tpaSystem.isAppInstalled(AugmentOSManagerPackageName)) {
            // TODO: While we use simulated puck, disable the BLE Peripheral for testing
            // TODO: For now, just disable peripheral if manager is installed on same device
            // blePeripheral.start();
        }

        completeInitialization();
    }

    private void getUserId() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        userId = prefs.getString("user_id", "");

        if (userId.isEmpty()) {
            // Generate a random UUID string if no userId exists
            userId = UUID.randomUUID().toString();

            // Save the new userId to SharedPreferences
            prefs.edit()
                    .putString("user_id", userId)
                    .apply();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    myChannelId,
                    notificationAppName,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(notificationDescription);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public void completeInitialization(){
        Log.d(TAG, "COMPLETE CONVOSCOPE INITIALIZATION");
        setUpUiPolling();
        // setUpLocationSending();

        getCurrentMode(this);

        //update settings on backend on launch
        // updateTargetLanguageOnBackend(this);
        // updateSourceLanguageOnBackend(this);
        // updateVocabularyUpgradeOnBackend(this);
        saveCurrentMode(this, getCurrentMode(this));

        saveCurrentMode(this, "");

        // Whitelist AugmentOS from battery optimization when system app
        // If not system app, bring up the settings menu
        if (isSystemApp(this)) {
            handleBatteryOptimization(this);
        }

        // TODO: Uncomment for auto-connect
        String preferredWearable = AugmentosSmartGlassesService.getPreferredWearable(this);
        if(!preferredWearable.isEmpty()) {
            SmartGlassesDevice preferredDevice = AugmentosSmartGlassesService.getSmartGlassesDeviceFromModelName(preferredWearable);
            if (preferredDevice != null) {
                executeOnceSmartGlassesServiceReady(this, () -> {
                    smartGlassesService.connectToSmartGlasses(preferredDevice);
                });
            } else {
                // We have some invalid device saved... delete from preferences
                AugmentosSmartGlassesService.savePreferredWearable(this, "");
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent == null || intent.getAction() == null) {
            Log.e(TAG, "Received null intent or null action");
            return Service.START_STICKY; // Or handle this scenario appropriately
        }

        String action = intent.getAction();
        Bundle extras = intent.getExtras();

        switch (action) {
            case ACTION_START_CORE:
            case ACTION_START_FOREGROUND_SERVICE:
                // start the service in the foreground
                Log.d("TEST", "starting foreground");
                createNotificationChannel(); // New method to ensure one-time channel creation
                startForeground(augmentOsMainServiceNotificationId, updateNotification());

                // Send out the status once AugmentOS_Core is ready :)
                // tpaSystem.stopThirdPartyAppByPackageName(AugmentOSManagerPackageName);
                tpaSystem.startThirdPartyAppByPackageName(AugmentOSManagerPackageName);

                break;
            case ACTION_STOP_CORE:
            case ACTION_STOP_FOREGROUND_SERVICE:
                stopForeground(true);
                stopSelf();
                break;
            default:
                Log.d(TAG, "Unknown action received in onStartCommand");
                Log.d(TAG, action);
        }
        return Service.START_STICKY;
    }


    private Notification updateNotification() {
        Context context = getApplicationContext();

        PendingIntent action = PendingIntent.getActivity(context,
                0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE); // Flag indicating that if the described PendingIntent already exists, the current one should be canceled before generating a new one.

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder;

        String CHANNEL_ID = myChannelId;

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, notificationAppName,
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(notificationDescription);
        manager.createNotificationChannel(channel);

        builder = new NotificationCompat.Builder(this, CHANNEL_ID);

        return builder.setContentIntent(action)
                .setContentTitle(notificationAppName)
                .setContentText(notificationDescription)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setTicker("...")
                .setContentIntent(action)
                .setOngoing(true).build();
    }

    // Method to start the Smart Glasses Service and bind to it
    public void startSmartGlassesService() {
        Intent intent = new Intent(this, AugmentosSmartGlassesService.class);
        // startService(intent);  // Start the service if it's not already running
        bindService(intent, connection, Context.BIND_AUTO_CREATE);  // Bind to the service
    }


    public void stopSmartGlassesService() {
        if (smartGlassesService != null) {
            unbindService(connection);  // Unbind from the service
            isSmartGlassesServiceBound = false;
            smartGlassesService = null;
            tpaSystem.setSmartGlassesService(smartGlassesService);
        }
        Intent intent = new Intent(this, AugmentosSmartGlassesService.class);
        stopService(intent);  // Stop the service
    }

    @Subscribe
    public void onGlassesDisplayPowerEvent(GlassesDisplayPowerEvent event) {
        if (smartGlassesService == null) return;
        if (event.turnedOn) {
            smartGlassesService.windowManager.showAppLayer("system", () -> smartGlassesService.sendReferenceCard("AugmentOS Connected", "Screen back on"), 4);
        }
    }
    @Subscribe
    public void onGlassesConnnected(SmartGlassesConnectedEvent event) {
        Log.d(TAG, "Got event for onGlassesConnected....");
        sendStatusToAugmentOsManager();

        Log.d(TAG, "****************** SENDING REFERENCE CARD: CONNECTED TO AUGMENT OS");
        if (smartGlassesService != null)
            smartGlassesService.windowManager.showAppLayer("system", () -> smartGlassesService.sendReferenceCard("Connected", "Connected to AugmentOS"), 6);

        Map<String, Object> props = new HashMap<>();
        props.put("glasses_model_name", event.device.deviceModelName);
        props.put("timestamp", System.currentTimeMillis());
        postHog.capture(userId, "glasses_connected", props);
    }

    public void handleSignOut(){
        EventBus.getDefault().post(new SignOutEvent());
    }

    public void sendSettings(JSONObject settingsObj){
        try{
            settingsObj.put("timestamp", System.currentTimeMillis() / 1000);
            backendServerComms.restRequest(SET_USER_SETTINGS_ENDPOINT, settingsObj, new VolleyJsonCallback(){
                @Override
                public void onSuccess(JSONObject result){
                    try {
                        Log.d(TAG, "GOT Settings update result: " + result.toString());
                        String query_answer = result.getString("message");
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
                @Override
                public void onFailure(int code){
                    Log.d(TAG, "SOME FAILURE HAPPENED (sendSettings)");

                }

            });
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void getSettings(){
        try{
            Log.d(TAG, "Runnign get settings");
            Context mContext = this.getApplicationContext();
            JSONObject getSettingsObj = new JSONObject();
            backendServerComms.restRequest(GET_USER_SETTINGS_ENDPOINT, getSettingsObj, new VolleyJsonCallback(){
                @Override
                public void onSuccess(JSONObject result){
                    try {
                        Log.d(TAG, "GOT GET Settings update result: " + result.toString());
                        JSONObject settings = result.getJSONObject("settings");
                        Boolean useDynamicTranscribeLanguage = settings.getBoolean("use_dynamic_transcribe_language");
                        String dynamicTranscribeLanguage = settings.getString("dynamic_transcribe_language");
                        Log.d(TAG, "Should use dynamic? " + useDynamicTranscribeLanguage);
                        if (useDynamicTranscribeLanguage){
                            Log.d(TAG, "Switching running transcribe language to: " + dynamicTranscribeLanguage);
                            if (smartGlassesService != null)
                                smartGlassesService.switchRunningTranscribeLanguage(dynamicTranscribeLanguage);
                        } else {
                            if (smartGlassesService != null)
                                smartGlassesService.switchRunningTranscribeLanguage(smartGlassesService.getChosenTranscribeLanguage(mContext));
                        }
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
                @Override
                public void onFailure(int code){
                    Log.d(TAG, "SOME FAILURE HAPPENED (getSettings)");
                }
            });
        } catch (Exception e){
            e.printStackTrace();
            Log.d(TAG, "SOME FAILURE HAPPENED (getSettings)");
        }
    }

    public void setUpUiPolling(){
        uiPollRunnableCode = new Runnable() {
            @Override
            public void run() {
                if (smartGlassesService != null) {
                    requestUiPoll();
                }
                long currentTime = System.currentTimeMillis();
                long interval = (currentTime - lastDataSentTime < DATA_SENT_THRESHOLD) ? POLL_INTERVAL_ACTIVE : POLL_INTERVAL_INACTIVE;
                csePollLoopHandler.postDelayed(this, interval);
            }
        };
        csePollLoopHandler.post(uiPollRunnableCode);
    }

    public void setUpLocationSending(){
        locationSystem = new LocationSystem(getApplicationContext());

        if (locationSendingLoopHandler != null){
            locationSendingLoopHandler.removeCallbacksAndMessages(this);
        }

        locationSendingRunnableCode = new Runnable() {
            @Override
            public void run() {
                if (smartGlassesService != null)
                    requestLocation();
                locationSendingLoopHandler.postDelayed(this, locationSendTime);
            }
        };
        locationSendingLoopHandler.post(locationSendingRunnableCode);
    }

    @Override
    public void onDestroy(){
        csePollLoopHandler.removeCallbacks(uiPollRunnableCode);
        displayPollLoopHandler.removeCallbacks(displayRunnableCode);
        locationSystem.stopLocationUpdates();
        locationSendingLoopHandler.removeCallbacks(locationSendingRunnableCode);
        locationSendingLoopHandler.removeCallbacksAndMessages(null);
        screenCaptureHandler.removeCallbacks(screenCaptureRunnable);
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        EventBus.getDefault().unregister(this);

        if (authStateListener != null) {
            firebaseAuth.removeAuthStateListener(authStateListener);
        }
        if (idTokenListener != null) {
            firebaseAuth.removeIdTokenListener(idTokenListener);
        }

//        stopNotificationService();

        if (blePeripheral != null) {
            blePeripheral.destroy();
        }

        if (smartGlassesService != null) {
            unbindService(connection);
            isSmartGlassesServiceBound = false;
            smartGlassesService = null;
            tpaSystem.setSmartGlassesService(smartGlassesService);
        }

        if(tpaSystem != null) {
            tpaSystem.destroy();
        }

        postHog.shutdown();

        super.onDestroy();
    }

    @Subscribe
    public void onSmartRingButtonEvent(SmartRingButtonOutputEvent event) {
        int buttonId = event.buttonId;
        long time = event.timestamp;
        boolean isDown = event.isDown;

        if(!isDown || buttonId != 1) return;
        Log.d(TAG,"DETECTED BUTTON PRESS W BUTTON ID: " + buttonId);
        currTime = System.currentTimeMillis();

        //Detect double presses
        if(isDown && currTime - lastPressed < doublePressTimeConst) {
            Log.d(TAG, "Double tap - CurrTime-lastPressed: "+ (currTime-lastPressed));
//            sendLatestCSEResultViaSms();
        }

        if(isDown) {
            lastPressed = System.currentTimeMillis();
        }
    }

//    public void sendLatestCSEResultViaSms(){
//        if (phoneNum == "") return;
//
//        if (responses.size() > 1) {
//            //Send latest CSE result via sms;
//            String messageToSend = responsesToShare.get(responsesToShare.size() - 1);
//
//            smsComms.sendSms(phoneNum, messageToSend);
//
//            sendReferenceCard("Convoscope", "Sending result(s) via SMS to " + phoneNumName);
//        }
//    }

    @Subscribe
    public void onSubscribeDataStreamRequestEvent(SubscribeDataStreamRequestEvent event){
        Log.d(TAG, "Got a request to subscribe to data stream");

        if (event.dataStreamType == DataStreamType.TRANSCRIPTION_DEFAULT_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN DEFAULT LANGUAGE");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("English");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_ENGLISH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN ENGLISH");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("English");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_CHINESE_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN CHINESE");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Chinese");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_RUSSIAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN RUSSIAN");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Russian");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_FRENCH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN FRENCH");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("French");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_SPANISH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN SPANISH");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Spanish");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_JAPANESE_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN JAPANESE");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Japanese");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_GERMAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN GERMAN");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("German");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_ARABIC_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN ARABIC");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Arabic");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_KOREAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN KOREAN");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Korean");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_ITALIAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN ITALIAN");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Italian");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_TURKISH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN TURKISH");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Turkish");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_PORTUGUESE_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN PORTUGUESE");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Portuguese");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSCRIPTION_DUTCH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSCRIBING IN DUTCH");
            if (smartGlassesService != null) {
                smartGlassesService.switchRunningTranscribeLanguage("Dutch");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_DEFAULT_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO DEFAULT LANGUAGE");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Default");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_ENGLISH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO ENGLISH");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("English");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_CHINESE_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO CHINESE");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Chinese");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_RUSSIAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO RUSSIAN");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Russian");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_FRENCH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO FRENCH");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("French");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_SPANISH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO SPANISH");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Spanish");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_JAPANESE_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO JAPANESE");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Japanese");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_GERMAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO GERMAN");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("German");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_ARABIC_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO ARABIC");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Arabic");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_KOREAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO KOREAN");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Korean");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_ITALIAN_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO ITALIAN");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Italian");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_TURKISH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO TURKISH");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Turkish");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_PORTUGUESE_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO PORTUGUESE");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Portuguese");
            }
        } else if (event.dataStreamType == DataStreamType.TRANSLATION_DUTCH_STREAM) {
            Log.d(TAG, "REQUESTED START TRANSLATING TO DUTCH");
            if (smartGlassesService != null) {
                smartGlassesService.startTranslationStream("Dutch");
            }
        } else if (event.dataStreamType == DataStreamType.KILL_TRANSLATION_STREAM) {
            Log.d(TAG, "REQUESTED KILL TRANSLATION STREAM");
//            if (smartGlassesService != null) {
//                smartGlassesService.killTranslationStream();
//            }
        }
        else {
            Log.d(TAG, "UNKNOWN DATA STREAM TYPE REQUESTED");
        }
    }

    private Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    @Subscribe
    public void onDiarizeData(DiarizationOutputEvent event) {
        Log.d(TAG, "SENDING DIARIZATION STUFF");
        try{
            JSONObject jsonQuery = new JSONObject();
            jsonQuery.put("transcript_meta_data", event.diarizationData);
            jsonQuery.put("timestamp", System.currentTimeMillis() / 1000);
            backendServerComms.restRequest(DIARIZE_QUERY_ENDPOINT, jsonQuery, new VolleyJsonCallback(){
                @Override
                public void onSuccess(JSONObject result){
                    try {
                        parseSendTranscriptResult(result);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
                @Override
                public void onFailure(int code){
                    Log.d(TAG, "SOME FAILURE HAPPENED (send Diarize Data)");
                }

            });
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onTranscript(SpeechRecOutputEvent event) {
        String text = event.text;
//        long time = event.timestamp;
        boolean isFinal = event.isFinal;
        boolean isTranslated = event.isTranslated;
//        Log.d(TAG, "PROCESS TRANSCRIPTION CALLBACK. IS IT FINAL? " + isFinal + " " + text);

        if (isFinal && !isTranslated) {
            transcriptsBuffer.add(text);
            //       sendFinalTranscriptToActivity(text);
        }

        if(smartGlassesService == null) return;
//
//        if (Objects.equals(getCurrentMode(this), "Language Learning")) {
//            //debounce and then send to backend
//            if (!isTranslated && smartGlassesService.getSelectedLiveCaptionsTranslation(this) != 2) debounceAndSendTranscript(text, isFinal);
//    //        getSettings();
//            // Send transcript to user if live captions are enabled
//            if (smartGlassesService.getSelectedLiveCaptionsTranslation(this) != 0) { // 0 is language learning mode
//    //            showTranscriptsToUser(text, isFinal);
//                debounceAndShowTranscriptOnGlasses(text, isFinal, isTranslated);
//            }
//        }
    }

    private Handler glassesTranscriptDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable glassesTranscriptDebounceRunnable;
    private long glassesTranscriptLastSentTime = 0;
    private long glassesTranslatedTranscriptLastSentTime = 0;
    private final long GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY = 400; // in milliseconds

//    private void debounceAndShowTranscriptOnGlasses(String transcript, boolean isFinal, boolean isTranslated) {
//        glassesTranscriptDebounceHandler.removeCallbacks(glassesTranscriptDebounceRunnable);
//        long currentTime = System.currentTimeMillis();
//
//        if (isFinal) {
//            showTranscriptsToUser(transcript, isTranslated, true);
//            return;
//        }
//
//        // if intermediate
//        if (smartGlassesService != null && smartGlassesService.getSelectedLiveCaptionsTranslation(this) == 2) {
//            if (isTranslated) {
//                if (currentTime - glassesTranslatedTranscriptLastSentTime >= GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY) {
//                    showTranscriptsToUser(transcript, true, false);
//                    glassesTranslatedTranscriptLastSentTime = currentTime;
//                } else {
//                    glassesTranscriptDebounceRunnable = () -> {
//                        showTranscriptsToUser(transcript, true, false);
//                        glassesTranslatedTranscriptLastSentTime = System.currentTimeMillis();
//                    };
//                    glassesTranscriptDebounceHandler.postDelayed(glassesTranscriptDebounceRunnable, GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY);
//                }
//            } else {
//                if (currentTime - glassesTranscriptLastSentTime >= GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY) {
//                    showTranscriptsToUser(transcript, false, false);
//                    glassesTranscriptLastSentTime = currentTime;
//                } else {
//                    glassesTranscriptDebounceRunnable = () -> {
//                        showTranscriptsToUser(transcript, false, false);
//                        glassesTranscriptLastSentTime = System.currentTimeMillis();
//                    };
//                    glassesTranscriptDebounceHandler.postDelayed(glassesTranscriptDebounceRunnable, GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY);
//                }
//            }
//        } else {
//            if (currentTime - glassesTranscriptLastSentTime >= GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY) {
//                showTranscriptsToUser(transcript, false, false);
//                glassesTranscriptLastSentTime = currentTime;
//            } else {
//                glassesTranscriptDebounceRunnable = () -> {
//                    showTranscriptsToUser(transcript, false, false);
//                    glassesTranscriptLastSentTime = System.currentTimeMillis();
//                };
//                glassesTranscriptDebounceHandler.postDelayed(glassesTranscriptDebounceRunnable, GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY);
//            }
//        }
//    }

//    private void showTranscriptsToUser(final String transcript, final boolean isTranslated, final boolean isFinal) {
//        String processed_transcript = transcript;
//
//        if (!isTranslated && AugmentosSmartGlassesService.getChosenTranscribeLanguage(this).equals("Chinese (Pinyin)") ||
//            isTranslated && (
//                getChosenSourceLanguage(this).equals("Chinese (Pinyin)") ||
//                getChosenTargetLanguage(this).equals("Chinese (Pinyin)") && AugmentosSmartGlassesService.getChosenTranscribeLanguage(this).equals(getChosenSourceLanguage(this)))
//        ) {
//            if(segmenterLoaded) {
//                processed_transcript = convertToPinyin(transcript);
//            } else if (!segmenterLoading) {
//                new Thread(this::loadSegmenter).start();
//                hasUserBeenNotified = true;
//                if (smartGlassesService != null)
//                    smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendTextWall("Loading Pinyin Converter, Please Wait..."), true, false, false));
//            } else if (!hasUserBeenNotified) {  //tell user we are loading the pinyin converter
//                hasUserBeenNotified = true;
//                if (smartGlassesService != null)
//                    smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendTextWall("Loading Pinyin Converter, Please Wait..."), true, false, false));
//            }
//        }
//
//        if (AugmentosSmartGlassesService.getSelectedLiveCaptionsTranslation(this) == 2) sendTextWallLiveTranslationLiveCaption(processed_transcript, isTranslated, isFinal);
//        else sendTextWallLiveCaptionLL(processed_transcript, "", isFinal);
//    }

    private void loadSegmenter() {
        segmenterLoading = true;
        final JiebaSegmenter segmenter = new JiebaSegmenter();
        segmenterLoaded = true;
        segmenterLoading = false;
//        displayQueue.addTask(new DisplayQueue.Task(() -> sendTextWall("Pinyin Converter Loaded!"), true, false));
    }

    private String convertToPinyin(final String chineseText) {
        final JiebaSegmenter segmenter = new JiebaSegmenter();

        final List<SegToken> tokens = segmenter.process(chineseText, JiebaSegmenter.SegMode.SEARCH);

        final HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);

        StringBuilder pinyinText = new StringBuilder();

        for (SegToken token : tokens) {
            StringBuilder tokenPinyin = new StringBuilder();
            for (char character : token.word.toCharArray()) {
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(character, format);
                    if (pinyinArray != null) {
                        // Use the first Pinyin representation if there are multiple
                        tokenPinyin.append(pinyinArray[0]);
                    } else {
                        // If character is not a Chinese character, append it as is
                        tokenPinyin.append(character);
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    e.printStackTrace();
                }
            }
            // Ensure the token is concatenated with a space only if it's not empty
            if (tokenPinyin.length() > 0) {
                pinyinText.append(tokenPinyin.toString()).append(" ");
            }
        }

        // Replace multiple spaces with a single space, but preserve newlines
        String cleanText = pinyinText.toString().trim().replaceAll("[ \\t]+", " ");  // Replace spaces and tabs only

        return cleanText;
    }

    private long lastSentTime = 0;
    private final long DEBOUNCE_DELAY = 333; // in milliseconds
    private void debounceAndSendTranscript(String transcript, boolean isFinal) {
        debounceHandler.removeCallbacks(debounceRunnable);
        long currentTime = System.currentTimeMillis();
        if (isFinal) {
            sendTranscriptRequest(transcript, isFinal);
        } else { //if intermediate
            if (currentTime - lastSentTime >= DEBOUNCE_DELAY) {
                sendTranscriptRequest(transcript, isFinal);
                lastSentTime = currentTime;
            } else {
                debounceRunnable = () -> {
                    sendTranscriptRequest(transcript, isFinal);
                    lastSentTime = System.currentTimeMillis();
                };
                debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_DELAY);
            }
        }
    }

    public void sendTranscriptRequest(String query, boolean isFinal){
        updateLastDataSentTime();
        try{
            JSONObject jsonQuery = new JSONObject();
            jsonQuery.put("text", query);
            jsonQuery.put("transcribe_language", AugmentosSmartGlassesService.getChosenTranscribeLanguage(this));
            jsonQuery.put("isFinal", isFinal);
            jsonQuery.put("timestamp", System.currentTimeMillis() / 1000);
            backendServerComms.restRequest(LLM_QUERY_ENDPOINT, jsonQuery, new VolleyJsonCallback(){
                @Override
                public void onSuccess(JSONObject result){
                    try {
                        parseSendTranscriptResult(result);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
                @Override
                public void onFailure(int code){
                    Log.d(TAG, "SOME FAILURE HAPPENED (sendChatRequest)");
                }

            });
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void requestUiPoll(){
        try{
            JSONObject jsonQuery = new JSONObject();
            jsonQuery.put("deviceId", deviceId);
            backendServerComms.restRequest(UI_POLL_ENDPOINT, jsonQuery, new VolleyJsonCallback(){
                @Override
                public void onSuccess(JSONObject result) throws JSONException {
                    parseAugmentosResults(result);
                }
                @Override
                public void onFailure(int code){
                    Log.d(TAG, "SOME FAILURE HAPPENED (requestUiPoll)");
                    if (code == 401){
                        EventBus.getDefault().post(new GoogleAuthFailedEvent("401 AUTH ERROR (requestUiPoll)"));
                    }
                }
            });
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    private void parseAugmentosResults(JSONObject jsonResponse) throws JSONException {
        JSONArray rootArray = jsonResponse.getJSONArray(notificationFilterKey);

        if (rootArray.length() == 0) {
            Log.d(TAG, "No data found in response");
            return;
        }

        JSONObject firstEntry = rootArray.getJSONObject(0);

        JSONArray notifications = firstEntry.getJSONArray("notification_data");
        Log.d(TAG, "Got notifications: " + notifications.toString());

        List<JSONObject> sortedNotifications = new ArrayList<>();
        for (int i = 0; i < notifications.length(); i++) {
            sortedNotifications.add(notifications.getJSONObject(i));
        }

        Collections.sort(sortedNotifications, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                try {
                    return Integer.compare(a.getInt("rank"), b.getInt("rank"));
                } catch (JSONException e) {
                    // If a rank is missing or unparsable, treat as equal
                    return 0;
                }
            }
        });

        notificationList.clear();
//        Log.d(TAG, "Got notifications: " + sortedNotifications.toString());

        for (int i = 0; i < sortedNotifications.size(); i++) {
            JSONObject notification = sortedNotifications.get(i);
            String summary = notification.getString("summary");
            notificationList.add(summary);
        }
    }

    public void requestLocation(){
//        Log.d(TAG, "running request locatoin");
        try{
            // Get location data as JSONObject
            double latitude = locationSystem.lat;
            double longitude = locationSystem.lng;

            // TODO: Filter here... is it meaningfully different?
            if(latitude == 0 && longitude == 0) return;

//            Log.d(TAG, "Got a GOOD location!");

            JSONObject jsonQuery = new JSONObject();
            jsonQuery.put("deviceId", deviceId);
            jsonQuery.put("lat", latitude);
            jsonQuery.put("lng", longitude);

            backendServerComms.restRequest(GEOLOCATION_STREAM_ENDPOINT, jsonQuery, new VolleyJsonCallback(){
                @Override
                public void onSuccess(JSONObject result){
                    Log.d(TAG, "Request sent Successfully: " + result.toString());
                }
                @Override
                public void onFailure(int code){
                    Log.d(TAG, "SOME FAILURE HAPPENED (requestLocation)");
                    if (code == 401){
                        EventBus.getDefault().post(new GoogleAuthFailedEvent("401 AUTH ERROR (requestLocation)"));
                    }
                }
            });
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void parseSendTranscriptResult(JSONObject response) throws JSONException {
//        Log.d(TAG, "Got result from server: " + response.toString());
        String message = response.getString("message");
        //DEV
        return;
//        if (!message.equals("")) {
//            responses.add(message);
//            sendUiUpdateSingle(message);
//            speakTTS(message);
//        }
    }

//    public String[] calculateLLStringFormatted(JSONArray jsonArray){
//        //clear canvas if needed
//        if (!clearedScreenYet){
//            sendHomeScreen();
//            clearedScreenYet = true;
//        }
//
//        // Assuming jsonArray is your existing JSONArray object
//        int max_rows_allowed = 4;
//
//        String[] inWords = new String[jsonArray.length()];
//        String[] inWordsTranslations = new String[jsonArray.length()];
//        String[] llResults = new String[max_rows_allowed];
//        String enSpace = "\u2002"; // Using en space for padding
//
//        int minSpaces = 2;
//        for (int i = 0; i < jsonArray.length() && i < max_rows_allowed; i++) {
//            try {
//                JSONObject obj = jsonArray.getJSONObject(i);
//                inWords[i] = obj.getString("in_word");
//                inWordsTranslations[i] = obj.getString("in_word_translation");
//                int max_len = Math.max(inWords[i].length(), inWordsTranslations[i].length());
////                llResults[i] = inWords[i] + enSpace.repeat(Math.max(0, max_len - inWords[i].length()) + minSpaces) + "⟶" + enSpace.repeat(Math.max(0, max_len - inWordsTranslations[i].length()) + minSpaces) + inWordsTranslations[i];
//                llResults[i] = inWords[i] + enSpace.repeat(minSpaces) + "⟶" + enSpace.repeat(minSpaces) + inWordsTranslations[i];
//            } catch (JSONException e){
//                e.printStackTrace();
//            }
//        }
//
//        return llResults;
//
////        String enSpace = "\u2002"; // Using en space for padding
////        String llResult = "";
////        for (int i = 0; i < inWords.length; i++) {
////            String inWord = inWords[i];
////            String translation = inWordsTranslations[i];
////            llResult += inWord + enSpace.repeat(3) + "->"+ enSpace.repeat(3) + translation + "\n\n";
////        }
//
////        StringBuilder topLine = new StringBuilder();
////        StringBuilder bottomLine = new StringBuilder();
////
////        // Calculate initial padding for the first word based on the bottom line's first word
////        int initialPaddingLength = (inWordsTranslations[0].length() - inWords[0].length()) / 2;
////        if (initialPaddingLength > 0) {
////            topLine.append(String.valueOf(enSpace).repeat(initialPaddingLength));
////        } else {
////            initialPaddingLength = 0; // Ensure it's not negative for subsequent calculations
////        }
////
////        for (int i = 0; i < inWords.length; i++) {
////            String inWord = inWords[i];
////            String translation = inWordsTranslations[i];
////
////            topLine.append(inWord);
////            bottomLine.append(translation);
////
////            if (i < inWords.length - 1) {
////                // Calculate the minimum necessary space to add based on the length of the next words in both lines
////                int nextTopWordLength = inWords[i + 1].length();
////                int nextBottomWordLength = inWordsTranslations[i + 1].length();
////                int currentTopWordLength = inWord.length();
////                int currentBottomWordLength = translation.length();
////
////                // Calculate additional space needed for alignment
////                int additionalSpaceTop = nextTopWordLength - currentTopWordLength;
////                int additionalSpaceBottom = nextBottomWordLength - currentBottomWordLength;
////
////                // Ensure there's a minimum spacing for readability, reduce this as needed
////                int minSpace = 2; // Reduced minimum space for closer alignment
////                int spacesToAddTop = Math.max(additionalSpaceTop, minSpace);
////                int spacesToAddBottom = Math.max(additionalSpaceBottom, minSpace);
////
////                // Append the calculated spaces to each line
////                topLine.append(String.valueOf(enSpace).repeat(spacesToAddTop));
////                bottomLine.append(String.valueOf(enSpace).repeat(spacesToAddBottom));
////            }
////        }
////
////
////        // Adjust for the initial padding by ensuring the bottom line starts directly under the top line's first word
////        if (initialPaddingLength > 0) {
////            String initialPaddingForBottom = String.valueOf(enSpace).repeat(initialPaddingLength);
////            bottomLine = new StringBuilder(initialPaddingForBottom).append(bottomLine.toString());
////        }
//
////        String llResult = topLine.toString() + "\n" + bottomLine.toString();
//    }

//    public String[] calculateLLStringFormatted(LinkedList<DefinedWord> definedWords) {
//        int max_rows_allowed = 4;
//        String[] llResults = new String[Math.min(max_rows_allowed, definedWords.size())];
//        String enSpace = "\u2002"; // Using en space for padding
//
//        int minSpaces = 2;
//        int index = 0;
//        for (DefinedWord word : definedWords) {
//            if (index >= max_rows_allowed) break;
//            llResults[index] = word.inWord + enSpace.repeat(minSpaces) + "⟶" + enSpace.repeat(minSpaces) + word.inWordTranslation;
//            index++;
//        }
//
//        return llResults;
//    }

    public String[] calculateLLCombineResponseFormatted(LinkedList<LLCombineResponse> llCombineResponses) {
        int max_rows_allowed = 4;

        String[] llCombineResults = new String[Math.min(max_rows_allowed, llCombineResponses.size())];

        int minSpaces = 2;
        int index = 0;
        String enSpace = "\u2002";

        for (LLCombineResponse llCombineResponse : llCombineResponses) {
            if (index >= max_rows_allowed) break;
//            Log.d(TAG, llCombineResponse.toString());
            if(llCombineResponse.inWord!=null && llCombineResponse.inWordTranslation!=null){
                llCombineResults[index] = llCombineResponse.inWord + enSpace.repeat(minSpaces) + "⟶" + enSpace.repeat(minSpaces) + llCombineResponse.inWordTranslation;
            } else if (llCombineResponse.inUpgrade != null && llCombineResponse.inUpgradeMeaning!= null) {
                llCombineResults[index] = "⬆ " + llCombineResponse.inUpgrade + enSpace.repeat(minSpaces) + "-" + enSpace.repeat(minSpaces) + llCombineResponse.inUpgradeMeaning;
            }
            index++;
        }

        return llCombineResults;
    }

    public String[] calculateAdhdStmbStringFormatted(LinkedList<STMBSummary> summaries) {
        int max_rows_allowed = 4;
        String[] stmbResults = new String[Math.min(max_rows_allowed, summaries.size())];

        int minSpaces = 2;
        int index = 0;
        for (STMBSummary summary : summaries) {
            if (index >= max_rows_allowed) break;
            stmbResults[index] = summary.summary;
            index++;
        }

        return stmbResults;
    }

//    public String[] calculateLLUpgradeResponseFormatted(LinkedList<LLUpgradeResponse> llUpgradeResponses) {
//        int max_rows_allowed = 1;
//        String[] llUpgradeResults = new String[Math.min(max_rows_allowed, llUpgradeResponses.size())];
//
//        int minSpaces = 0;
//        int index = 0;
//        for (LLUpgradeResponse llUpgradeResponse : llUpgradeResponses) {
//            if (index >= max_rows_allowed) break;
//            llUpgradeResults[index] = "Upgrade: " + llUpgradeResponse.inUpgrade + " ( " + llUpgradeResponse.inUpgradeMeaning + " ) ";
//            index++;
//        }
//
//        return llUpgradeResults;
//    }

    public String[] calculateLLContextConvoResponseFormatted(LinkedList<ContextConvoResponse> contextConvoResponses) {
        int max_rows_allowed = 4;

        if (!clearedScreenYet) {
            if (smartGlassesService != null)
                smartGlassesService.sendHomeScreen();
            clearedScreenYet = true;
        }

        String[] llResults = new String[Math.min(max_rows_allowed, contextConvoResponses.size())];

        int index = 0;
        for (ContextConvoResponse contextConvoResponse: contextConvoResponses) {
            if (index >= max_rows_allowed) break;
            llResults[index] = contextConvoResponse.response;
            index++;
        }

        return llResults;
    }

    private String processString(String str) {
        if (str.length() > charsPerTranscript) {
            int startIndex = str.length() - charsPerTranscript;

            // Move startIndex forward to the next space to avoid splitting a word
            while (startIndex < str.length() && str.charAt(startIndex) != ' ') {
                startIndex++;
            }

            // If a space is found, start from the next character after the space
            if (startIndex < str.length()) {
                str = str.substring(startIndex + 1);
            } else {
                // If no space is found, it means the substring is a single long word
                // In this case, start from the original startIndex
                str = str.substring(str.length() - charsPerTranscript);
            }
        }

        int len = str.length();
        if (len > 2 * charsPerTranscript / 3) {
            // Insert newlines to split into three lines
            int index1 = len / 3;
            int index2 = 2 * len / 3;

            // Find the last space before index1
            while (index1 > 0 && str.charAt(index1) != ' ') {
                index1--;
            }
            // Insert first newline
            if (index1 > 0) {
                str = str.substring(0, index1) + "\n" + str.substring(index1 + 1);
                index2 += 1; // Adjust index2 after insertion
            }

            // Find the last space before index2
            while (index2 > index1 && str.charAt(index2) != ' ') {
                index2--;
            }
            // Insert second newline
            if (index2 > index1) {
                str = str.substring(0, index2) + "\n" + str.substring(index2 + 1);
            }
        } else if (len > charsPerTranscript / 3) {
            // Insert newline to split into two lines
            int index = len / 2;
            while (index > 0 && str.charAt(index) != ' ') {
                index--;
            }
            if (index > 0) {
                str = str.substring(0, index) + "\n" + str.substring(index + 1) + "\n";
            }
        } else {
            str = str + "\n\n";
        }

        return str;
    }

    private String processHanziString(String str) {
        if (str.length() > charsPerHanziTranscript) {
            str = str.substring(str.length() - charsPerHanziTranscript);
        }

        int len = str.length();
        if (len > 2 * charsPerHanziTranscript / 3) {
            // Split into three lines without searching for spaces
            int index1 = len / 3;
            int index2 = 2 * len / 3;

            // Insert first newline after index1
            str = str.substring(0, index1) + "\n" + str.substring(index1);

            // Adjust index2 after the first insertion
            index2 += 1;

            // Insert second newline after index2
            str = str.substring(0, index2) + "\n" + str.substring(index2);

        } else if (len > charsPerHanziTranscript / 3) {
            // Split into two lines
            int index = len / 2;

            // Insert newline at the middle
            str = str.substring(0, index) + "\n" + str.substring(index) + "\n";
        } else {
            // If string is shorter, add two newlines at the end
            str = str + "\n\n";
        }

        return str;
    }

    public void sendTextWallLiveCaptionLL(final String newLiveCaption, final String llString, final boolean isFinal) {
//        String textBubble = "\uD83D\uDDE8";
//        if (!llString.isEmpty()) {
//            llCurrentString = llString;
//        } else if (!newLiveCaption.isEmpty()) {
//            if (AugmentosSmartGlassesService.getChosenTranscribeLanguage(this).equals("Chinese (Hanzi)") ||
//                    AugmentosSmartGlassesService.getChosenTranscribeLanguage(this).equals("Chinese (Hanzi)") && !segmenterLoaded) {
//                currentLiveCaption = processHanziString(finalLiveCaption + " " + newLiveCaption);
//            } else {
//                currentLiveCaption = processString(finalLiveCaption + " " + newLiveCaption);
//            }
//            if (isFinal) {
//                finalLiveCaption += " " + newLiveCaption;
//            }
//
//            // Limit the length of the final live caption, in case it gets too long
//            if (finalLiveCaption.length() > 5000) {
//                finalLiveCaption = finalLiveCaption.substring(finalLiveCaption.length() - 5000);
//            }
//        }
//
//        final String finalLiveCaption = textBubble + currentLiveCaption;
//        if (smartGlassesService != null)
//            smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendDoubleTextWall(llCurrentString, finalLiveCaption), true, false, true));
    }

    public void sendTextWallLiveTranslationLiveCaption(final String newText, final boolean isTranslated, final boolean isFinal) {
//        if (!newText.isEmpty()) {
//            if (isTranslated) {
//                if (getChosenSourceLanguage(this).equals("Chinese (Hanzi)") ||
//                        getChosenSourceLanguage(this).equals("Chinese (Pinyin)") && !segmenterLoaded) {
//                    translationText = processHanziString(finalTranslationText + " " + newText);
//                } else {
//                    translationText = processString(finalTranslationText + " " + newText);
//                }
//
//                if (isFinal) {
//                    finalTranslationText += " " + newText;
//                }
//
//                // Limit the length of the final translation text
//                if (finalTranslationText.length() > 5000) {
//                    finalTranslationText = finalTranslationText.substring(finalTranslationText.length() - 5000);
//                }
//            } else {
//                if (AugmentosSmartGlassesService.getChosenTranscribeLanguage(this).equals("Chinese (Hanzi)") ||
//                        AugmentosSmartGlassesService.getChosenTranscribeLanguage(this).equals("Chinese (Pinyin)") && !segmenterLoaded) {
//                    liveCaptionText = processHanziString(finalLiveCaptionText + " " + newText);
//                } else {
//                    liveCaptionText = processString(finalLiveCaptionText + " " + newText);
//                }
//
//                if (isFinal) {
//                    finalLiveCaptionText += " " + newText;
//                }
//
//                // Limit the length of the final live caption text
//                if (finalLiveCaptionText.length() > 5000) {
//                    finalLiveCaptionText = finalLiveCaptionText.substring(finalLiveCaptionText.length() - 5000);
//                }
//            }
//        }
//
//        String textBubble = "\uD83D\uDDE8";
//
//        final String finalLiveTranslationDisplayText;
//        if (!translationText.isEmpty()) {
//            finalLiveTranslationDisplayText = textBubble + translationText + "\n";
//        } else {
//            finalLiveTranslationDisplayText = "\n\n\n";
//        }
//
//        final String finalLiveCaptionDisplayText;
//        if (!liveCaptionText.isEmpty()) {
//            finalLiveCaptionDisplayText = textBubble + liveCaptionText;
//        } else {
//            finalLiveCaptionDisplayText = "\n\n\n";
//        }
//
//        if (smartGlassesService != null)
//            smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendDoubleTextWall(finalLiveTranslationDisplayText, finalLiveCaptionDisplayText), true, false, true));
    }

    public void parseConvoscopeResults(JSONObject response) throws JSONException {
        if (Objects.equals(getCurrentMode(this), "Language Learning") && AugmentosSmartGlassesService.getSelectedLiveCaptionsTranslation(this) == 2) return;
//        Log.d(TAG, "GOT CSE RESULT: " + response.toString());
        String imgKey = "image_url";
        String mapImgKey = "map_image_path";

        boolean isLiveCaptionsChecked = SmartGlassesAndroidService.getSelectedLiveCaptionsTranslation(this) != 0;

        //explicit queries
        JSONArray explicitAgentQueries = response.has(explicitAgentQueriesKey) ? response.getJSONArray(explicitAgentQueriesKey) : new JSONArray();

        JSONArray explicitAgentResults = response.has(explicitAgentResultsKey) ? response.getJSONArray(explicitAgentResultsKey) : new JSONArray();

        //displayResults
        JSONArray displayRequests = response.has(displayRequestsKey) ? response.getJSONArray(displayRequestsKey) : new JSONArray();

//        //proactive agents
//        JSONArray proactiveAgentResults = response.has(proactiveAgentResultsKey) ? response.getJSONArray(proactiveAgentResultsKey) : new JSONArray();
//        JSONArray entityDefinitions = response.has(entityDefinitionsKey) ? response.getJSONArray(entityDefinitionsKey) : new JSONArray();
//
//        //adhd STMB results
//        JSONArray adhdStmbResults = response.has(adhdStmbAgentKey) ? response.getJSONArray(adhdStmbAgentKey) : new JSONArray();
//        if (adhdStmbResults.length() != 0) {
//            Log.d(TAG, "ADHD RESULTS: ");
//            Log.d(TAG, adhdStmbResults.toString());
//
//            if (!clearedScreenYet) {
//                smartGlassesService.sendHomeScreen();
//                clearedScreenYet = true;
//            }
//
//            updateAdhdSummaries(adhdStmbResults);
//            String dynamicSummary = adhdStmbResults.getJSONObject(0).getString("summary");
//            String [] adhdResults = calculateAdhdStmbStringFormatted(getAdhdStmbSummaries());
//            smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendRowsCard(adhdResults), false, true, false));
//            sendUiUpdateSingle(dynamicSummary);
//            responsesBuffer.add(dynamicSummary);
//        }
//
//        JSONArray languageLearningResults = response.has(languageLearningKey) ? response.getJSONArray(languageLearningKey) : new JSONArray();
//        JSONArray llWordSuggestUpgradeResults = response.has(llWordSuggestUpgradeKey) ? response.getJSONArray(llWordSuggestUpgradeKey) : new JSONArray();
//        updateCombineResponse(languageLearningResults, llWordSuggestUpgradeResults);
//        if (Objects.equals(getCurrentMode(this), "Language Learning") && (languageLearningResults.length() != 0 || llWordSuggestUpgradeResults.length() != 0)) {
//            String [] llCombineResults = calculateLLCombineResponseFormatted(getLLCombineResponse());
//            String newLineSeparator = isLiveCaptionsChecked ? "\n" : "\n\n";
//            if (smartGlassesService.getConnectedDeviceModelOs() != SmartGlassesOperatingSystem.AUDIO_WEARABLE_GLASSES) {
//                String textWallString = Arrays.stream(llCombineResults)
//                        .reduce((a, b) -> b + newLineSeparator + a)
//                        .orElse("");
//                if (isLiveCaptionsChecked) sendTextWallLiveCaptionLL("", textWallString, false);
//                else {
//                    smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendTextWall(textWallString), true, true, true));
//                }
//            }
////            Log.d(TAG, "ll combine results"+ llCombineResults.toString());
//            sendUiUpdateSingle(String.join("\n", llCombineResults));
//            responsesBuffer.add(String.join("\n", llCombineResults));
//        }

//        JSONArray llContextConvoResults = response.has(llContextConvoKey) ? response.getJSONArray(llContextConvoKey) : new JSONArray();
//
//        updateContextConvoResponses(llContextConvoResults); //sliding buffer, time managed context convo card
//        String[] llContextConvoResponses;
//
//        if (llContextConvoResults.length() != 0) {
//            llContextConvoResponses = calculateLLContextConvoResponseFormatted(getContextConvoResponses());
//            if (smartGlassesService.getConnectedDeviceModelOs() != SmartGlassesOperatingSystem.AUDIO_WEARABLE_GLASSES) {
//                String textWallString = Arrays.stream(llContextConvoResponses)
//                        .reduce((a, b) -> b + "\n\n" + a)
//                        .orElse("");
//                //sendRowsCard(llContextConvoResponses);
//
//                if (isLiveCaptionsChecked) sendTextWallLiveCaptionLL("", textWallString, false);
//                else {
//                    smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendTextWall(textWallString), false, true, false));
//                }
//            }
//            List<String> list = Arrays.stream(Arrays.copyOfRange(llContextConvoResponses, 0, llContextConvoResults.length())).filter(Objects::nonNull).collect(Collectors.toList());
//            Collections.reverse(list);
//            sendUiUpdateSingle(String.join("\n", list));
//            responsesBuffer.add(String.join("\n", list));
//
//            try {
//                JSONObject llContextConvoResult = llContextConvoResults.getJSONObject(0);
////                Log.d(TAG, llContextConvoResult.toString());
//                JSONObject toTTS = llContextConvoResult.getJSONObject("to_tts");
//                String text = toTTS.getString("text");
//                String language = toTTS.getString("language");
////                Log.d(TAG, "Text: " + text + ", Language: " + language);
//                //sendTextToSpeech(text, language);
//                smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendTextToSpeech(text, language), false, false, false));
//            } catch (JSONException e) {
//                e.printStackTrace();
//            }
//        }

        // displayResults
        for (int i = 0; i < displayRequests.length(); i++) {
            try {
                JSONObject obj = displayRequests.getJSONObject(i);
                JSONObject req = obj.getJSONObject("data");
                JSONObject content = req.getJSONObject("content");
                String layout = req.getString("layout");
                String title;
                String body;
                switch (layout){
                    case "REFERENCE_CARD":
                        title = content.getString("title");
                        body = content.getString("body");
                        queueOutput(title + ": " + body);
                        smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendReferenceCard(title, body), -1);
                        break;
                    case "TEXT_WALL":
                    case "TEXT_LINE":
                        body = content.getString("body");
                        queueOutput(body);
                        smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendTextWall(body), -1);
                        break;
                    case "DOUBLE_TEXT_WALL":
                        String bodyTop = content.getString("bodyTop");
                        String bodyBottom = content.getString("bodyBottom");
                        queueOutput(bodyTop + "\n\n" + bodyBottom);
                        smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendDoubleTextWall(bodyTop, bodyBottom), -1);
                        break;
                    case "ROWS_CARD":
                        JSONArray rowsArray = content.getJSONArray("rows");
                        String[] stringsArray = new String[rowsArray.length()];
                        for (int k = 0; k < rowsArray.length(); k++)
                            stringsArray[k] = rowsArray.getString(k);
                        queueOutput(String.join("\n", stringsArray));
                        smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendRowsCard(stringsArray), -1);
                        break;
                    default:
                        Log.d(TAG, "SOME ISSUE");
                }
            }
            catch (JSONException e){
                e.printStackTrace();
            }
        }

//        // entityDefinitions
//        for (int i = 0; i < entityDefinitions.length(); i++) {
//            try {
//                JSONObject obj = entityDefinitions.getJSONObject(i);
//                String name = obj.getString("name");
//                String body = obj.getString("summary");
//                if (smartGlassesService != null)
//                    smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendReferenceCard("" + name + "", body), false, false, false));
//                queueOutput(name + ": " + body);
//            } catch (JSONException e){
//                e.printStackTrace();
//            }
//        }

        long wakeWordTime = response.has(wakeWordTimeKey) ? response.getLong(wakeWordTimeKey) : -1;

        // Wake word indicator
        if (wakeWordTime != -1 && wakeWordTime != previousWakeWordTime){
            previousWakeWordTime = wakeWordTime;
            String body = "Listening... ";
            if (smartGlassesService != null)
                smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendReferenceCard(glassesCardTitle, body), -1);
            queueOutput(body);
        }

        //go through explicit agent queries and add to resultsToDisplayList
        // "Processing query: " indicator
        for (int i = 0; i < explicitAgentQueries.length(); i++){
            try {
                JSONObject obj = explicitAgentQueries.getJSONObject(i);
                String title = "Processing Query";
                String body = "\"" + obj.getString("query") + "\"";
                if (smartGlassesService != null)
                    smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendReferenceCard(title, body), -1);
                queueOutput(body);
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        //go through explicit agent results and add to resultsToDisplayList
        // Show Wake Word Query
        for (int i = 0; i < explicitAgentResults.length(); i++){
            Log.d(TAG, "explicitAgentResults.toString() *************");
            Log.d(TAG, explicitAgentResults.toString());
            try {
                JSONObject obj = explicitAgentResults.getJSONObject(i);
                //String body = "Response: " + obj.getString("insight");
                String body = obj.getString("insight");
                if (smartGlassesService != null)
                    smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendReferenceCard(glassesCardTitle, body), -1);
                queueOutput(body);
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

//        //go through proactive agent results and add to resultsToDisplayList
//        for (int i = 0; i < proactiveAgentResults.length(); i++){
//            try {
//                JSONObject obj = proactiveAgentResults.getJSONObject(i);
//                String name = obj.getString("agent_name") + " says";
//                String body = obj.getString("agent_insight");
//                if (smartGlassesService != null)
//                    smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendReferenceCard(name, body), false, false, false));
//                queueOutput(name + ": " + body);
//            } catch (JSONException e){
//                e.printStackTrace();
//            }
//        }

        //see if we should update user settings
        boolean shouldUpdateSettingsResult = response.has(shouldUpdateSettingsKey) && response.getBoolean(shouldUpdateSettingsKey);
        if (shouldUpdateSettingsResult){
            Log.d(TAG, "Running get settings because shouldUpdateSettings true");
            getSettings();
        }
    }

    public void parseLocationResults(JSONObject response) throws JSONException {
        Log.d(TAG, "GOT LOCATION RESULT: " + response.toString());
        // ll context convo
    }

    // Display things to the phone screen
    public void queueOutput(String item){
        responsesBuffer.add(item);
        sendUiUpdateSingle(item);
    }

    public void speakTTS(String toSpeak){
        if (smartGlassesService != null)
            smartGlassesService.sendTextLine(toSpeak);
    }

    public void sendUiUpdateFull(){
        Intent intent = new Intent();
        intent.setAction(AugmentosUi.UI_UPDATE_FULL);
        intent.putStringArrayListExtra(AugmentosUi.CONVOSCOPE_MESSAGE_STRING, responsesBuffer);
        intent.putStringArrayListExtra(AugmentosUi.TRANSCRIPTS_MESSAGE_STRING, transcriptsBuffer);
        sendBroadcast(intent);
    }

    public void sendUiUpdateSingle(String message) {
        Intent intent = new Intent();
        intent.setAction(AugmentosUi.UI_UPDATE_SINGLE);
        intent.putExtra(AugmentosUi.CONVOSCOPE_MESSAGE_STRING, message);
        sendBroadcast(intent);
    }

    public void sendFinalTranscriptToActivity(String transcript){
        Intent intent = new Intent();
        intent.setAction(AugmentosUi.UI_UPDATE_FINAL_TRANSCRIPT);
        intent.putExtra(AugmentosUi.FINAL_TRANSCRIPT, transcript);
        sendBroadcast(intent);
    }

    public void buttonDownEvent(int buttonNumber, boolean downUp){ //downUp if true if down, false if up
        if (!downUp){
            return;
        }

        try{
            JSONObject jsonQuery = new JSONObject();
            jsonQuery.put("button_num", buttonNumber);
            jsonQuery.put("button_activity", downUp);
            jsonQuery.put("timestamp", System.currentTimeMillis() / 1000);
            backendServerComms.restRequest(BUTTON_EVENT_ENDPOINT, jsonQuery, new VolleyJsonCallback(){
                @Override
                public void onSuccess(JSONObject result){
                    try {
                        Log.d(TAG, "GOT BUTTON RESULT: " + result.toString());
                        String query_answer = result.getString("message");
                        sendUiUpdateSingle(query_answer);
                        speakTTS(query_answer);
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
                @Override
                public void onFailure(int code){
                    Log.d(TAG, "SOME FAILURE HAPPENED (buttonDownEvent)");
                }

            });
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void setupAuthTokenMonitor(){
        idTokenListener = new FirebaseAuth.IdTokenListener() {
            @Override
            public void onIdTokenChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    user.getIdToken(true).addOnCompleteListener(new OnCompleteListener<GetTokenResult>() {
                        @Override
                        public void onComplete(@NonNull Task<GetTokenResult> task) {
                            if (task.isSuccessful()) {
                                String idToken = task.getResult().getToken();
                                Log.d(TAG, "GOT ONIDTOKENCHANGED Auth Token: " + idToken);
                                authToken = idToken;
                                PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                                        .edit()
                                        .putString("auth_token", idToken)
                                        .apply();
                            } else {
                                Log.d(TAG, "Task failure in setAuthToken");
                                EventBus.getDefault().post(new GoogleAuthFailedEvent("#1 ERROR IN (setupAuthTokenMonitor)"));
                            }
                        }
                    });
                }
            }
        };
    }

    public void manualSetAuthToken() {
        Log.d(TAG, "GETTING AUTH TOKEN");
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            user.getIdToken(true)
                    .addOnCompleteListener(new OnCompleteListener<GetTokenResult>() {
                        public void onComplete(@NonNull Task<GetTokenResult> task) {
                            if (task.isSuccessful()) {
                                String idToken = task.getResult().getToken();
                                Log.d(TAG, "GOT dat MANUAL Auth Token: " + idToken);
                                authToken = idToken;
                                PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                                        .edit()
                                        .putString("auth_token", idToken)
                                        .apply();
                            } else {
                                Log.d(TAG, "Task failure in setAuthToken");
                                EventBus.getDefault().post(new GoogleAuthFailedEvent("#1 ERROR IN (SETAUTHTOKEN)"));
                            }
                        }
                    });
        } else {
            // not logged in, must log in
            Log.d(TAG, "User is null in setAuthToken");
            EventBus.getDefault().post(new GoogleAuthFailedEvent("#2 ERROR IN (SETAUTHTOKEN) (USER IS NULL)"));
        }
    }

    public static void saveChosenTargetLanguage(Context context, String targetLanguageString) {
        Log.d("CONVOSCOPE", "SAVING TARGET LANGUAGE: " + targetLanguageString);
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getResources().getString(R.string.SHARED_PREF_TARGET_LANGUAGE), targetLanguageString)
                .apply();
    }

//    public Boolean isVocabularyUpgradeEnabled(Context context) {
//        return PreferenceManager.getDefaultSharedPreferences(context)
//                .getBoolean(context.getResources().getString(R.string.SHARED_PREF_VOCABULARY_UPGRADE), false);
//    }

    public static Boolean isVocabularyUpgradeEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getResources().getString(R.string.SHARED_PREF_VOCABULARY_UPGRADE), true);
    }

    public static void setVocabularyUpgradeEnabled(Context context, boolean isEnabled) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(context.getResources().getString(R.string.SHARED_PREF_VOCABULARY_UPGRADE), isEnabled)
                .apply();
    }

    public static void saveChosenSourceLanguage(Context context, String sourceLanguageString) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getResources().getString(R.string.SHARED_PREF_SOURCE_LANGUAGE), sourceLanguageString)
                .apply();
    }

    public static String getChosenTargetLanguage(Context context) {
        String targetLanguageString = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.SHARED_PREF_TARGET_LANGUAGE), "");
        if (targetLanguageString.equals("")){
            saveChosenTargetLanguage(context, "Russian");
            targetLanguageString = "Russian";
        }
        return targetLanguageString;
    }

    public static String getChosenSourceLanguage(Context context) {
        String sourceLanguageString = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.SHARED_PREF_SOURCE_LANGUAGE), "");
        if (sourceLanguageString.equals("")){
            saveChosenSourceLanguage(context, "English");
            sourceLanguageString = "English";
        }
        return sourceLanguageString;
    }

//    public void changeMode(String currentModeString){
//        if (currentModeString.equals("Proactive Agents")){
//            features = new String[]{explicitAgent, proactiveAgents, definerAgent};
//        } else if (currentModeString.equals("Language Learning")){
//            features = new String[]{explicitAgent, languageLearningAgent, llContextConvoAgent};
//        } else if (currentModeString.equals("ADHD Glasses")){
//            Log.d(TAG, "Settings features for ADHD Glasses");
//            features = new String[]{explicitAgent, adhdStmbAgent};
//        }
//    }

    public static void saveCurrentModeLocal(Context context, String currentModeString) {
        //save the new mode
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getResources().getString(R.string.SHARED_PREF_CURRENT_MODE), currentModeString)
                .apply();
    }

    public void saveCurrentMode(Context context, String currentModeString) {
//        if (smartGlassesService != null)
//            smartGlassesService.sendHomeScreen();

        saveCurrentModeLocal(context, currentModeString);

        try{
            JSONObject settingsObj = new JSONObject();
            settingsObj.put("current_mode", currentModeString);
            //     sendSettings(settingsObj);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public String getCurrentMode(Context context) {
        String currentModeString = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.SHARED_PREF_CURRENT_MODE), "");
        // if (currentModeString.equals("")){
        //     currentModeString = "Proactive Agents";
        //     saveCurrentMode(context, currentModeString);
        // }
//        return currentModeString;
        return "Hard Coded Mode"; // TODO: hard coded mode
    }

    public void updateVocabularyUpgradeOnBackend(Context context){
        Boolean upgradeEnabled = isVocabularyUpgradeEnabled(context);
        try{
            JSONObject settingsObj = new JSONObject();
            settingsObj.put("vocabulary_upgrade_enabled", upgradeEnabled);
            sendSettings(settingsObj);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }
    public void updateTargetLanguageOnBackend(Context context){
        String targetLanguage = getChosenTargetLanguage(context);
        try{
            JSONObject settingsObj = new JSONObject();
            settingsObj.put("target_language", targetLanguage);
            sendSettings(settingsObj);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }


    public void updateSourceLanguageOnBackend(Context context){
        String sourceLanguage = getChosenSourceLanguage(context);
        try{
            JSONObject settingsObj = new JSONObject();
            settingsObj.put("source_language", sourceLanguage);
            sendSettings(settingsObj);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }


    //language learning
    public void updateDefinedWords(JSONArray newData) {
        long currentTime = System.currentTimeMillis();

        // Add new data to the list
        for (int i = 0; i < newData.length(); i++) {
            try {
                JSONObject wordData = newData.getJSONObject(i);
                definedWords.addFirst(new DefinedWord(
                        wordData.getString("in_word"),
                        wordData.getString("in_word_translation"),
                        wordData.getLong("timestamp"),
                        wordData.getString("uuid")
                ));
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        // Remove old words based on time constraint
        definedWords.removeIf(word -> (currentTime - (word.timestamp * 1000)) > llDefinedWordsShowTime);

        // Ensure list does not exceed max size
        while (definedWords.size() > maxDefinedWordsShow) {
            definedWords.removeLast();
        }
    }

    public void updateCombineResponse(JSONArray llData, JSONArray ugData) {
        long currentTime = System.currentTimeMillis();
        // Add new data to the list
        for (int i = 0; i < llData.length(); i++) {
            try {
                JSONObject wordData = llData.getJSONObject(i);
                llCombineResponses.addFirst(new LLCombineResponse(
                        null,
                        null,
                        wordData.getString("in_word"),
                        wordData.getString("in_word_translation"),
                        wordData.getLong("timestamp"),
                        wordData.getString("uuid")
                ));
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        for (int i = 0; i < ugData.length(); i++) {
            try {
                JSONObject resData = ugData.getJSONObject(i);
                llCombineResponses.addFirst(new LLCombineResponse(
                        resData.getString("in_upgrade"),
                        resData.getString("in_upgrade_meaning"),
                        null,
                        null,
                        resData.getLong("timestamp"),
                        resData.getString("uuid")
                ));
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        // Remove old words based on time constraint
        llCombineResponses.removeIf(word -> (currentTime - (word.timestamp * 1000)) > llCombineShowTime);

        // Ensure list does not exceed max size
        while (llCombineResponses.size() > maxLLCombineShow) {
            llCombineResponses.removeLast();
        }
    }

    public void updateAdhdSummaries(JSONArray newData) {
        long currentTime = System.currentTimeMillis();
        boolean foundNewFalseShift = false;
        STMBSummary newFalseShiftSummary = null;

        // First, identify if there's a new summary with true_shift = false and prepare it
        for (int i = 0; i < newData.length(); i++) {
            try {
                JSONObject summaryData = newData.getJSONObject(i);
                if (!summaryData.getBoolean("true_shift")) {
                    foundNewFalseShift = true;
                    newFalseShiftSummary = new STMBSummary(
                            summaryData.getString("summary"),
                            summaryData.getLong("timestamp"),
                            false,
                            summaryData.getString("uuid"));
                    break; // Stop after finding the first false shift as only one should exist
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // If a new false shift summary exists, remove the old false shift summary
        if (foundNewFalseShift) {
            adhdStmbSummaries.removeIf(summary -> !summary.true_shift);
        }

        // Now, add new data while excluding the newly identified false shift summary
        for (int i = 0; i < newData.length(); i++) {
            try {
                JSONObject summaryData = newData.getJSONObject(i);
                if (summaryData.getBoolean("true_shift") || !foundNewFalseShift || !summaryData.getString("uuid").equals(newFalseShiftSummary.uuid)) {
                    adhdStmbSummaries.addFirst(new STMBSummary(
                            summaryData.getString("summary"),
                            summaryData.getLong("timestamp"),
                            summaryData.getBoolean("true_shift"),
                            summaryData.getString("uuid")
                    ));
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // Add the new false shift summary at the beginning if it exists
        if (newFalseShiftSummary != null) {
            adhdStmbSummaries.addFirst(newFalseShiftSummary);
        }

        // Remove old words based on time constraint
        adhdStmbSummaries.removeIf(summary -> (currentTime - (summary.timestamp * 1000)) > adhdSummaryShowTime);

        // Ensure list does not exceed max size
        while (adhdStmbSummaries.size() > maxAdhdStmbShowNum) {
            adhdStmbSummaries.removeLast();
        }
    }

    // Getter for the list, if needed
    public LinkedList<DefinedWord> getDefinedWords() {
        return definedWords;
    }

    public LinkedList<STMBSummary> getAdhdStmbSummaries() {
        return adhdStmbSummaries;
    }

    public LinkedList<LLUpgradeResponse> getLLUpgradeResponse() {
        return llUpgradeResponses;
    }

    public LinkedList<LLCombineResponse> getLLCombineResponse() {
        return llCombineResponses;
    }

    // A simple representation of your word data
    private static class DefinedWord {
        String inWord;
        String inWordTranslation;
        long timestamp;
        String uuid;

        DefinedWord(String inWord, String inWordTranslation, long timestamp, String uuid) {
            this.inWord = inWord;
            this.inWordTranslation = inWordTranslation;
            this.timestamp = timestamp;
            this.uuid = uuid;
        }
    }

    // A simple representation of upgrade word data
    // private static class UpgradeWord {
    //     String inUpgrade;
    //     String inUpgradeMeaning;
    //     long timestamp;
    //     String uuid;

    //     UpgradeWord(String inUpgrade, String inUpgradeMeaning, long timestamp, String uuid) {
    //         this.inUpgrade = inUpgrade;
    //         this.inUpgradeMeaning = inUpgradeMeaning;
    //         this.timestamp = timestamp;
    //         this.uuid = uuid;
    //     }
    // }

    // A simple representation of ADHD STMB data
    private static class STMBSummary {
        String summary;
        long timestamp;
        boolean true_shift;
        String uuid;

        STMBSummary(String summary, long timestamp, boolean true_shift, String uuid) {
            this.summary = summary;
            this.timestamp = timestamp;
            this.true_shift = true_shift;
            this.uuid = uuid;
        }
    }


    //context convo
    public void updateContextConvoResponses(JSONArray newData) {
        long currentTime = System.currentTimeMillis();
//        Log.d(TAG, "GOT NEW DATA: ");
//        Log.d(TAG, newData.toString());

        // Add new data to the list
        for (int i = 0; i < newData.length(); i++) {
            try {
                JSONObject wordData = newData.getJSONObject(i);
                contextConvoResponses.addFirst(new ContextConvoResponse(
                        wordData.getString("ll_context_convo_response"),
                        wordData.getLong("timestamp"),
                        wordData.getString("uuid")
                ));
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        contextConvoResponses.removeIf(contextConvoResponse -> (currentTime - (contextConvoResponse.timestamp * 1000)) > llContextConvoResponsesShowTime);

        // Ensure list does not exceed max size
        while (contextConvoResponses.size() > maxContextConvoResponsesShow) {
            contextConvoResponses.removeLast();
        }
    }

    public void updateLLUpgradeResponse(JSONArray newData) {
        long currentTime = System.currentTimeMillis();
        // Add new data to the list
        for (int i = 0; i < newData.length(); i++) {
            try {
                JSONObject resData = newData.getJSONObject(i);
                llUpgradeResponses.addFirst(new LLUpgradeResponse(
                        resData.getString("in_upgrade"),
                        resData.getString("in_upgrade_meaning"),
                        resData.getLong("timestamp"),
                        resData.getString("uuid")
                ));
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        llUpgradeResponses.removeIf(llupgradeResponse -> (currentTime - (llupgradeResponse.timestamp * 1000)) > llUpgradeShowTime);

        // Ensure list does not exceed max size
        while (llUpgradeResponses.size() > maxLLUpgradeResponsesShow) {
            llUpgradeResponses.removeLast();
        }
    }



    // Getter for the list, if needed
    public LinkedList<ContextConvoResponse> getContextConvoResponses() {
        return contextConvoResponses;
    }

    // A simple representation of your word data
    private static class ContextConvoResponse {
        String response;
        long timestamp;
        String uuid;

        ContextConvoResponse(String response, long timestamp, String uuid) {
            this.response = response;
            this.timestamp = timestamp;
            this.uuid = uuid;
        }
    }

    private static class LLUpgradeResponse {
        String inUpgrade;
        String inUpgradeMeaning;
        long timestamp;
        String uuid;

        LLUpgradeResponse(String inUpgrade, String inUpgradeMeaning, long timestamp, String uuid) {
            this.inUpgrade = inUpgrade;
            this.inUpgradeMeaning = inUpgradeMeaning;
            this.timestamp = timestamp;
            this.uuid = uuid;
        }
    }

    // A simple representation of combination of ll rare and ll upgrade
    private static class LLCombineResponse {
        String inUpgrade;
        String inUpgradeMeaning;
        String inWord;
        String inWordTranslation;
        long timestamp;
        String uuid;

        LLCombineResponse(String inUpgrade, String inUpgradeMeaning,String inWord, String inWordTranslation, long timestamp, String uuid) {
            this.inUpgrade = inUpgrade;
            this.inUpgradeMeaning = inUpgradeMeaning;
            this.inWord = inWord;
            this.inWordTranslation = inWordTranslation;
            this.timestamp = timestamp;
            this.uuid = uuid;
        }
    }

    //retry auth right away if it failed, but don't do it too much as we have a max # refreshes/day
    private int max_google_retries = 3;
    private int googleAuthRetryCount = 0;
    private long lastGoogleAuthRetryTime = 0;

    @Subscribe
    public void onGoogleAuthFailedEvent(GoogleAuthFailedEvent event) {
        Log.d(TAG, "onGoogleAuthFailedEvent triggered: " + event.reason);
        numConsecutiveAuthFailures += 1;
        if(numConsecutiveAuthFailures > 10) {
            Log.d("TAG", "ATTEMPT SIGN OUT");
            handleSignOut();
        }
    }

    // Used for notifications and for screen mirror
    @Subscribe
    public void onNewScreenTextEvent(NewScreenTextEvent event) {
//        // Notification
//        if (event.title != null && event.body != null) {
//            if (smartGlassesService != null)
//                smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendReferenceCard(event.title, event.body), false, false, false));
//        }
//        else if (event.body != null){ //Screen mirror text
//            if (smartGlassesService != null)
//                smartGlassesService.windowManager.addTask(new WindowManager.Task(() -> smartGlassesService.sendTextWall(event.body), false, true, false));
//        }
    }

    @Subscribe
    public void onGlassesBluetoothSearchDiscoverEvent(GlassesBluetoothSearchDiscoverEvent event){
        blePeripheral.sendGlassesBluetoothDiscoverResultToManager(event.modelName, event.deviceName);
    }

    @Subscribe
    public void onGlassesBluetoothSearchStopEvent(GlassesBluetoothSearchStopEvent event){
        blePeripheral.sendGlassesBluetoothStopToManager(event.modelName);
    }

    @Subscribe
    public void onNewScreenImageEvent(NewScreenImageEvent event) {
        if (smartGlassesService != null)
            smartGlassesService.windowManager.showAppLayer("server", () -> smartGlassesService.sendBitmap(event.bmp), -1);
    }

    private void updateLastDataSentTime() {
        lastDataSentTime = System.currentTimeMillis();
    }

    private void startNotificationService() {
        Intent notificationServiceIntent = new Intent(this, MyNotificationListeners.class);
        startService(notificationServiceIntent);

        NotificationListenerService.requestRebind(
                new ComponentName(this, MyNotificationListeners.class));
    }

    private void stopNotificationService() {
        Intent notificationServiceIntent = new Intent(this, MyNotificationListeners.class);
        stopService(notificationServiceIntent);
    }

    public boolean getIsSearchingForGlasses() {
        return isSmartGlassesServiceBound && smartGlassesService.getConnectedSmartGlasses() == null;
    }

    private void executeOnceSmartGlassesServiceReady(Context context, Runnable action) {
        if (smartGlassesService != null && smartGlassesService != null) {
            // If the service is already bound, execute the action immediately
            action.run();
            return;
        }

        // Add the action to the queue
        serviceReadyListeners.add(action);

        // Ensure the service is started and bound
        if (smartGlassesService == null) {
            startSmartGlassesService();
        }
    }

    public JSONObject generateStatusJson() {
        try {
            // Creating the main status object
            JSONObject status = new JSONObject();

            // Adding puck battery life and charging status
            status.put("puck_battery_life", batteryStatusHelper.getBatteryLevel());
            status.put("charging_status", batteryStatusHelper.isBatteryCharging());
            status.put("sensing_enabled", SpeechRecSwitchSystem.sensing_enabled);
            status.put("contextual_dashboard_enabled", this.contextualDashboardEnabled);
            status.put("default_wearable", AugmentosSmartGlassesService.getPreferredWearable(this));
            Log.d(TAG, "PREFER - Got default wearabe: " + AugmentosSmartGlassesService.getPreferredWearable(this));

            // Adding connected glasses object
            JSONObject connectedGlasses = new JSONObject();
            if(smartGlassesService != null && smartGlassesService.getConnectedSmartGlasses() != null) {
                connectedGlasses.put("model_name", smartGlassesService.getConnectedSmartGlasses().deviceModelName);
                connectedGlasses.put("battery_life", (batteryLevel == null) ? -1: batteryLevel); //-1 if unknown
                String brightnessString;
                if (brightnessLevel == null) {
                    brightnessString = "-";
                } else if (brightnessLevel == -1){
                    brightnessString = "AUTO";
                } else {
                    brightnessString = brightnessLevel.toString() + "%";
                }
                connectedGlasses.put("brightness", brightnessString);
            }
            else {
                connectedGlasses.put("is_searching", getIsSearchingForGlasses());
            }
            status.put("connected_glasses", connectedGlasses);

            // Adding wifi status
            JSONObject wifi = new JSONObject();
            wifi.put("is_connected", wifiStatusHelper.isWifiConnected());
            wifi.put("ssid", wifiStatusHelper.getSSID());
            wifi.put("signal_strength", wifiStatusHelper.getSignalStrength());
            status.put("wifi", wifi);

            // Adding gsm status
            JSONObject gsm = new JSONObject();
            gsm.put("is_connected", gsmStatusHelper.isConnected());
            gsm.put("carrier", gsmStatusHelper.getNetworkType());
            gsm.put("signal_strength", gsmStatusHelper.getSignalStrength());
            status.put("gsm", gsm);

            // Adding apps array
            JSONArray apps = new JSONArray();

            for (ThirdPartyApp tpa : tpaSystem.getThirdPartyApps()) {
                if(tpa.appType != ThirdPartyAppType.APP) continue;

                JSONObject tpaObj = tpa.toJson(false);
                //JSONObject tpaObj = new JSONObject();
                //tpaObj.put("name", tpa.appName);
                //tpaObj.put("description", tpa.appDescription);
                tpaObj.put("is_running", tpaSystem.checkIsThirdPartyAppRunningByPackageName(tpa.packageName));
                tpaObj.put("is_foreground", tpaSystem.checkIsThirdPartyAppRunningByPackageName(tpa.packageName));
                tpaObj.put("version", tpa.version);
                //tpaObj.put("package_name", tpa.packageName);
                //tpaObj.put("type", tpa.appType.name());
                apps.put(tpaObj);
            }

            // Adding apps array to the status object
            status.put("apps", apps);



            // Wrapping the status object inside a main object (as shown in your example)
            JSONObject mainObject = new JSONObject();
            mainObject.put("status", status);

            try {
                Map<String, Object> props = convertJsonToMap(status);
                postHog.capture(userId, "status", props);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            return mainObject;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // AugmentOS_Manager Comms Callbacks
    public void sendStatusToAugmentOsManager(){
        // Build status obj, send to aosmanager
        JSONObject status = generateStatusJson();
        blePeripheral.sendDataToAugmentOsManager(status.toString());
    }

    @Override
    public void requestPing() {
        Log.d("AugmentOsService", "Requesting ping: ");
        blePeripheral.sendPing();
    }

    @Override
    public void requestStatus() {
        Log.d("AugmentOsService", "Requesting status: ");
        sendStatusToAugmentOsManager();
    }

    @Override
    public void searchForCompatibleDeviceNames(String modelName) {
        Log.d("AugmentOsService", "Searching for compatible device names for model: " + modelName);
        SmartGlassesDevice device = getSmartGlassesDeviceFromModelName(modelName);
        if (device == null) {
            blePeripheral.sendNotifyManager("Incorrect model name: " + modelName, "error");
            return;
        }

        executeOnceSmartGlassesServiceReady(this, () -> {
            smartGlassesService.findCompatibleDeviceNames(device);
            // blePeripheral.sendGlassesSearchResultsToManager(modelName, compatibleDeviceNames);
        });
    }

    @Override
    public void connectToWearable(String modelName, String deviceName) {
        Log.d("AugmentOsService", "Connecting to wearable: " + modelName + ". DeviceName: " + deviceName + ".");
        // Logic to connect to wearable
        SmartGlassesDevice device = getSmartGlassesDeviceFromModelName(modelName);
        if (device == null) {
            blePeripheral.sendNotifyManager("Incorrect model name: " + modelName, "error");
            return;
        }

        // TODO: Good lord this is hacky
        // TODO: Find a good way to conditionally send a glasses bt device name to SGC
        if (!deviceName.isEmpty() && modelName.contains("Even Realities"))
            savePreferredG1DeviceId(this, deviceName);

        executeOnceSmartGlassesServiceReady(this, () -> {
            smartGlassesService.connectToSmartGlasses(device);
            sendStatusToAugmentOsManager();
        });
    }

    @Override
    public void disconnectWearable(String wearableId) {
        Log.d("AugmentOsService", "Disconnecting from wearable: " + wearableId);
        // Logic to disconnect wearable
        stopSmartGlassesService();

        //reset some local variables
        brightnessLevel = null;
        batteryLevel = null;
    }

    @Override
    public void forgetSmartGlasses() {
        Log.d("AugmentOsService", "Forgetting wearable");
        savePreferredWearable(this, "");
        deleteEvenSharedPreferences(this);
        stopSmartGlassesService();
        sendStatusToAugmentOsManager();
    }

    @Override
    public void startApp(String packageName) {
        Log.d("AugmentOsService", "Starting app: " + packageName);
        // Logic to start the app by package name

        // Only allow starting apps if glasses are connected
        if (smartGlassesService != null && smartGlassesService.getConnectedSmartGlasses() != null) {
            tpaSystem.startThirdPartyAppByPackageName(packageName);
            sendStatusToAugmentOsManager();
        } else {
            blePeripheral.sendNotifyManager("Must connect glasses to start an app", "error");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("package_name", packageName);
        props.put("timestamp", System.currentTimeMillis());
        postHog.capture(userId, "start_app", props);
    }

    @Override
    public void stopApp(String packageName) {
        Log.d("AugmentOsService", "Stopping app: " + packageName);
        tpaSystem.stopThirdPartyAppByPackageName(packageName);
        sendStatusToAugmentOsManager();

        Map<String, Object> props = new HashMap<>();
        props.put("package_name", packageName);
        props.put("timestamp", System.currentTimeMillis());
        postHog.capture(userId, "stop_app", props);
    }

    @Override
    public void setSensingEnabled(boolean sensingEnabled) {
        if (smartGlassesService != null) {
            EventBus.getDefault().post(new SetSensingEnabledEvent(sensingEnabled));
        } else {
            blePeripheral.sendNotifyManager("Connect glasses to toggle sensing", "error");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("sensing_enabled", sensingEnabled);
        props.put("timestamp", System.currentTimeMillis());
        postHog.capture(userId, "set_sensing_enabled", props);
    }

    @Override
    public void setContextualDashboardEnabled(boolean contextualDashboardEnabled) {
        saveContextualDashboardEnabled(contextualDashboardEnabled);
        this.contextualDashboardEnabled = contextualDashboardEnabled;
    }

    public boolean getContextualDashboardEnabled() {
        return this.getSharedPreferences("AugmentOSPrefs", Context.MODE_PRIVATE).getBoolean("contextual_dashboard_enabled", true);
    }

    public void saveContextualDashboardEnabled(boolean enabled) {
        SharedPreferences sharedPreferences = this.getSharedPreferences("AugmentOSPrefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("contextual_dashboard_enabled", enabled);
        editor.apply();
    }

    @Override
    public void installAppFromRepository(String repository, String packageName) throws JSONException {
        Log.d("AugmentOsService", "Installing app from repository: " + packageName);

        JSONObject jsonQuery = new JSONObject();
        jsonQuery.put("packageName", packageName);

        backendServerComms.restRequest(REQUEST_APP_BY_PACKAGE_NAME_DOWNLOAD_LINK_ENDPOINT, jsonQuery, new VolleyJsonCallback() {
            @Override
            public void onSuccess(JSONObject result) {
                Log.d(TAG, "GOT INSTALL APP RESULT: " + result.toString());

                try {
                    String downloadLink = result.optString("download_url");
                    String appName = result.optString("app_name");
                    String version = result.optString("version");
                    if (!downloadLink.isEmpty()) {
                        Log.d(TAG, "Download link received: " + downloadLink);

                        if (downloadLink.startsWith("https://api.augmentos.org/")) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                downloadApk(downloadLink, packageName, appName, version);
                            }
                        } else {
                            Log.e(TAG, "The download link does not match the required domain.");
                            throw new UnsupportedOperationException("Download links outside of https://api.augmentos.org/ are not supported.");
                        }
                    } else {
                        Log.e(TAG, "Download link is missing in the response.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing download link: ", e);
                }
            }

            @Override
            public void onFailure(int code) {
                Log.d(TAG, "SOME FAILURE HAPPENED (installAppFromRepository)");
            }
        });
    }

    private void downloadApk(String downloadLink, String packageName, String appName, String version) { // TODO: Add fallback if the download doesn't succeed
        DownloadManager downloadManager = (DownloadManager) this.getSystemService(Context.DOWNLOAD_SERVICE);

        if (downloadManager != null) {
            Uri uri = Uri.parse(downloadLink);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle("Downloading " + appName);
//            request.setDescription("Downloading APK for " + appName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String downloadedAppName = appName.replace(" ", "") + "_" + version + ".apk";
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, downloadedAppName);
//            blePeripheral.sendAppIsInstalledEventToManager(packageName);

            long downloadId = downloadManager.enqueue(request);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        installApk(packageName, downloadedAppName);

                        context.unregisterReceiver(this);
                    }
                }
            };

            this.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    private void installApk(String packageName, String downloadedAppName) {
        File apkFile = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                downloadedAppName
        );
        if (!apkFile.exists() || apkFile.length() == 0) {
            Log.e("Installer", "APK file is missing or 0 bytes.");
            return;
        }

        Log.d("Installer", "APK file exists: " + apkFile.getAbsolutePath());

        blePeripheral.sendAppIsInstalledEventToManager(packageName);

//        Uri apkUri;
//        Intent intent = new Intent(Intent.ACTION_VIEW);
//        apkUri = FileProvider.getUriForFile(
//                getApplicationContext(),
//                getApplicationContext().getPackageName() + ".provider",
//                apkFile
//        );
//        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
//
//        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//        startActivity(intent);
//        blePeripheral.sendNotifyManager("App installed", "Success");
    }

    @Override
    public void uninstallApp(String uninstallPackageName) {
        Log.d(TAG, "uninstallApp not implemented");
        blePeripheral.sendNotifyManager("Uninstalling is not implemented yet", "error");
    }

    @Override
    public void requestAppInfo(String packageNameToGetDetails) {
        ThirdPartyApp tpa = tpaSystem.getThirdPartyAppByPackageName(packageNameToGetDetails);
        if (tpa == null) {
            blePeripheral.sendNotifyManager("Could not find app", "error");
            sendStatusToAugmentOsManager();
            return;
        }
        JSONArray settings = tpa.getSettings(this);
        if (settings == null) {
            blePeripheral.sendNotifyManager("Could not get app's details", "error");
            return;
        }
        blePeripheral.sendAppInfoToManager(tpa);

        Map<String, Object> props = new HashMap<>();
        props.put("package_name", packageNameToGetDetails);
        props.put("timestamp", System.currentTimeMillis());
        postHog.capture(userId, "request_app_info", props);
    }

    @Override
    public void handleNotificationData(JSONObject notificationData){
        try {
            if (notificationData != null) {
                String appName = notificationData.getString("appName");
                String title = notificationData.getString("title");
                String text = notificationData.getString("text");
                long timestamp = notificationData.getLong("timestamp");
                String uuid = notificationData.getString("uuid");

                EventBus.getDefault().post(new NotificationEvent(title, text, appName, timestamp, uuid));
            } else {
                System.out.println("Notification Data is null");
            }
        } catch (JSONException e) {
            Log.d(TAG, "JSONException occurred while handling notification data: " + e.getMessage());
        }
    }

    @Override
    public void updateGlassesBrightness(int brightness) {
        Log.d("AugmentOsService", "Updating glasses brightness: " + brightness);
        if (smartGlassesService != null) {
            String title = "Brightness Adjustment";
            String body = "Updating glasses brightness to " + brightness + "%.";
            smartGlassesService.windowManager.showAppLayer("system", () -> SmartGlassesAndroidService.sendReferenceCard(title, body), 6);
            smartGlassesService.updateGlassesBrightness(brightness);
        } else {
            blePeripheral.sendNotifyManager("Connect glasses to update brightness", "error");
        }
    }

    @Override
    public void setAuthSecretKey(String authSecretKey) {
        Log.d("AugmentOsService", "Setting auth secret key: " + authSecretKey);
        // Logic to set the authentication key
        // Save the new authSecretKey & verify it

        // NOTE: This wont be used until phase 2
    }

    @Override
    public void verifyAuthSecretKey() {
        Log.d("AugmentOsService", "Deleting auth secret key");
        // Logic to verify the authentication key
        // (Ping a server /login or /verify route & return the result to aosManager)

        // NOTE: This wont be used until phase 2
    }

    @Override
    public void deleteAuthSecretKey() {
        Log.d("AugmentOsService", "Deleting auth secret key");
        // Logic to delete the authentication key
        // Delete our authSecretKey

        // NOTE: This wont be used until phase 2
    }

    @Override
    public void updateAppSettings(String targetApp, JSONObject settings) {
        Log.d("AugmentOsService", "Updating settings for app: " + targetApp);
        ThirdPartyApp tpa = tpaSystem.getThirdPartyAppByPackageName(targetApp);
        if (tpa == null) {
            blePeripheral.sendNotifyManager("Could not find app", "error");
            return;
        }

        boolean allSuccess = true;
        try {
            // New loop over all keys in the settings object
            Iterator<String> keys = settings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = settings.get(key);
                if(!tpa.updateSetting(this, key, value)) {
                    allSuccess = false;
                }
            }
        } catch (JSONException e) {
            Log.e("AugmentOsService", "Failed to parse settings object", e);
            allSuccess = false;
        }

        if (!allSuccess) {
            blePeripheral.sendNotifyManager("Error updating settings", "error");
            return;
        }
    }

    public class LocalBinder extends Binder {
        public AugmentosService getService() {
            // Return this instance of LocalService so clients can call public methods
            return AugmentosService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}