#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#define XR_USE_PLATFORM_ANDROID
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

/* Exercise destruction AND re-creation: an instance-only smoke test can pass
 * while a detached runtime thread crashes a moment after dlclose. */
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

    for (int cycle = 1; cycle <= 3; ++cycle) {
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
        __android_log_print(ANDROID_LOG_INFO, "XRProbe", "CREATE_INSTANCE cycle=%d result=%d", cycle, result);
        if (XR_FAILED(result)) { char msg[100]; snprintf(msg, sizeof(msg), "create cycle %d: %d", cycle, result); return (*env)->NewStringUTF(env, msg); }
        XrSystemGetInfo get = {XR_TYPE_SYSTEM_GET_INFO}; get.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XrSystemId id = XR_NULL_SYSTEM_ID;
        XrResult system = xrGetSystem(instance, &get, &id);
        __android_log_print(ANDROID_LOG_INFO, "XRProbe", "GET_SYSTEM cycle=%d result=%d", cycle, system);
        XrResult destroyed = xrDestroyInstance(instance);
        __android_log_print(ANDROID_LOG_INFO, "XRProbe", "DESTROY_INSTANCE cycle=%d result=%d", cycle, destroyed);
        if (XR_FAILED(system) || XR_FAILED(destroyed)) {
            char msg[120]; snprintf(msg, sizeof(msg), "cycle %d: system=%d destroy=%d", cycle, system, destroyed);
            return (*env)->NewStringUTF(env, msg);
        }
        sleep(3); // catch asynchronous crashes before the next cycle
    }
    __android_log_print(ANDROID_LOG_INFO, "XRProbe", "ALL_CYCLES_OK");
    return (*env)->NewStringUTF(env, "3 instances created and destroyed successfully");
}
