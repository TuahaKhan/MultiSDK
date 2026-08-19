package com.example.ottsdk;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Credentials the OTT SDK uses for its own CleverTap account (Account B).
 *
 * <p>By default these come from the OTT SDK module's own {@code BuildConfig}, which
 * is populated from {@code gradle.properties} placeholders. The host app never
 * needs to know them &mdash; that is the point: Account B belongs to the OTT
 * vendor, Account A belongs to the host.
 *
 * <p>The {@link #of} factory exists only so the isolation test can be pointed at
 * scratch accounts without rebuilding.
 */
public final class OttSdkConfig {

    private final String accountId;

    private final String accountToken;

    private final String region;

    private OttSdkConfig(String accountId, String accountToken, String region) {
        this.accountId = accountId;
        this.accountToken = accountToken;
        this.region = region;
    }

    /**
     * The OTT SDK's built-in Account B credentials, as compiled into the library.
     */
    @NonNull
    public static OttSdkConfig fromBuildConfig() {
        return new OttSdkConfig(
                BuildConfig.CLEVERTAP_ACCOUNT_B_ID,
                BuildConfig.CLEVERTAP_ACCOUNT_B_TOKEN,
                BuildConfig.CLEVERTAP_ACCOUNT_B_REGION
        );
    }

    /**
     * Explicit Account B credentials.
     *
     * @param region CleverTap region such as {@code eu1} / {@code in1} / {@code sg1},
     *               or {@code null} / empty to let the SDK use its default domain.
     */
    @NonNull
    public static OttSdkConfig of(
            @NonNull String accountId,
            @NonNull String accountToken,
            @Nullable String region
    ) {
        return new OttSdkConfig(accountId, accountToken, region);
    }

    @NonNull
    String accountId() {
        return accountId == null ? "" : accountId.trim();
    }

    @NonNull
    String accountToken() {
        return accountToken == null ? "" : accountToken.trim();
    }

    /**
     * @return the region, or {@code null} when unset. CleverTap treats a blank
     *         region as "no region", so this normalises blank to {@code null}
     *         rather than passing an empty string down.
     */
    @Nullable
    String region() {
        String trimmed = region == null ? "" : region.trim();
        return TextUtils.isEmpty(trimmed) ? null : trimmed;
    }

    /**
     * True when the credentials are still the committed placeholders (or empty),
     * meaning nothing can actually reach CleverTap.
     */
    boolean isPlaceholder() {
        String id = accountId();
        String token = accountToken();
        return TextUtils.isEmpty(id)
                || TextUtils.isEmpty(token)
                || id.startsWith("YOUR_ACCOUNT")
                || token.startsWith("YOUR_ACCOUNT");
    }
}
