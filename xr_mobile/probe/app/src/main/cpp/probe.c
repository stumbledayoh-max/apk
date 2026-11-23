#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>
#define XR_USE_PLATFORM_ANDROID
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

JNIEXPORT jstring JNICALL Java_com_xrpocket_probe_ProbeActivity_probe(JNIEnv *env, jclass cls, jobject activity) {
    (void)cls;
    JavaVM *vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) return (*env)->NewStringUTF(env, "GetJavaVM failed");
    PFN_xrInitializeLoaderKHR init = NULL;
    XrResult result = xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction *)&init);
    if (XR_FAILED(result) || init == NULL) {
        char msg[100]; snprintf(msg, sizeof(msg), "loader lookup: %d", result);
        return (*env)->NewStringUTF(env, msg);
    }
    XrLoaderInitInfoAndroidKHR loader = {XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    loader.applicationVM = vm; loader.applicationContext = activity;
    result = init((const XrLoaderInitInfoBaseHeaderKHR *)&loader);
    __android_log_print(ANDROID_LOG_INFO, "XRProbe", "LOADER_INIT %d", result);
    if (XR_FAILED(result)) { char msg[100]; snprintf(msg, sizeof(msg), "loader init: %d", result); return (*env)->NewStringUTF(env, msg); }
    XrInstanceCreateInfoAndroidKHR android_info = {XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    android_info.applicationVM = vm; android_info.applicationActivity = activity;
    const char *extensions[] = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
    XrInstanceCreateInfo create = {XR_TYPE_INSTANCE_CREATE_INFO};
    create.next = &android_info;
    strcpy(create.applicationInfo.applicationName, "XR Pocket Probe");
    strcpy(create.applicationInfo.engineName, "test");
    create.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    create.enabledExtensionCount = 1; create.enabledExtensionNames = extensions;
    XrInstance instance = XR_NULL_HANDLE;
    result = xrCreateInstance(&create, &instance);
    __android_log_print(ANDROID_LOG_INFO, "XRProbe", "CREATE_INSTANCE %d", result);
    if (XR_FAILED(result)) { char msg[100]; snprintf(msg, sizeof(msg), "create instance: %d", result); return (*env)->NewStringUTF(env, msg); }
    XrSystemGetInfo get = {XR_TYPE_SYSTEM_GET_INFO}; get.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId id = XR_NULL_SYSTEM_ID;
    XrResult system = xrGetSystem(instance, &get, &id);
    __android_log_print(ANDROID_LOG_INFO, "XRProbe", "GET_SYSTEM %d", system);
    xrDestroyInstance(instance);
    char msg[140]; snprintf(msg, sizeof(msg), "instance OK; getSystem=%d", system);
    return (*env)->NewStringUTF(env, msg);
}
