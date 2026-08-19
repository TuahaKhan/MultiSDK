package com.example.ottsdk;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.clevertap.android.sdk.CleverTapAPI;
import com.clevertap.android.sdk.CleverTapInstanceConfig;
import com.clevertap.android.sdk.pushnotification.PushConstants;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public entry point of the OTT SDK.
 *
 * <p><strong>Isolation contract.</strong> Every CleverTap call in this library goes
 * through {@link #cleverTap()}, which only ever returns the Account B instance
 * built by {@link CleverTapAPI#instanceWithConfig(Context, CleverTapInstanceConfig)}.
 * This class never calls {@link CleverTapAPI#getDefaultInstance(Context)} and never
 * touches the host app's (Account A) instance or its stored state. That is the
 * whole guarantee, and it is enforced by construction: there is no code path here
 * that can reach the default instance.
 *
 * <p><strong>Multi-instance mechanism.</strong> This is CleverTap's documented
 * mechanism for a second account in the same Android application: build a
 * {@link CleverTapInstanceConfig} with
 * {@code CleverTapInstanceConfig.createInstance(context, accountId, accountToken[, region])}
 * and pass it to {@code CleverTapAPI.instanceWithConfig(...)}. The resulting
 * instance is a non-default instance: the CleverTap SDK namespaces all of its
 * local state by account id, so it gets its own CleverTap ID, its own session,
 * its own event queue and its own cached push token. No manual data clearing is
 * required. See the README section "Data clearing / CleverTap ID".
 */
public final class OttSdk {

    /** Tag for SDK-lifecycle logs. */
    private static final String TAG_SDK = "OTT_SDK";

    /** Tag for logs describing traffic on the OTT CleverTap instance (Account B). */
    private static final String TAG_CT = "OTT_CT";

    // ---- Sample OTT events, all of which must land in Account B only. --------
    public static final String EVENT_OTT_OPENED = "OTT_OPENED";

    public static final String EVENT_OTT_CONTENT_VIEWED = "OTT_CONTENT_VIEWED";

    public static final String EVENT_OTT_PLAY = "OTT_PLAY";

    public static final String EVENT_OTT_SUBSCRIPTION = "OTT_SUBSCRIPTION";

    private static Context appContext;

    private static OttSdkConfig sdkConfig;

    /** The Account B instance. Created lazily, exactly once. */
    private static CleverTapAPI ottCleverTap;

    private OttSdk() {
    }

    /**
     * Registers the OTT SDK with the host application. Safe to call from
     * {@code Application.onCreate()}.
     *
     * <p>Deliberately does <em>not</em> create the CleverTap Account B instance yet.
     * The instance is created on first actual use ({@link #open} or
     * {@link #fireCustomEvent}), or eagerly via {@link #startCleverTap()}. Keeping
     * these two steps separate is what lets the tester observe exactly when
     * Account B starts receiving anything, including automatic events.
     */
    public static synchronized void initialize(@NonNull Context context) {
        initialize(context, OttSdkConfig.fromBuildConfig());
    }

    /**
     * Same as {@link #initialize(Context)} but with explicit Account B credentials.
     */
    public static synchronized void initialize(@NonNull Context context, @NonNull OttSdkConfig config) {
        appContext = context.getApplicationContext();
        sdkConfig = config;
        Log.i(TAG_SDK, "initialize() -- OTT SDK registered with host app."
                + " CleverTap Account B instance NOT created yet (created on first use).");
        if (config.isPlaceholder()) {
            Log.w(TAG_SDK, "Account B credentials are placeholders/empty."
                    + " OTT events will be logged locally but will NOT reach CleverTap."
                    + " Set CLEVERTAP_ACCOUNT_B_ID / CLEVERTAP_ACCOUNT_B_TOKEN to test.");
        }
    }

    /**
     * Forces creation of the Account B CleverTap instance without opening any UI.
     *
     * <p>Useful for the isolation test: it separates "instance B exists" from
     * "the user opened the OTT screen", so App Launched behaviour on Account B can
     * be attributed precisely.
     *
     * @return true when the instance is available.
     */
    public static synchronized boolean startCleverTap() {
        return cleverTap() != null;
    }

    /**
     * Opens the OTT experience, as a host app would when the user taps an OTT entry
     * point. Creates the Account B instance if needed and fires {@code OTT_OPENED}.
     */
    @MainThread
    public static void open(@NonNull Context context) {
        Log.i(TAG_SDK, "open() -- opening OTT experience");
        if (appContext == null) {
            // Tolerate a host that forgot to call initialize().
            Log.w(TAG_SDK, "open() called before initialize(); initializing now with built-in config");
            initialize(context);
        }
        fireCustomEvent(EVENT_OTT_OPENED, singleProp("trigger", "host_open_button"));

        Intent intent = new Intent(context, OttActivity.class);
        context.startActivity(intent);
    }

    /**
     * Fires an event on the OTT CleverTap instance (Account B) only.
     */
    public static void fireCustomEvent(@NonNull String eventName) {
        fireCustomEvent(eventName, null);
    }

    /**
     * Fires an event with properties on the OTT CleverTap instance (Account B) only.
     */
    public static void fireCustomEvent(@NonNull String eventName, @Nullable Map<String, Object> properties) {
        Map<String, Object> props = new HashMap<>();
        props.put("source", "ott_sdk");
        props.put("test", true);
        if (properties != null) {
            props.putAll(properties);
        }

        CleverTapAPI ct = cleverTap();
        if (ct == null) {
            Log.w(TAG_CT, "DROPPED '" + eventName + "' -- no Account B instance available."
                    + " Nothing was sent to any CleverTap account.");
            return;
        }

        // Logging the resolved account id on every push is what makes leakage
        // visible in logcat: an OTT event must never print Account A's id here.
        Log.i(TAG_CT, "Firing " + eventName + " -> CleverTap instance accountId="
                + ct.getAccountId() + " props=" + props);
        ct.pushEvent(eventName, props);
    }

    /**
     * @return the OTT (Account B) CleverTap instance, creating it on first call, or
     *         {@code null} if the SDK was never initialized or the credentials are
     *         unusable.
     */
    @Nullable
    static synchronized CleverTapAPI cleverTap() {
        if (ottCleverTap != null) {
            return ottCleverTap;
        }
        if (appContext == null || sdkConfig == null) {
            Log.e(TAG_SDK, "OTT SDK not initialized -- call OttSdk.initialize(context) first");
            return null;
        }
        if (sdkConfig.isPlaceholder()) {
            // Do not hand CleverTap placeholder credentials; it would create an
            // instance that quietly fails, which is worse for a test than a
            // loud local log.
            Log.e(TAG_SDK, "Cannot create Account B instance: credentials are placeholders/empty.");
            return null;
        }

        String accountId = sdkConfig.accountId();
        String region = sdkConfig.region();

        Log.i(TAG_SDK, "Initializing OTT CleverTap instance (Account B) accountId="
                + accountId + " region=" + (region == null ? "<default>" : region));

        // Misconfiguration guard, run BEFORE creating anything.
        //
        // CleverTap keys its instance registry by account id. If Account B's id
        // matched an instance that already exists in this process (i.e. the host's
        // Account A), instanceWithConfig() would hand us back that existing
        // instance instead of a new one -- every "OTT" event would then go
        // straight to Account A while still looking like it worked. Detect that
        // and say so loudly.
        //
        // Note this is a pure read of the registry: it never calls
        // getDefaultInstance(), so the OTT SDK still cannot create or mutate the
        // host's instance.
        Map<String, CleverTapAPI> existing = CleverTapAPI.getInstances();
        if (existing != null && existing.containsKey(accountId)) {
            Log.e(TAG_SDK, "MISCONFIGURED: a CleverTap instance for accountId=" + accountId
                    + " already exists in this process. Account A and Account B must be"
                    + " DIFFERENT CleverTap accounts, otherwise the OTT SDK receives the"
                    + " host's instance and there is no isolation to test.");
        }

        // The official multi-instance API. Note: NOT getDefaultInstance().
        CleverTapInstanceConfig instanceConfig = (region == null)
                ? CleverTapInstanceConfig.createInstance(appContext, accountId, sdkConfig.accountToken())
                : CleverTapInstanceConfig.createInstance(appContext, accountId, sdkConfig.accountToken(), region);

        if (instanceConfig == null) {
            Log.e(TAG_SDK, "CleverTapInstanceConfig.createInstance returned null for Account B");
            return null;
        }

        // Keep the OTT instance's automatic tracking at CleverTap's normal
        // defaults. Two knobs are deliberately left ALONE so this project reports
        // real behaviour instead of a doctored result:
        //
        //   instanceConfig.setDisableAppLaunchedEvent(true)
        //       would stop "App Launched" being raised for Account B.
        //   instanceConfig.setAnalyticsOnly(true)
        //       would stop Account B from rendering push/in-app and from
        //       processing device tokens.
        //
        // See README "App launch / system events" and "Push token isolation" for
        // what actually happens and when you would want to flip either one.
        instanceConfig.setDebugLevel(CleverTapAPI.LogLevel.VERBOSE.intValue());

        CleverTapAPI instance = CleverTapAPI.instanceWithConfig(appContext, instanceConfig);
        if (instance == null) {
            Log.e(TAG_SDK, "CleverTapAPI.instanceWithConfig returned null for Account B");
            return null;
        }

        ottCleverTap = instance;
        Log.i(TAG_SDK, "OTT CleverTap instance ready. accountId=" + instance.getAccountId());
        return ottCleverTap;
    }

    /**
     * Builds a human-readable snapshot of the OTT instance's identity state, for
     * comparing against the host instance during testing. Never includes the
     * account token; the push token is reduced to a short fingerprint so two
     * instances can be compared without dumping the credential into logs.
     */
    @NonNull
    public static String describeState() {
        // Reads the field directly instead of calling cleverTap(), so asking for
        // diagnostics never has the side effect of creating the Account B
        // instance. That matters: the test needs to be able to confirm that
        // Account B is genuinely untouched before the OTT SDK is used.
        CleverTapAPI ct = ottCleverTap;
        if (ct == null) {
            return "[OTT_CT] Account B instance not created yet";
        }
        String token;
        try {
            token = ct.getDevicePushToken(PushConstants.FCM);
        } catch (Throwable t) {
            token = null;
        }
        return "[OTT_CT] accountId=" + ct.getAccountId()
                + " cleverTapId=" + ct.getCleverTapID()
                + " fcmToken=" + fingerprint(token);
    }

    /**
     * Reduces a push token to a comparable, non-sensitive fingerprint.
     */
    @NonNull
    static String fingerprint(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return "<none>";
        }
        int keep = Math.min(8, value.length());
        return value.substring(0, keep) + "...(len=" + value.length() + ")";
    }

    @NonNull
    private static Map<String, Object> singleProp(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
