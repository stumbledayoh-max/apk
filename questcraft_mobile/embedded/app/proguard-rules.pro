# This build is merged into an existing APK. Keep component and JNI names stable,
# but relocate unrelated dependency internals away from the game's classes.
-repackageclasses 'com.questcraft.mobile.internal'
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.questcraft.mobile.** { *; }
-keep class org.freedesktop.monado.ipc.MonadoService { *; }
-keep class org.freedesktop.monado.auxiliary.ActivityLifecycleListener { *; }
-keep class org.freedesktop.monado.auxiliary.SystemUiController { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn org.freedesktop.monado.ipc.**
