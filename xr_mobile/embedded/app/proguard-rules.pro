# This build is merged into an existing APK. Keep component and JNI names stable,
# but relocate unrelated dependency internals away from the game's classes.
-repackageclasses 'com.bigxr.embedded.internal'
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keep class com.bigxr.embedded.** { *; }
-keep class org.freedesktop.monado.ipc.MonadoService { *; }
-keep class org.freedesktop.monado.auxiliary.ActivityLifecycleListener { *; }
-keep class org.freedesktop.monado.auxiliary.SystemUiController { *; }
-keep class com.google.mediapipe.** { *; }
# MediaPipe's Graph initializes Flogger using a caller-sensitive stack walk.
# R8 inlining/repackaging breaks that lookup, and protobuf uses reflection.
-keep class com.google.common.flogger.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn org.freedesktop.monado.ipc.**
# These optional profiler/template types are not packaged by MediaPipe's AAR
# and are not used by the HandLandmarker path.
-dontwarn com.google.auto.value.extension.memoized.Memoized
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
