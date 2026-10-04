# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# FixLog keep rules (isMinifyEnabled=true)
# ---------------------------------------------------------------------------

# 크래시 스택트레이스에서 원본 라인/파일을 유지(디버깅용).
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# --- WebView JS 브리지: @JavascriptInterface 메서드는 리플렉션으로 호출되므로 보존 ---
-keepclassmembers class kr.co.fixlog.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Moshi (KotlinJsonAdapterFactory: 리플렉션 기반) ---
# DTO 필드/생성자가 난독화되면 JSON 매핑이 깨진다. DTO 패키지 전체 보존.
-keep class kr.co.fixlog.data.remote.dto.** { *; }
-keepclassmembers class kr.co.fixlog.data.remote.dto.** { *; }

# Moshi 내부 규칙
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keepnames @kotlin.Metadata class kr.co.fixlog.**
-keep class kotlin.Metadata { *; }
-dontwarn org.jetbrains.annotations.**

# Kotlin reflection(Moshi kotlin-reflect 경로) 보존
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class kotlin.jvm.internal.** { *; }

# --- OkHttp / Okio (consumer rules 존재하나 경고 억제) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Tink / security-crypto (EncryptedSharedPreferences) ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**