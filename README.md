# CleverTap Multi-Account Isolation Demo (Host App + OTT SDK)

A deliberately minimal Android proof-of-concept that answers one question:

> Can a host application and an embedded OTT SDK each run their own CleverTap
> account inside the **same Android application/process** without data leaking
> between the two accounts?

Short answer, from reading the CleverTap Android SDK source (see
[Evidence](#evidence-from-the-clevertap-sdk-source)): **yes, and no manual data
clearing is required.** CleverTap's multi-instance API namespaces all per-account
state by account id. There is one behaviour you must expect rather than treat as a
bug: **Account B gets its own automatic `App Launched` event** once its instance
exists. That is per-instance automatic tracking, not cross-account leakage. It is
documented in detail below, and deliberately **not** suppressed.

---

## 1. Project architecture

```text
ONE Android Application  (applicationId: com.example.multisdk, one process)
        │
        ├── :app          Host / "Recharge" app
        │                   └── CleverTap DEFAULT instance  → Account A
        │
        └── :ott-sdk      Android LIBRARY (com.android.library, AAR)
                            └── CleverTap ADDITIONAL instance → Account B
```

```text
                         Android Application
                                │
              ┌─────────────────┴─────────────────┐
       Recharge/Host App                     OTT SDK
              │                                   │
    CleverTap Instance A                CleverTap Instance B
      (default instance,                (instanceWithConfig,
       manifest meta-data)               configured in code)
              │                                   │
         Account A                           Account B
```

The OTT component is **not** another application, APK, package or `applicationId`.
It is a library module that runs inside the host's process.

### Files that matter

| File | Role |
| --- | --- |
| `app/src/main/java/.../HostApp.java` | `Application`; registers CleverTap lifecycle callbacks, creates Account A, registers the OTT SDK |
| `app/src/main/java/.../HostCleverTap.java` | Account A wrapper. Only ever uses `getDefaultInstance()` |
| `app/src/main/java/.../MainActivity.java` | The entire host UI (buttons) |
| `app/src/main/AndroidManifest.xml` | Account A credentials as CleverTap meta-data |
| `ott-sdk/src/main/java/.../OttSdk.java` | OTT SDK public API. Only ever uses `instanceWithConfig()` |
| `ott-sdk/src/main/java/.../OttSdkConfig.java` | Account B credentials (from the library's own `BuildConfig`) |
| `ott-sdk/src/main/java/.../OttActivity.java` | The OTT screen |
| `ott-sdk/src/main/AndroidManifest.xml` | The OTT SDK's own manifest, merged by Gradle |

---

## 2. Host application setup

Normal, recommended CleverTap Android integration:

1. `HostApp extends Application` is declared via `android:name=".HostApp"`.
2. `ActivityLifecycleCallback.register(this)` is called **before**
   `super.onCreate()`, per CleverTap's documented integration.
3. `CleverTapAPI.getDefaultInstance(context)` builds the Account A instance from
   the manifest meta-data.

```java
@Override
public void onCreate() {
    CleverTapAPI.setDebugLevel(CleverTapAPI.LogLevel.VERBOSE);
    ActivityLifecycleCallback.register(this);   // before super.onCreate()
    super.onCreate();

    HostCleverTap.initialize(this);   // Account A
    OttSdk.initialize(this);          // registers SDK; does NOT create Account B yet
}
```

The host behaves like any ordinary CleverTap-enabled app, and its automatic
tracking (App Launched, device/platform metadata) is left entirely alone.

---

## 3. OTT SDK setup

`:ott-sdk` uses the `com.android.library` plugin. Note what it deliberately does
**not** declare:

- no `applicationId`
- no `targetSdk` (that belongs to the consuming application)
- no launcher activity
- no `Application` class

The host consumes it as a normal module dependency:

```groovy
// app/build.gradle
implementation project(':ott-sdk')
```

Replacing this with a published AAR (`implementation 'com.vendor:ott-sdk:1.0.0'`)
would change nothing about the isolation behaviour.

---

## 4 & 5. CleverTap Account A and Account B configuration

**No real credentials are committed.** Placeholders live in `gradle.properties`:

```properties
CLEVERTAP_ACCOUNT_A_ID=YOUR_ACCOUNT_A_ID
CLEVERTAP_ACCOUNT_A_TOKEN=YOUR_ACCOUNT_A_TOKEN
CLEVERTAP_ACCOUNT_A_REGION=
CLEVERTAP_ACCOUNT_B_ID=YOUR_ACCOUNT_B_ID
CLEVERTAP_ACCOUNT_B_TOKEN=YOUR_ACCOUNT_B_TOKEN
CLEVERTAP_ACCOUNT_B_REGION=
```

Supply real values **without editing the repo**, either in
`~/.gradle/gradle.properties` or on the command line:

```bash
./gradlew :app:installDebug \
  -PCLEVERTAP_ACCOUNT_A_ID=xxx-xxx-xxxZ -PCLEVERTAP_ACCOUNT_A_TOKEN=aaa-bbb \
  -PCLEVERTAP_ACCOUNT_B_ID=yyy-yyy-yyyZ -PCLEVERTAP_ACCOUNT_B_TOKEN=ccc-ddd
```

`REGION` is optional (`eu1`, `in1`, `sg1`, `us1`, …). A blank region is safe: the
SDK checks `region.isNotNullAndBlank()` and falls back to its default domain.

### Where each account is configured — and why differently

**Account A → manifest meta-data.** These keys configure the *default* instance:

```xml
<meta-data android:name="CLEVERTAP_ACCOUNT_ID" android:value="${clevertapAccountIdA}" />
<meta-data android:name="CLEVERTAP_TOKEN"      android:value="${clevertapTokenA}" />
<meta-data android:name="CLEVERTAP_REGION"     android:value="${clevertapRegionA}" />
```

**Account B → code only.** The OTT SDK's manifest declares **none** of those keys.
This is the single most important design decision in the project: those meta-data
keys are global to the application, so if the OTT SDK also declared them the
manifest merger would either fail on the conflict or one account would silently
win — the exact failure this PoC exists to rule out.

Because credentials are injected at build time, if you rebuild with different
values the app keeps the profile/state stored under the *old* account id
namespace. Run `adb shell pm clear com.example.multisdk` when switching accounts.

---

## 6. Multi-instance initialization

This is CleverTap's official mechanism for a second account in the same app:

```java
CleverTapInstanceConfig cfg =
        CleverTapInstanceConfig.createInstance(appContext, accountId, accountToken);
        // or createInstance(appContext, accountId, accountToken, region)

CleverTapAPI ottInstance = CleverTapAPI.instanceWithConfig(appContext, cfg);
```

Simply "creating another CleverTap object" is **not** what happens here.
`instanceWithConfig()` registers a genuine non-default instance in CleverTap's
internal registry, and the SDK then namespaces that instance's storage, session,
event queue, CleverTap ID and push token by account id.

### The isolation contract, enforced by construction

| Component | Allowed API | Never calls |
| --- | --- | --- |
| Host (`HostCleverTap`) | `CleverTapAPI.getDefaultInstance()` | `instanceWithConfig()` |
| OTT SDK (`OttSdk`) | `CleverTapAPI.instanceWithConfig()` | `getDefaultInstance()` |

There is no code path in `OttSdk` that can reach the default instance, and none in
`HostCleverTap` that can reach instance B. Every event push logs the resolved
`getAccountId()`, so leakage would be visible in logcat immediately.

### Misconfiguration guard

CleverTap keys its instance registry **by account id**:

```java
CleverTapAPI instance = instances.get(config.getAccountId());
if (instance == null) { instance = new CleverTapAPI(...); instances.put(...); }
```

So if Account B's id equalled Account A's, `instanceWithConfig()` would hand the
OTT SDK **the host's existing instance** — every "OTT" event would land in
Account A while appearing to work. `OttSdk` therefore checks the registry
(a read-only `CleverTapAPI.getInstances()` lookup, which never creates the default
instance) and logs a loud `MISCONFIGURED` error if the id already exists.

The flip side is the isolation guarantee: **distinct account ids necessarily
produce distinct instances.**

---

## 7. How the OTT SDK is consumed

```java
OttSdk.initialize(context);                                 // Application.onCreate
OttSdk.startCleverTap();                                    // optional: create Account B eagerly
OttSdk.open(activity);                                      // opens OTT screen, fires OTT_OPENED
OttSdk.fireCustomEvent(OttSdk.EVENT_OTT_CONTENT_VIEWED);    // Account B only
OttSdk.describeState();                                     // diagnostics, creates nothing
```

`initialize()` deliberately does **not** create the CleverTap Account B instance.
The instance is created lazily on first real use (`open()` / `fireCustomEvent()`),
or eagerly via `startCleverTap()`. Keeping "SDK registered" separate from
"Account B active" is what lets you attribute Account B's automatic events
precisely. `describeState()` reads the instance field directly rather than
creating it, so asking for diagnostics can never itself activate Account B.

---

## 8. How custom events are fired

Host, on Account A:

```java
ct.pushEvent("HOST_CUSTOM_EVENT", Map.of("source", "host_app", "test", true));
```

OTT, on Account B — every OTT event carries `source = "ott_sdk"`, `test = true`:

| Event | Fired from |
| --- | --- |
| `OTT_OPENED` | `OttSdk.open()` |
| `OTT_CONTENT_VIEWED` | host button, and OTT screen button |
| `OTT_PLAY` | host button, and OTT screen button |
| `OTT_SUBSCRIPTION` | host button, and OTT screen button |

---

## 9. Manifest merging

Three manifests are merged by Android's normal merger — nothing is hand-copied.

1. **`app`** — `HostApp`, `MainActivity`, and the Account A meta-data.
2. **`ott-sdk`** — `OttActivity` only (`android:exported="false"`), plus its own
   theme/string resources. No `<application>` attributes, so it cannot fight the
   host over `name`/`icon`/`theme`. No CleverTap meta-data.
3. **`clevertap-android-sdk` (AAR)** — contributes, exactly once regardless of how
   many modules depend on it:

   | Component | Type |
   | --- | --- |
   | `CleverTapFileProvider` (`${applicationId}.clevertap.fileprovider`) | provider |
   | `InAppNotificationActivity` | activity |
   | `inbox.CTInboxActivity` | activity |
   | `pushnotification.CTPushNotificationReceiver` | receiver |
   | `pushnotification.fcm.CTFirebaseMessagingReceiver` | receiver |
   | `android.permission.POST_NOTIFICATIONS` | uses-permission |
   | `minSdkVersion 23` | uses-sdk |

**Merged-manifest behaviour observed:** there are no conflicting nodes. The
CleverTap components are account-agnostic — they are declared once per
*application*, not per *account*, and route to the correct instance at runtime by
account id (see §11). The `${applicationId}` placeholder in the FileProvider
authority resolves to the host's `com.example.multisdk`, so it stays unique.
The library's `minSdk 24` is compatible with CleverTap's `minSdkVersion 23`.

Inspect the real merged output with:

```bash
./gradlew :app:processDebugMainManifest
cat app/build/intermediates/merged_manifests/debug/*/AndroidManifest.xml
```

---

## 10. Gradle dependency resolution

```text
:app ──────────────► clevertap-android-sdk   (implementation, direct)
:app ──► :ott-sdk ─► clevertap-android-sdk   (api, transitive)
```

| Module | CleverTap declaration |
| --- | --- |
| `:app` | `implementation libs.clevertap.android.sdk` |
| `:ott-sdk` | `api libs.clevertap.android.sdk` |

Both resolve through **one** version-catalog entry, so the versions cannot drift:

```toml
# gradle/libs.versions.toml
clevertap = "8.4.1"
```

**How Gradle resolves this.** Two paths reach the same module
(`com.clevertap.android:clevertap-android-sdk`). Gradle builds a single resolved
dependency graph per configuration and selects **one** version per module — by
default the **highest** requested version — so exactly one copy of the AAR is
packaged. There is no way to end up with duplicate or incompatible CleverTap
copies from this setup. Because both declarations point at the same catalog alias
here, the "highest wins" rule never even has to break a tie.

`:ott-sdk` uses `api` rather than `implementation` so CleverTap is part of the
SDK's exposed graph, modelling a vendor SDK that brings CleverTap with it: the
host would still compile against CleverTap even without its own declaration. The
host declares it anyway because it runs its own Account A integration.

Verify the resolved graph:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i clevertap
```

If you ever *do* introduce a skew (e.g. the SDK on 8.4.0, the app on 8.4.1),
Gradle picks 8.4.1 and reports it as `8.4.0 -> 8.4.1`. To pin deliberately:

```groovy
configurations.configureEach {
    resolutionStrategy.force "com.clevertap.android:clevertap-android-sdk:8.4.1"
}
```

CleverTap 8.4.1 also pulls in `kotlin-stdlib`, `androidx.appcompat`,
`recyclerview`, `swiperefreshlayout`, `lifecycle-process`, `installreferrer` and
`work-runtime`. Where the app requests a higher version (e.g. appcompat 1.8.0 vs
CleverTap's 1.7.0), the higher one wins — normal Gradle behaviour, no action
needed.

---

## 11. Expected event behaviour

### Testing matrix

| Test | Expected Account A | Expected Account B |
| --- | --- | --- |
| Host App Launch | `App Launched` + normal system events ✅ | No host event leakage ✅ |
| Host Custom Event (`HOST_CUSTOM_EVENT`) | ✅ | ❌ |
| Open OTT SDK | No OTT event ❌ | `OTT_OPENED` ✅ **plus its own `App Launched`** (see below) |
| OTT Custom Event (`OTT_CONTENT_VIEWED`) | ❌ | ✅ |
| OTT Event #2 (`OTT_PLAY`) | ❌ | ✅ |
| OTT Event #3 (`OTT_SUBSCRIPTION`) | ❌ | ✅ |
| Host Event after OTT opens | ✅ | ❌ |
| OTT Event after host event | ❌ | ✅ |
| Host Push Token | Account A only ✅ | Must not be attributed to A's profile in B |
| OTT CleverTap data | Must not affect A ✅ | Account B only ✅ |

Run it in both directions: **Host → OTT** (fire host event, open OTT, fire OTT
events) and **OTT → Host** (open OTT, fire OTT events, return, fire host event).
Neither order should change any row.

### App launch / system events — the one behaviour to expect

**Account B will raise its own `App Launched` event.** This is per-instance
automatic tracking, not leakage. Mechanism, from the SDK source:

`CleverTapAPI.onActivityResumed()` iterates **every** registered instance:

```java
for (String accountId : CleverTapAPI.instances.keySet()) {
    instance.coreState.getActivityLifeCycleManager().activityResumed(activity);
}
```

and `ActivityLifeCycleManager.activityResumed()` gates App Launched on its
instance's **own** `CoreMetaData`:

```java
if (!coreMetaData.isAppLaunchPushed()) {
    analyticsManager.pushAppLaunchedEvent();
    ...
    pushProviders.onTokenRefresh();
}
```

Since each instance owns its `CoreMetaData`, each instance raises App Launched
once per **session** of its own. (It recurs after a session timeout:
`SessionManager.checkTimeoutSession()` → `destroySession()` calls
`setAppLaunchPushed(false)`, so a return to the foreground after
`Constants.SESSION_LENGTH_MINS` in the background raises it again — again, per
instance.) In this build the OTT instance is created lazily, so **Account B's
first `App Launched` appears at the first activity resume after `OttSdk.open()`**
— i.e. when `OttActivity` resumes, not at host launch.

Per the project requirements this is **not** suppressed. The knob exists if you
want it, and is left commented in `OttSdk.cleverTap()`:

```java
instanceConfig.setDisableAppLaunchedEvent(true);  // stops App Launched for Account B
instanceConfig.setAnalyticsOnly(true);            // also stops push/in-app rendering
```

The distinction that matters: this is **Account B's own** App Launched, generated
from Account B's own state, containing Account B's own device/session fields. No
Account A event, profile or identity crosses over.

### Push token isolation

The SDK caches each instance's token under an account-scoped key, and the same
`activityResumed` block calls `pushProviders.onTokenRefresh()` per instance:

```java
StorageHelper.putStringImmediate(context, config.getAccountId(), key, token); // "fcm_token:<accountId>"
```

Two consequences, stated plainly:

- **Correct attribution:** each instance registers the token against **its own**
  account. A's registration is stored and sent under A; B's under B.
- **The device token itself is device-level, not account-level.** FCM issues one
  token per app install. If both instances have push enabled, both accounts
  legitimately end up holding *the same token string* — that is how each account
  is able to reach the device, and it is not cross-account leakage. What must not
  happen (and does not) is A's token being attached to B's *profile/identity*, or
  vice versa: the profiles have different CleverTap IDs.

Inbound pushes route by account id. `CleverTapAPI.fromAccountId()` matches
`wzrk_acct_id` from the payload, and a payload without it goes **only** to the
default instance:

```java
shouldProcess = (_accountId == null && instance.coreState.getConfig().isDefaultInstance())
        || instance.getAccountId().equals(_accountId);
```

So a campaign sent from Account B is handled by instance B, and one from
Account A by instance A.

To keep push exclusively on Account A, set `setAnalyticsOnly(true)` on the OTT
config — the SDK then skips notification rendering and token processing for B.

### Data clearing / CleverTap ID

**No manual data clearing is required, and the host's state is never touched.**

All CleverTap local state is stored in one `SharedPreferences` file
(`WizRocket`) with **every key suffixed by account id**:

```kotlin
fun storageKeyWithSuffix(accountID: String, key: String) = "$key:$accountID"
```

and the device / CleverTap ID specifically:

```java
return Constants.DEVICE_ID_TAG + ":" + this.config.getAccountId();   // "deviceId:<accountId>"
```

So instance B gets its **own CleverTap ID** the first time it initialises, without
any reset call and without reading or writing Account A's keys. Answering the
question the brief asks explicitly:

> **The multi-instance implementation naturally maintains separate state. This
> project does not generate a new CleverTap ID for Account B, does not call any
> clear/reset API, and does not touch Account A's stored data.**

Verify with the **Log Instance Diagnostics** button: the two instances report
different `accountId` and different `cleverTapId`.

If you ever *did* need a fresh Account B identity, the correct scope-limited tools
are `CleverTapAPI.instanceWithConfig(context, ottConfig, customCleverTapId)`
(with `setEnableCustomCleverTapId(true)`), or `onUserLogin()` on instance B only.
Neither touches Account A. Never clear the `WizRocket` prefs file wholesale — that
would destroy both accounts' state.

---

## 12. Logging

Grep-friendly tags make the Android action → instance → account → event chain
easy to follow:

```text
[HOST_APP] onCreate -- initializing host CleverTap (Account A)
[HOST_CT]  Host CleverTap instance ready (Account A). accountId=...
[OTT_SDK]  initialize() -- OTT SDK registered with host app. CleverTap Account B instance NOT created yet
[OTT_SDK]  Initializing OTT CleverTap instance (Account B) accountId=... region=<default>
[OTT_SDK]  Opening OTT experience (OttActivity)
[HOST_CT]  Firing HOST_CUSTOM_EVENT -> CleverTap instance accountId=<A> props={...}
[OTT_CT]   Firing OTT_CONTENT_VIEWED -> CleverTap instance accountId=<B> props={...}
```

Every event log prints the **resolved** account id of the instance it is pushing
to, which is what makes leakage detectable locally rather than only in the
dashboard.

```bash
adb logcat -s HOST_APP:V HOST_CT:V OTT_SDK:V OTT_CT:V CleverTap:V
```

**Never logged:** account tokens (never printed at all) and full push tokens
(reduced to an `abcd1234...(len=163)` fingerprint — enough to compare two
instances, not enough to be a usable credential).

---

## 13 & 14. How to test Account A and Account B

### Build and install

```bash
./gradlew :app:installDebug \
  -PCLEVERTAP_ACCOUNT_A_ID=... -PCLEVERTAP_ACCOUNT_A_TOKEN=... \
  -PCLEVERTAP_ACCOUNT_B_ID=... -PCLEVERTAP_ACCOUNT_B_TOKEN=...
```

Start from a clean slate whenever you change credentials:

```bash
adb shell pm clear com.example.multisdk
```

### Procedure

1. **Launch the app.** Confirm in logcat that instance A is ready and that the
   OTT instance is explicitly *not* created yet.
2. **Log Instance Diagnostics.** Account A shows an id and CleverTap ID;
   Account B reports "not created yet". *Nothing has reached Account B.*
3. **Fire Host Event.** Log line must show Account A's id.
4. **Open OTT SDK.** Instance B is created; `OTT_OPENED` fires on B; the OTT
   screen appears. Expect Account B's own `App Launched` here.
5. **Fire the OTT events**, from the host screen and from the OTT screen.
6. **Diagnostics again.** Two different `accountId`s and two different
   `cleverTapId`s.
7. **Go back and fire the host event again** — still Account A.

### Verify in the dashboards

For each account, open **Analytics → Events** (and a live user profile) and check:

| Account A dashboard | Account B dashboard |
| --- | --- |
| `App Launched` ✅ | `App Launched` ✅ (Account B's own — expected, see §11) |
| `HOST_CUSTOM_EVENT` ✅ | `HOST_CUSTOM_EVENT` must be **absent** ❌ |
| `OTT_OPENED` / `OTT_CONTENT_VIEWED` / `OTT_PLAY` / `OTT_SUBSCRIPTION` must all be **absent** ❌ | all four present ✅ |

The test **passes** when each custom event appears in exactly one account, and the
two accounts show different CleverTap IDs. Automatic per-instance events in
Account B are expected and are not a failure.

---

## 15. Known limitations and CleverTap-specific assumptions

1. **Not compiled or run in this environment.** The sandbox this was authored in
   cannot reach `dl.google.com`, so neither the Android Gradle Plugin nor the
   Android SDK could be downloaded, and no `./gradlew` build or device run was
   performed. What *was* verified:
   - every CleverTap symbol used is confirmed present in the real
     `clevertap-android-sdk-8.4.1` artifact (checked with `javap` against the
     downloaded AAR);
   - all Java sources fully typecheck against that real AAR (`javac`, 0 errors),
     using generated stubs for `android.*`/`androidx.*` and `R` classes generated
     from the actual layout XML, so every `R.id` reference is confirmed to exist;
   - all 16 XML files are well-formed and every `@string`/`@style`/`@mipmap`/`@xml`
     reference resolves.

   **Please run `./gradlew :app:assembleDebug` once on a machine with the Android
   SDK before trusting the build**, and treat the dashboard results as the real
   verification of isolation.

2. **Push/FCM is not wired up.** No `google-services.json` and no
   `firebase-messaging` dependency are included: a real Firebase project cannot be
   committed, and inventing one would be fake credentials. The push-token analysis
   in §11 is derived from the SDK source, not from an observed device token. To
   test push for real, add your own `google-services.json`, the
   `com.google.gms.google-services` plugin and
   `com.google.firebase:firebase-messaging` to `:app`, then re-check diagnostics:
   both instances should report the same token *fingerprint* under different
   `cleverTapId`s. Per the brief, no token is faked or hand-generated anywhere.

3. **`App Launched` on Account B is expected, not suppressed.** See §11. This is
   the behaviour the brief asked to identify rather than hide.

4. **Automatic tracking is left at CleverTap's defaults for both instances.**
   `setDisableAppLaunchedEvent` and `setAnalyticsOnly` are documented and left
   commented out, so results reflect real SDK behaviour.

5. **Credentials are build-time constants.** Fine for a PoC. A production OTT SDK
   would still ship its account id/token inside the AAR (they are client-side
   credentials by design), but changing them requires a rebuild plus
   `adb shell pm clear`.

6. **Same-account misconfiguration is detected, not prevented.** If A and B are
   given the same account id, CleverTap returns the *same* instance for both and
   isolation is impossible; the SDK logs `MISCONFIGURED` but still returns that
   instance. Use two genuinely different CleverTap accounts.

7. **Single-process assumption.** Everything here assumes one process. A host app
   using `android:process` for extra components would create additional instance
   registries in those processes.

8. **Source-derived claims.** The mechanism claims in this README come from
   reading `clevertap-android-sdk` **8.4.1** sources (published
   `-sources.jar`). CleverTap's public documentation sites were unreachable from
   the authoring sandbox, so behaviour was established from the shipped source
   rather than from docs. Re-verify against the changelog if you move to a
   different SDK version.

---

## Evidence from the CleverTap SDK source

Everything asserted above traces to `clevertap-android-sdk:8.4.1`:

| Claim | Source location |
| --- | --- |
| Official multi-instance API | `CleverTapInstanceConfig.createInstance(...)`, `CleverTapAPI.instanceWithConfig(...)` |
| Instances are registered by account id | `CleverTapAPI.instanceWithConfig` → `instances.get(config.getAccountId())` |
| Per-account local storage namespace | `StorageHelper.storageKeyWithSuffix()` → `"$key:$accountID"` |
| Per-account CleverTap ID | `DeviceInfo` → `Constants.DEVICE_ID_TAG + ":" + config.getAccountId()` |
| Lifecycle callbacks fan out to every instance | `CleverTapAPI.onActivityResumed()` loop over `instances.keySet()` |
| App Launched is gated per instance | `ActivityLifeCycleManager.activityResumed()` → `coreMetaData.isAppLaunchPushed()` |
| App Launched can be disabled per instance | `AnalyticsManager.pushAppLaunchedEvent()` → `config.isDisableAppLaunchedEvent()` |
| Per-account push token cache | `PushProviders.cacheToken()` → `putStringImmediate(context, config.getAccountId(), ...)` |
| Inbound push routed by account id | `CleverTapAPI.fromAccountId()` + `Constants.WZRK_ACCT_ID_KEY` (`wzrk_acct_id`) |
| Blank region is ignored | `CtApi.kt` → `region.isNotNullAndBlank()` |
| Components the AAR contributes | the AAR's own `AndroidManifest.xml` |
