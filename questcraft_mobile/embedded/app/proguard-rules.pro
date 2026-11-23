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
# These optional profiler/template types are not packaged by MediaPipe's AAR
# and are not used by the HandLandmarker path.
-dontwarn com.google.auto.value.extension.memoized.Memoized
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
