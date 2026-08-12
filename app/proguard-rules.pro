# FMZlinkR release shrinker rules.
# Keep readable stack traces during the initial device-validation phase.
-dontobfuscate
-keepattributes LineNumberTable,SourceFile,Signature,InnerClasses,EnclosingMethod

# Shizuku creates this UserService from its class name in another process.
-keep class com.fumizo07.fmzlinkr.services.shell.ShellService { *; }

# Strip verbose platform/custom logging only.
-assumenosideeffects class android.util.Log {
    v(...);
}
-assumenosideeffects class com.fumizo07.fmzlinkr.utils.AppLogger {
    v(...);
}
