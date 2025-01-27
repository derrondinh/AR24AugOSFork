package com.teamopensmartglasses.smartglassesmanager.speechrecognition.augmentos;

import android.content.Context;
import android.util.Log;

import com.teamopensmartglasses.augmentoslib.events.SpeechRecOutputEvent;
import com.teamopensmartglasses.smartglassesmanager.speechrecognition.SpeechRecFramework;
import com.teamopensmartglasses.smartglassesmanager.speechrecognition.vad.VadGateSpeechPolicy;
import com.teamopensmartglasses.smartglassesmanager.utils.EnvHelper;

import org.greenrobot.eventbus.EventBus;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SpeechRecAugmentos extends SpeechRecFramework {
    private static final String TAG = "WearableAi_SpeechRecAugmentos";
    private static SpeechRecAugmentos instance;

    private final Context mContext;
    private final String currentLanguageCode;
    private final String targetLanguageCode;
    private final boolean isTranslation;
    private final WebSocketStreamManager webSocketManager;
    private final BlockingQueue<byte[]> rollingBuffer;
    private final int bufferMaxSize;

    //VAD
    private VadGateSpeechPolicy vadPolicy;
    private volatile boolean isSpeaking = false; // Track VAD state

    private SpeechRecAugmentos(Context context, String languageLocale) {
        this.mContext = context;
        this.currentLanguageCode = initLanguageLocale(languageLocale);
        this.targetLanguageCode = null;
        this.isTranslation = false;
        this.webSocketManager = WebSocketStreamManager.getInstance(getServerUrl());

        // Rolling buffer stores last 3 seconds of audio
        this.bufferMaxSize = (int) ((16000 * 0.15 * 2) / 512); // ~150ms buffer (assuming 512-byte chunks)
        this.rollingBuffer = new LinkedBlockingQueue<>(bufferMaxSize);

        //VAD
        this.vadPolicy = new VadGateSpeechPolicy(mContext);
        this.vadPolicy.init(512);
        setupVadListener();
        startVadProcessingThread();

        setupWebSocketCallbacks();
    }

    //translation mode
    private SpeechRecAugmentos(Context context, String currentLanguageLocale, String targetLanguageLocale) {
        this.mContext = context;
        this.currentLanguageCode = initLanguageLocale(currentLanguageLocale);
        this.targetLanguageCode = initLanguageLocale(targetLanguageLocale);
        this.isTranslation = true;
        this.webSocketManager = WebSocketStreamManager.getInstance(getServerUrl());

        // Rolling buffer stores last 3 seconds of audio
        this.bufferMaxSize = (16000 * 3 * 2) / 512; // ~3 sec buffer (assuming 512-byte chunks)
        this.rollingBuffer = new LinkedBlockingQueue<>(bufferMaxSize);

        //VAD
        this.vadPolicy = new VadGateSpeechPolicy(mContext);
        this.vadPolicy.init(512);
        setupVadListener();
        startVadProcessingThread();

        setupWebSocketCallbacks();

        if (isTranslation) {
            Log.d(TAG, "Translation requested but not yet implemented");
        }
    }

    private String getServerUrl() {
        String host = EnvHelper.getEnv("AUGMENTOS_ASR_HOST");
        String port = EnvHelper.getEnv("AUGMENTOS_ASR_PORT");
        if (host == null || port == null) {
            throw new IllegalStateException("AugmentOS ASR config not found. Please ensure AUGMENTOS_ASR_HOST and AUGMENTOS_ASR_PORT are set.");
        }
        return String.format("ws://%s:%s", host, port);
    }

    private void setupVadListener() {
        new Thread(() -> {
            while (true) {
                boolean newVadState = vadPolicy.shouldPassAudioToRecognizer();

                if (newVadState && !isSpeaking) {
                    webSocketManager.sendVadStatus(true);
                    sendBufferedAudio();
                    isSpeaking = true;
                } else if (!newVadState && isSpeaking) {
                    isSpeaking = false;
                    webSocketManager.sendVadStatus(false);
                }

                try {
                    Thread.sleep(50); // Polling interval
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }).start();
    }

    private void setupWebSocketCallbacks() {
        webSocketManager.addCallback(new WebSocketStreamManager.WebSocketCallback() {
            @Override
            public void onInterimTranscript(String text, long timestamp) {
                Log.d(TAG, "Got intermediate from Cloud: " + text);
                if (text != null && !text.trim().isEmpty()) {
                    EventBus.getDefault().post(new SpeechRecOutputEvent(text, currentLanguageCode, timestamp, false));
                }
            }

            @Override
            public void onFinalTranscript(String text, long timestamp) {
                Log.d(TAG, "Got final from Cloud: " + text);
                if (text != null && !text.trim().isEmpty()) {
                    EventBus.getDefault().post(new SpeechRecOutputEvent(text, currentLanguageCode, timestamp, true));
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "WebSocket error: " + error);
                // Could add error handling/retry logic here
            }
        });
    }

    private void sendBufferedAudio() {
        Log.d(TAG, "Sending buffered audio...");

        List<byte[]> bufferDump = new ArrayList<>();
        rollingBuffer.drainTo(bufferDump);

        for (byte[] chunk : bufferDump) {
            webSocketManager.writeAudioChunk(chunk);
        }
    }

    private final BlockingQueue<Short> vadBuffer = new LinkedBlockingQueue<>(); // VAD buffer
    private final int vadFrameSize = 512;  // Silero expects 512-sample frames
    private volatile boolean vadRunning = true; // Control VAD processing thread

    @Override
    public void ingestAudioChunk(byte[] audioChunk) {
        if (vadPolicy == null) {
            Log.e(TAG, "VAD policy is not initialized yet. Skipping audio processing.");
            return;
        }

        if (!isVadInitialized()) {
            Log.e(TAG, "VAD model is not initialized properly. Skipping audio processing.");
            return;
        }

        // Convert byte[] to short[]
        short[] audioSamples = bytesToShort(audioChunk);

        // Add samples to the VAD buffer
        for (short sample : audioSamples) {
            if (vadBuffer.size() >= 16000) { // Keep max ~1 sec of audio
                vadBuffer.poll(); // Drop the oldest sample
            }
            vadBuffer.offer(sample);
        }

        if (isSpeaking) {
            webSocketManager.writeAudioChunk(audioChunk);
        }

        // Maintain rolling buffer for sending to WebSocket
        if (rollingBuffer.size() >= bufferMaxSize) {
            rollingBuffer.poll(); // Remove oldest chunk
        }
        rollingBuffer.offer(audioChunk);
    }

    private void startVadProcessingThread() {
        new Thread(() -> {
            while (vadRunning) {
                try {
                    // Wait until we have at least 512 samples
                    while (vadBuffer.size() < vadFrameSize) {
                        Thread.sleep(5); // Wait for more data to arrive
                    }

                    // Extract exactly 512 samples
                    short[] vadChunk = new short[vadFrameSize];
                    for (int i = 0; i < vadFrameSize; i++) {
                        vadChunk[i] = vadBuffer.poll();
                    }

                    // Send chunk to VAD
                    vadPolicy.processAudioBytes(shortsToBytes(vadChunk), 0, vadChunk.length * 2);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    // Utility method to convert short[] to byte[]
    private byte[] shortsToBytes(short[] shorts) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(shorts.length * 2);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts);
        return byteBuffer.array();
    }

    private short[] bytesToShort(byte[] bytes) {
        short[] shorts = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        return shorts;
    }

    private boolean isVadInitialized() {
        try {
            Field vadModelField = vadPolicy.getClass().getDeclaredField("vadModel");
            vadModelField.setAccessible(true);
            Object vadModel = vadModelField.get(vadPolicy);
            return vadModel != null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to check if VAD is initialized.", e);
            return false;
        }
    }

    @Override
    public void start() {
        Log.d(TAG, "Starting Speech Recognition Service");
        webSocketManager.connect(currentLanguageCode);
    }

    @Override
    public void destroy() {
        Log.d(TAG, "Destroying Speech Recognition Service");
        webSocketManager.disconnect();
    }

    public static synchronized SpeechRecAugmentos getInstance(Context context, String languageLocale) {
        if (instance == null || instance.isTranslation || !instance.currentLanguageCode.equals(languageLocale)) {
            if (instance != null) {
                instance.destroy();
            }
            instance = new SpeechRecAugmentos(context, languageLocale);
        }
        return instance;
    }

    public static synchronized SpeechRecAugmentos getInstance(Context context, String currentLanguageLocale, String targetLanguageLocale) {
        if (instance == null || !instance.isTranslation ||
                !instance.currentLanguageCode.equals(currentLanguageLocale) ||
                !instance.targetLanguageCode.equals(targetLanguageLocale)) {
            if (instance != null) {
                instance.destroy();
            }
            instance = new SpeechRecAugmentos(context, currentLanguageLocale, targetLanguageLocale);
        }
        return instance;
    }

    // Keep the existing initLanguageLocale method as is
    private String initLanguageLocale(String localeString) {
        // ... existing language code mapping implementation ...
        switch (localeString) {
            case "Afrikaans (South Africa)":
                return "af-ZA";
            case "Amharic (Ethiopia)":
                return "am-ET";
            case "Arabic (United Arab Emirates)":
                return "ar-AE";
            case "Arabic (Bahrain)":
                return "ar-BH";
            case "Arabic (Algeria)":
                return "ar-DZ";
            case "Arabic (Egypt)":
                return "ar-EG";
            case "Arabic (Israel)":
                return "ar-IL";
            case "Arabic (Iraq)":
                return "ar-IQ";
            case "Arabic (Jordan)":
                return "ar-JO";
            case "Arabic (Kuwait)":
                return "ar-KW";
            case "Arabic (Lebanon)":
                return "ar-LB";
            case "Arabic (Libya)":
                return "ar-LY";
            case "Arabic (Morocco)":
                return "ar-MA";
            case "Arabic (Oman)":
                return "ar-OM";
            case "Arabic (Palestinian Authority)":
                return "ar-PS";
            case "Arabic (Qatar)":
                return "ar-QA";
            case "Arabic (Saudi Arabia)":
                return "ar-SA";
            case "Arabic (Syria)":
                return "ar-SY";
            case "Arabic (Tunisia)":
                return "ar-TN";
            case "Arabic (Yemen)":
                return "ar-YE";
            case "Azerbaijani (Latin, Azerbaijan)":
                return "az-AZ";
            case "Bulgarian (Bulgaria)":
                return "bg-BG";
            case "Bengali (India)":
                return "bn-IN";
            case "Bosnian (Bosnia and Herzegovina)":
                return "bs-BA";
            case "Catalan":
                return "ca-ES";
            case "Czech (Czechia)":
                return "cs-CZ";
            case "Welsh (United Kingdom)":
                return "cy-GB";
            case "Danish (Denmark)":
                return "da-DK";
            case "German (Austria)":
                return "de-AT";
            case "German (Switzerland)":
                return "de-CH";
            case "German":
            case "German (Germany)":
                return "de-DE";
            case "Greek (Greece)":
                return "el-GR";
            case "English (Australia)":
                return "en-AU";
            case "English (Canada)":
                return "en-CA";
            case "English (United Kingdom)":
                return "en-GB";
            case "English (Ghana)":
                return "en-GH";
            case "English (Hong Kong SAR)":
                return "en-HK";
            case "English (Ireland)":
                return "en-IE";
            case "English (India)":
                return "en-IN";
            case "English (Kenya)":
                return "en-KE";
            case "English (Nigeria)":
                return "en-NG";
            case "English (New Zealand)":
                return "en-NZ";
            case "English (Philippines)":
                return "en-PH";
            case "English (Singapore)":
                return "en-SG";
            case "English (Tanzania)":
                return "en-TZ";
            case "English":
            case "English (United States)":
                return "en-US";
            case "English (South Africa)":
                return "en-ZA";
            case "Spanish (Argentina)":
                return "es-AR";
            case "Spanish (Bolivia)":
                return "es-BO";
            case "Spanish (Chile)":
                return "es-CL";
            case "Spanish (Colombia)":
                return "es-CO";
            case "Spanish (Costa Rica)":
                return "es-CR";
            case "Spanish (Cuba)":
                return "es-CU";
            case "Spanish (Dominican Republic)":
                return "es-DO";
            case "Spanish (Ecuador)":
                return "es-EC";
            case "Spanish (Spain)":
                return "es-ES";
            case "Spanish (Equatorial Guinea)":
                return "es-GQ";
            case "Spanish (Guatemala)":
                return "es-GT";
            case "Spanish (Honduras)":
                return "es-HN";
            case "Spanish":
            case "Spanish (Mexico)":
                return "es-MX";
            case "Spanish (Nicaragua)":
                return "es-NI";
            case "Spanish (Panama)":
                return "es-PA";
            case "Spanish (Peru)":
                return "es-PE";
            case "Spanish (Puerto Rico)":
                return "es-PR";
            case "Spanish (Paraguay)":
                return "es-PY";
            case "Spanish (El Salvador)":
                return "es-SV";
            case "Spanish (United States)":
                return "es-US";
            case "Spanish (Uruguay)":
                return "es-UY";
            case "Spanish (Venezuela)":
                return "es-VE";
            case "Estonian (Estonia)":
                return "et-EE";
            case "Basque":
                return "eu-ES";
            case "Persian (Iran)":
                return "fa-IR";
            case "Finnish (Finland)":
                return "fi-FI";
            case "Filipino (Philippines)":
                return "fil-PH";
            case "French (Belgium)":
                return "fr-BE";
            case "French (Canada)":
                return "fr-CA";
            case "French (Switzerland)":
                return "fr-CH";
            case "French":
            case "French (France)":
                return "fr-FR";
            case "Irish (Ireland)":
                return "ga-IE";
            case "Galician":
                return "gl-ES";
            case "Gujarati (India)":
                return "gu-IN";
            case "Hebrew":
            case "Hebrew (Israel)":
                return "he-IL";
            case "Hindi (India)":
                return "hi-IN";
            case "Croatian (Croatia)":
                return "hr-HR";
            case "Hungarian (Hungary)":
                return "hu-HU";
            case "Armenian (Armenia)":
                return "hy-AM";
            case "Indonesian (Indonesia)":
                return "id-ID";
            case "Icelandic (Iceland)":
                return "is-IS";
            case "Italian (Switzerland)":
                return "it-CH";
            case "Italian":
            case "Italian (Italy)":
                return "it-IT";
            case "Japanese":
            case "Japanese (Japan)":
                return "ja-JP";
            case "Javanese (Latin, Indonesia)":
                return "jv-ID";
            case "Georgian (Georgia)":
                return "ka-GE";
            case "Kazakh (Kazakhstan)":
                return "kk-KZ";
            case "Khmer (Cambodia)":
                return "km-KH";
            case "Kannada (India)":
                return "kn-IN";
            case "Korean":
            case "Korean (Korea)":
                return "ko-KR";
            case "Lao (Laos)":
                return "lo-LA";
            case "Lithuanian (Lithuania)":
                return "lt-LT";
            case "Latvian (Latvia)":
                return "lv-LV";
            case "Macedonian (North Macedonia)":
                return "mk-MK";
            case "Malayalam (India)":
                return "ml-IN";
            case "Mongolian (Mongolia)":
                return "mn-MN";
            case "Marathi (India)":
                return "mr-IN";
            case "Malay (Malaysia)":
                return "ms-MY";
            case "Maltese (Malta)":
                return "mt-MT";
            case "Burmese (Myanmar)":
                return "my-MM";
            case "Norwegian Bokmål (Norway)":
                return "nb-NO";
            case "Nepali (Nepal)":
                return "ne-NP";
            case "Dutch":
            case "Dutch (Belgium)":
                return "nl-BE";
            case "Dutch (Netherlands)":
                return "nl-NL";
            case "Punjabi (India)":
                return "pa-IN";
            case "Polish (Poland)":
                return "pl-PL";
            case "Pashto (Afghanistan)":
                return "ps-AF";
            case "Portuguese":
            case "Portuguese (Brazil)":
                return "pt-BR";
            case "Portuguese (Portugal)":
                return "pt-PT";
            case "Romanian (Romania)":
                return "ro-RO";
            case "Russian":
            case "Russian (Russia)":
                return "ru-RU";
            case "Sinhala (Sri Lanka)":
                return "si-LK";
            case "Slovak (Slovakia)":
                return "sk-SK";
            case "Slovenian (Slovenia)":
                return "sl-SI";
            case "Somali (Somalia)":
                return "so-SO";
            case "Albanian (Albania)":
                return "sq-AL";
            case "Serbian (Cyrillic, Serbia)":
                return "sr-RS";
            case "Swedish (Sweden)":
                return "sv-SE";
            case "Swahili (Kenya)":
                return "sw-KE";
            case "Swahili (Tanzania)":
                return "sw-TZ";
            case "Tamil (India)":
                return "ta-IN";
            case "Telugu (India)":
                return "te-IN";
            case "Thai (Thailand)":
                return "th-TH";
            case "Turkish":
            case "Turkish (Türkiye)":
                return "tr-TR";
            case "Ukrainian (Ukraine)":
                return "uk-UA";
            case "Urdu (India)":
                return "ur-IN";
            case "Uzbek (Latin, Uzbekistan)":
                return "uz-UZ";
            case "Vietnamese (Vietnam)":
                return "vi-VN";
            case "Chinese (Wu, Simplified)":
                return "wuu-CN";
            case "Chinese (Cantonese, Simplified)":
                return "yue-CN";
            case "Chinese":
            case "Chinese (Pinyin)":
            case "Chinese (Hanzi)":
            case "Chinese (Mandarin, Simplified)":
                return "zh-CN";
            case "Chinese (Jilu Mandarin, Simplified)":
                return "zh-CN-shandong";
            case "Chinese (Southwestern Mandarin, Simplified)":
                return "zh-CN-sichuan";
            case "Chinese (Cantonese, Traditional)":
                return "zh-HK";
            case "Chinese (Taiwanese Mandarin, Traditional)":
                return "zh-TW";
            case "Zulu (South Africa)":
                return "zu-ZA";
            default:
                return "en-US";
        }
    }
}