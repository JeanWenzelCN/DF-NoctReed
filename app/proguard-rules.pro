# Shizuku 使用反射调用 Shizuku.newProcess，必须保留
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# 保留 Kotlin Metadata 以便反射正常
-keep class kotlin.Metadata { *; }

# 保留 Compose 运行时
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
