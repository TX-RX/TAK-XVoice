# =====================================================================
# XV ATAK plugin ProGuard rules.
#
# Mirrors the bridge's verified setup. The CRITICAL line is
# `-applymapping <atak.proguard.mapping>` — the takdev gradle plugin
# substitutes the placeholder with the path to ATAK's published mapping.
# Without this, our compiled bytecode keeps SDK class references
# unobfuscated (e.g. gov.tak.api.plugin.IServiceController), but Play
# Store ATAK 5.6.0.12 ships those interfaces under their obfuscated
# names (e.g. gov.tak.api.plugin.a) and reflective construction fails
# with NoClassDefFoundError.
# =====================================================================


# ----- System Section (do not modify) --------------------------------

-dontskipnonpubliclibraryclasses
-dontshrink
-dontoptimize

-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

-applymapping <atak.proguard.mapping>

-keepattributes *Annotation*
-keepattributes Signature, InnerClasses

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    @org.simpleframework.xml.* *;
}

-keep class * implements gov.tak.api.plugin.IPlugin {
}
-keep class * extends com.atak.plugins.impl.AbstractPluginTool {
}

-keep class module-info

-keep,allowobfuscation @interface gov.tak.api.annotation.DontObfuscate
-keep @gov.tak.api.annotation.DontObfuscate class * {*;}
-keepclassmembers class * {
    @gov.tak.api.annotation.DontObfuscate *;
}


# ----- User Section --------------------------------------------------

# Unique repackage namespace so XV's obfuscated classes don't collide
# with other plugins loaded into the same ATAK classloader.
-repackageclasses 'atakplugin.xv'

-dontwarn com.atakmap.**
-dontwarn atakplugin.**
-dontwarn gov.tak.**

# protobuf-javalite reflects on generated message fields by name. The
# Mumble wire protocol classes (Version, Authenticate, UserState, …)
# extend GeneratedMessageLite and carry fields named with a trailing
# underscore (`version_`, `comment_`, …). Without this rule R8 renames
# those fields and the first serialization on TLS handshake throws
# "Field version_ for atakplugin.xv.G2$D not found" — every connection
# dies before sendVersion completes (verified 2026-05-12 on
# TPP-signed release APK against tak.example.com:64738).
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
