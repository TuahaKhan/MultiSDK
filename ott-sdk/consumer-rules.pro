# ProGuard/R8 rules exported to any app that consumes the OTT SDK.
# The OTT SDK's public entry point must survive shrinking in the host app.
-keep public class com.example.ottsdk.OttSdk { public *; }
-keep public class com.example.ottsdk.OttSdkConfig { public *; }
