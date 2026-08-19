package com.example.multisdk;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.ottsdk.OttSdk;

/**
 * The whole host UI: a few buttons, nothing else.
 *
 * <p>Host buttons go through {@link HostCleverTap} (Account A). OTT buttons go
 * through the {@link OttSdk} public API (Account B). Nothing in this activity can
 * mix the two.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HOST_APP";

    private TextView stateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // targetSdk 35+ is edge-to-edge by default, so pad for the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        stateView = findViewById(R.id.state);

        // --- Account A ---
        findViewById(R.id.btn_host_event).setOnClickListener(v -> {
            HostCleverTap.fireHostEvent();
            refreshState();
        });

        // --- Account B, via the OTT SDK's public API ---
        findViewById(R.id.btn_open_ott).setOnClickListener(v -> {
            Log.i(TAG, "Open OTT SDK tapped -> OttSdk.open()");
            OttSdk.open(this);
            refreshState();
        });

        findViewById(R.id.btn_ott_event).setOnClickListener(v -> {
            OttSdk.fireCustomEvent(OttSdk.EVENT_OTT_CONTENT_VIEWED);
            refreshState();
        });

        findViewById(R.id.btn_ott_play).setOnClickListener(v -> {
            OttSdk.fireCustomEvent(OttSdk.EVENT_OTT_PLAY);
            refreshState();
        });

        findViewById(R.id.btn_ott_subscription).setOnClickListener(v -> {
            OttSdk.fireCustomEvent(OttSdk.EVENT_OTT_SUBSCRIPTION);
            refreshState();
        });

        // Prints both instances' identity side by side: account id, CleverTap ID and
        // push-token fingerprint. This is how the CleverTap-ID and push-token rows
        // of the testing matrix are checked.
        findViewById(R.id.btn_diagnostics).setOnClickListener(v -> {
            String host = HostCleverTap.describeState();
            String ott = OttSdk.describeState();
            Log.i(TAG, host);
            Log.i(TAG, ott);
            stateView.setText(host + "\n\n" + ott);
        });

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void refreshState() {
        stateView.setText(HostCleverTap.describeState() + "\n\n" + OttSdk.describeState());
    }
}
