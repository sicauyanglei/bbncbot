# OCR 模块 ProGuard 规则
# 目前 isMinifyEnabled=false，预留规则以备后续启用

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
