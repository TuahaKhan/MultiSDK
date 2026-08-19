package com.example.multisdk;

import android.app.Application;
import android.util.Log;

import com.clevertap.android.sdk.ActivityLifecycleCallback;
import com.clevertap.android.sdk.CleverTapAPI;
import com.example.ottsdk.OttSdk;

/**
 * Host ("recharge app") Application class.
 *
 * <p>This is the normal, recommended CleverTap Android integration for the host app:
 * register the activity lifecycle callbacks, then let CleverTap build its default
 * instance from the manifest meta-data (Account A).
 */
public class HostApp extends Application {

    private static final String TAG = "HOST_APP";

    @Override
    public void onCreate() {
        // Set the log level before anything else so instance creation is logged.
        CleverTapAPI.setDebugLevel(CleverTapAPI.LogLevel.VERBOSE);

        // CleverTap's documented integration point. Must run before super.onCreate()
        // so the SDK sees the very first activity lifecycle callbacks.
        //
        // IMPORTANT for this project: these callbacks are process-wide, not
        // per-account. CleverTap dispatches each activity resume to EVERY instance
        // that exists at that moment -- which is why Account B gets its own
        // "App Launched" once the OTT instance exists. See README section
        // "App launch / system events".
        ActivityLifecycleCallback.register(this);

        super.onCreate();

        Log.i(TAG, "onCreate -- initializing host CleverTap (Account A)");

        // Account A: the host's own CleverTap default instance.
        HostCleverTap.initialize(this);

        // Register the OTT SDK. This does NOT create the Account B CleverTap
        // instance yet -- the SDK creates it on first use, so the test can tell
        // apart "SDK present" from "Account B active".
        OttSdk.initialize(this);
    }
}
