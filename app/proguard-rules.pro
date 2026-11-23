# 数据模型（Room 反射访问）
-keep class com.shanqiu.pickupmanager.data.model.** { *; }
-keep @androidx.room.Entity class *
-keep class * extends androidx.room.RoomDatabase

# 桌面小部件的 RemoteViews 通过类名反射实例化，不可混淆
-keep class * extends android.appwidget.AppWidgetProvider

# 保留协程调度器工厂
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# release 包剥离调试日志
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# 堆栈可读性
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
