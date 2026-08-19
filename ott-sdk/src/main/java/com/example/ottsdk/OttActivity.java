package com.example.ottsdk;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The OTT SDK's screen. Intentionally minimal: a label plus buttons that fire OTT
 * events, so the "OTT event after host event" direction of the test can be run
 * from inside the OTT surface.
 *
 * <p>Declared in the OTT SDK's own AndroidManifest.xml and merged into the host
 * app by the Android manifest merger.
 */
public class OttActivity extends AppCompatActivity {

    private static final String TAG_SDK = "OTT_SDK";

    private TextView stateView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ott_sdk_activity);

        // targetSdk 35+ is edge-to-edge by default, so pad for the system bars or
        // the content sits under the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ott_root), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        stateView = findViewById(R.id.ott_state);

        Log.i(TAG_SDK, "Opening OTT experience (OttActivity)");

        findViewById(R.id.ott_btn_content_viewed).setOnClickListener(v ->
                fire(OttSdk.EVENT_OTT_CONTENT_VIEWED));
        findViewById(R.id.ott_btn_play).setOnClickListener(v ->
                fire(OttSdk.EVENT_OTT_PLAY));
        findViewById(R.id.ott_btn_subscription).setOnClickListener(v ->
                fire(OttSdk.EVENT_OTT_SUBSCRIPTION));

        refreshState();
    }

    private void fire(String eventName) {
        OttSdk.fireCustomEvent(eventName);
        refreshState();
    }

    private void refreshState() {
        stateView.setText(OttSdk.describeState());
    }
}
