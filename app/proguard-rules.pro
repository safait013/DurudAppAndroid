# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep the Application class
-keep public class com.darood.app.App extends android.app.Application

# Keep native bridge classes exposed to JavaScript
-keepclassmembers class com.darood.app.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Room entities and DAOs
-keep class com.darood.app.** extends androidx.room.Entity
-keep class com.darood.app.** extends androidx.room.Dao

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
