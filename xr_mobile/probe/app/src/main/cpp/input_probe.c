/* Headless diagnostic only. Sends synthetic local hand-controller packets and
 * reads OpenXR actions and head orientation, without rendering or real hands. */
#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#define XR_USE_PLATFORM_ANDROID
#define XR_USE_TIMESPEC
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "XRProbe", __VA_ARGS__)
#define CHECK(call) do { \
    status = (call); \
    if (XR_FAILED(status)) { LOG("INPUT_ERROR %s result=%d", #call, status); goto cleanup; } \
} while (0)

static void send_hand(int socket_fd, const struct sockaddr_in *destination, int pressed) {
    char data[256];
    int n = snprintf(data, sizeof(data),
        "PH5 1 %d 0 0 0.2 0.5 0.2 0 0 0 1 0 0 0 "
        "0 0 0 0 0.5 0.5 0.5 0 0 0 1 0 0 0 1", pressed);
    if (n > 0 && (size_t)n < sizeof(data)) {
        (void)sendto(socket_fd, data, (size_t)n, 0,
                     (const struct sockaddr *)destination, sizeof(*destination));
    }
}

static int input_probe(JavaVM *vm, jobject activity) {
    XrResult status = XR_SUCCESS;
    XrInstance instance = XR_NULL_HANDLE;
    XrSession session = XR_NULL_HANDLE;
    XrActionSet action_set = XR_NULL_HANDLE;
    XrAction trigger = XR_NULL_HANDLE, pose = XR_NULL_HANDLE;
    XrSpace hand_space = XR_NULL_HANDLE, local_space = XR_NULL_HANDLE, view_space = XR_NULL_HANDLE;
    int socket_fd = -1, started = 0, focused = 0, saw_press = 0, saw_release = 0;
    int saw_hand_pose = 0, saw_head_motion = 0, have_head = 0, success = 0;
    XrQuaternionf initial_head = {0};
    PFN_xrConvertTimespecTimeToTimeKHR convert_time = NULL;
    XrSessionState session_state = XR_SESSION_STATE_UNKNOWN;

    XrInstanceCreateInfoAndroidKHR android_info = {XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    android_info.applicationVM = vm; android_info.applicationActivity = activity;
    const char *extensions[] = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        XR_MND_HEADLESS_EXTENSION_NAME, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME};
    XrInstanceCreateInfo create = {XR_TYPE_INSTANCE_CREATE_INFO};
    create.next = &android_info;
    strcpy(create.applicationInfo.applicationName, "XR Input Probe");
    strcpy(create.applicationInfo.engineName, "headless test");
    create.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    create.enabledExtensionCount = 3; create.enabledExtensionNames = extensions;
    CHECK(xrCreateInstance(&create, &instance));
    CHECK(xrGetInstanceProcAddr(instance, "xrConvertTimespecTimeToTimeKHR", (PFN_xrVoidFunction *)&convert_time));
    if (convert_time == NULL) { LOG("INPUT_ERROR missing time conversion"); goto cleanup; }
    XrSystemGetInfo sys = {XR_TYPE_SYSTEM_GET_INFO}; sys.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId system_id = XR_NULL_SYSTEM_ID;
    CHECK(xrGetSystem(instance, &sys, &system_id));
    XrPath user, trigger_path, pose_path, touch_profile;
    CHECK(xrStringToPath(instance, "/user/hand/left", &user));
    CHECK(xrStringToPath(instance, "/user/hand/left/input/trigger/value", &trigger_path));
    CHECK(xrStringToPath(instance, "/user/hand/left/input/grip/pose", &pose_path));
    CHECK(xrStringToPath(instance, "/interaction_profiles/oculus/touch_controller", &touch_profile));
    XrActionSetCreateInfo set_info = {XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(set_info.actionSetName, "probe"); strcpy(set_info.localizedActionSetName, "Probe");
    CHECK(xrCreateActionSet(instance, &set_info, &action_set));
    XrActionCreateInfo action_info = {XR_TYPE_ACTION_CREATE_INFO};
    strcpy(action_info.actionName, "trigger"); strcpy(action_info.localizedActionName, "Trigger");
    action_info.actionType = XR_ACTION_TYPE_FLOAT_INPUT;
    action_info.countSubactionPaths = 1; action_info.subactionPaths = &user;
    CHECK(xrCreateAction(action_set, &action_info, &trigger));
    strcpy(action_info.actionName, "grip_pose"); strcpy(action_info.localizedActionName, "Grip pose");
    action_info.actionType = XR_ACTION_TYPE_POSE_INPUT;
    CHECK(xrCreateAction(action_set, &action_info, &pose));
    XrActionSuggestedBinding bindings[] = {{trigger, trigger_path}, {pose, pose_path}};
    XrInteractionProfileSuggestedBinding suggestion = {XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggestion.interactionProfile = touch_profile;
    suggestion.countSuggestedBindings = 2; suggestion.suggestedBindings = bindings;
    CHECK(xrSuggestInteractionProfileBindings(instance, &suggestion));
    XrSessionCreateInfo session_info = {XR_TYPE_SESSION_CREATE_INFO}; session_info.systemId = system_id;
    CHECK(xrCreateSession(instance, &session_info, &session));
    XrSessionActionSetsAttachInfo attach = {XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1; attach.actionSets = &action_set;
    CHECK(xrAttachSessionActionSets(session, &attach));
    XrActionSpaceCreateInfo action_space_info = {XR_TYPE_ACTION_SPACE_CREATE_INFO};
    action_space_info.action = pose; action_space_info.subactionPath = user;
    action_space_info.poseInActionSpace.orientation.w = 1;
    CHECK(xrCreateActionSpace(session, &action_space_info, &hand_space));
    XrReferenceSpaceCreateInfo ref = {XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    ref.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL; ref.poseInReferenceSpace.orientation.w = 1;
    CHECK(xrCreateReferenceSpace(session, &ref, &local_space));
    ref.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    CHECK(xrCreateReferenceSpace(session, &ref, &view_space));
    socket_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (socket_fd < 0) { LOG("INPUT_ERROR could not create UDP socket"); goto cleanup; }
    struct sockaddr_in destination = {0};
    destination.sin_family = AF_INET; destination.sin_port = htons(42424);
    destination.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    XrActiveActionSet active = {action_set, XR_NULL_PATH};
    XrActionsSyncInfo sync = {XR_TYPE_ACTIONS_SYNC_INFO}; sync.countActiveActionSets = 1; sync.activeActionSets = &active;
    XrActionStateGetInfo state_info = {XR_TYPE_ACTION_STATE_GET_INFO}; state_info.action = trigger; state_info.subactionPath = user;

    /* Headless sessions transition to FOCUSED without calling xrWaitFrame. */
    for (int iteration = 0; iteration < 750; ++iteration) {
        XrEventDataBuffer event = {XR_TYPE_EVENT_DATA_BUFFER};
        while (xrPollEvent(instance, &event) == XR_SUCCESS) {
            if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                session_state = ((XrEventDataSessionStateChanged *)&event)->state;
                LOG("INPUT_SESSION_STATE %d", session_state);
                if (session_state == XR_SESSION_STATE_READY && !started) {
                    XrSessionBeginInfo begin = {XR_TYPE_SESSION_BEGIN_INFO};
                    begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    CHECK(xrBeginSession(session, &begin)); started = 1;
                }
                focused = session_state == XR_SESSION_STATE_FOCUSED;
            }
            memset(&event, 0, sizeof(event)); event.type = XR_TYPE_EVENT_DATA_BUFFER;
        }
        send_hand(socket_fd, &destination, !saw_press);
        if (focused) {
            status = xrSyncActions(session, &sync);
            if (XR_FAILED(status)) { LOG("INPUT_ERROR sync result=%d", status); goto cleanup; }
            XrActionStateFloat state = {XR_TYPE_ACTION_STATE_FLOAT};
            CHECK(xrGetActionStateFloat(session, &state_info, &state));
            if (state.isActive && state.currentState > 0.9f && !saw_press) {
                saw_press = 1; LOG("INPUT_PRESS_OK %.3f", state.currentState);
            } else if (saw_press && state.isActive && state.currentState < 0.1f && !saw_release) {
                saw_release = 1; LOG("INPUT_RELEASE_OK %.3f", state.currentState);
            }
            struct timespec now;
            if (clock_gettime(CLOCK_MONOTONIC, &now) == 0) {
                XrTime time = 0;
                CHECK(convert_time(instance, &now, &time));
                XrSpaceLocation hand = {XR_TYPE_SPACE_LOCATION};
                status = xrLocateSpace(hand_space, local_space, time, &hand);
                if (XR_SUCCEEDED(status) && (hand.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)
                        && (hand.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) && !saw_hand_pose) {
                    saw_hand_pose = 1; LOG("INPUT_POSE_OK %.3f %.3f %.3f", hand.pose.position.x, hand.pose.position.y, hand.pose.position.z);
                }
                XrSpaceLocation head = {XR_TYPE_SPACE_LOCATION};
                status = xrLocateSpace(view_space, local_space, time, &head);
                if (XR_SUCCEEDED(status) && (head.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
                    XrQuaternionf q = head.pose.orientation;
                    if (!have_head) { initial_head = q; have_head = 1; LOG("INPUT_GYRO_BASELINE"); }
                    else {
                        float dot = fabsf(q.x*initial_head.x + q.y*initial_head.y + q.z*initial_head.z + q.w*initial_head.w);
                        // About 11 degrees of rotation, not just numerical drift.
                        if (dot < 0.995f && !saw_head_motion) {
                            saw_head_motion = 1; LOG("INPUT_HEAD_MOTION_OK dot=%.6f", dot);
                        }
                    }
                }
            }
            if (saw_press && saw_release && saw_hand_pose && saw_head_motion) { success = 1; break; }
        }
        struct timespec pause = {0, 20000000}; nanosleep(&pause, NULL);
    }
    LOG("INPUT_SUMMARY press=%d release=%d handPose=%d headMotion=%d focused=%d", saw_press, saw_release,
        saw_hand_pose, saw_head_motion, focused);
    if (success) LOG("INPUTS_OK");

cleanup:
    if (socket_fd >= 0) close(socket_fd);
    if (session != XR_NULL_HANDLE && started) {
        if (XR_SUCCEEDED(xrRequestExitSession(session))) {
            for (int n = 0; n < 50 && session_state != XR_SESSION_STATE_STOPPING; ++n) {
                XrEventDataBuffer event = {XR_TYPE_EVENT_DATA_BUFFER};
                while (xrPollEvent(instance, &event) == XR_SUCCESS) {
                    if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED)
                        session_state = ((XrEventDataSessionStateChanged *)&event)->state;
                    memset(&event, 0, sizeof(event)); event.type = XR_TYPE_EVENT_DATA_BUFFER;
                }
                struct timespec pause = {0, 20000000}; nanosleep(&pause, NULL);
            }
            if (session_state == XR_SESSION_STATE_STOPPING) (void)xrEndSession(session);
        }
    }
    if (hand_space != XR_NULL_HANDLE) (void)xrDestroySpace(hand_space);
    if (local_space != XR_NULL_HANDLE) (void)xrDestroySpace(local_space);
    if (view_space != XR_NULL_HANDLE) (void)xrDestroySpace(view_space);
    if (session != XR_NULL_HANDLE) (void)xrDestroySession(session);
    if (action_set != XR_NULL_HANDLE) (void)xrDestroyActionSet(action_set);
    if (instance != XR_NULL_HANDLE) (void)xrDestroyInstance(instance);
    return success;
}

JNIEXPORT jstring JNICALL Java_com_xrpocket_probe_ProbeActivity_probeInput(JNIEnv *env, jclass cls, jobject activity) {
    (void)cls;
    JavaVM *vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) return (*env)->NewStringUTF(env, "GetJavaVM failed");
    int ok = input_probe(vm, activity);
    return (*env)->NewStringUTF(env, ok ? "synthetic inputs and virtual head motion OK" : "input test failed; see log");
}
