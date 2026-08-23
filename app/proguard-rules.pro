# Relay LSPosed Module ProGuard Rules

# Keep the Xposed module entry point
-keep class cn.reddragon.relay.RelayModule { *; }

# Keep data models used for JSON serialization
-keep class cn.reddragon.relay.model.NotificationData { *; }

# libxposed API is compileOnly — don't warn about missing classes
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.api.** { *; }
