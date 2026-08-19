package com.example.multisdk;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.clevertap.android.sdk.CleverTapAPI;
import com.clevertap.android.sdk.pushnotification.PushConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * The host app's CleverTap integration -- Account A, and only Account A.
 *
 * <p><strong>Isolation contract.</strong> This class only ever uses
 * {@link CleverTapAPI#getDefaultInstance(Context)}, the instance configured from the
 * manifest meta-data. It never constructs a {@code CleverTapInstanceConfig} and
 * never reaches for the OTT SDK's instance, so host events cannot land in
 * Account B.
 */
final class HostCleverTap {

    private static final String TAG = "HOST_CT";

    static final String EVENT_HOST_CUSTOM = "HOST_CUSTOM_EVENT";

    private static CleverTapAPI hostCleverTap;

    private HostCleverTap() {
    }

    /**
     * Creates the default (Account A) instance. Called from
     * {@link HostApp#onCreate()}.
     */
    static synchronized void initialize(@NonNull Context context) {
        hostCleverTap = CleverTapAPI.getDefaultInstance(context.getApplicationContext());
        if (hostCleverTap == null) {
            Log.e(TAG, "getDefaultInstance() returned null."
                    + " Check CLEVERTAP_ACCOUNT_ID / CLEVERTAP_TOKEN in the merged manifest --"
                    + " they are injected from CLEVERTAP_ACCOUNT_A_* in gradle.properties.");
            return;
        }
        Log.i(TAG, "Host CleverTap instance ready (Account A). accountId="
                + hostCleverTap.getAccountId());
    }

    @Nullable
    static synchronized CleverTapAPI instance() {
        return hostCleverTap;
    }

    /**
     * Fires the host's custom event on Account A only.
     */
    static void fireHostEvent() {
        Map<String, Object> props = new HashMap<>();
        props.put("source", "host_app");
        props.put("test", true);

        CleverTapAPI ct = instance();
        if (ct == null) {
            Log.w(TAG, "DROPPED '" + EVENT_HOST_CUSTOM + "' -- no Account A instance available."
                    + " Nothing was sent to any CleverTap account.");
            return;
        }

        // Printing the resolved account id on every push is what makes leakage
        // visible: a host event must never print Account B's id here.
        Log.i(TAG, "Firing " + EVENT_HOST_CUSTOM + " -> CleverTap instance accountId="
                + ct.getAccountId() + " props=" + props);
        ct.pushEvent(EVENT_HOST_CUSTOM, props);
    }

    /**
     * Identity snapshot of the host instance, for side-by-side comparison with the
     * OTT instance. Never includes the account token; the push token is reduced to
     * a fingerprint so the two instances can be compared without logging the
     * credential itself.
     */
    @NonNull
    static String describeState() {
        CleverTapAPI ct = instance();
        if (ct == null) {
            return "[HOST_CT] Account A instance not available";
        }
        String token;
        try {
            token = ct.getDevicePushToken(PushConstants.FCM);
        } catch (Throwable t) {
            token = null;
        }
        return "[HOST_CT] accountId=" + ct.getAccountId()
                + " cleverTapId=" + ct.getCleverTapID()
                + " fcmToken=" + fingerprint(token);
    }

    @NonNull
    private static String fingerprint(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return "<none>";
        }
        int keep = Math.min(8, value.length());
        return value.substring(0, keep) + "...(len=" + value.length() + ")";
    }
}
