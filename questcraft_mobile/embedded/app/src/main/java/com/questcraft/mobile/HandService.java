package com.questcraft.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Camera-to-controller bridge contained in the same APK as QuestCraft. */
public final class HandService extends Service {
    public static final String ACTION_STOPPED = "com.questcraft.mobile.HANDS_STOPPED";
    private static final String ACTION_STOP = "com.questcraft.mobile.STOP_HANDS";
    private static final String TAG = "QuestCraftHands";
    private static final String CHANNEL = "questcraft_hands";
    private static final int NOTIFICATION_ID = 21401;
    private HandlerThread worker;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandLandmarker landmarker;
    private DatagramSocket socket;
    private ScheduledExecutorService sender;
    private InetAddress loopback;
    private volatile HandsMath.State left = new HandsMath.State();
    private volatile HandsMath.State right = new HandsMath.State();
    private volatile long lastResult;
    private volatile boolean closing;
    private long lastFrame;
    private long lastTimestamp;
    private int sensorOrientation;

    @Override public void onCreate() {
        super.onCreate();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Câmera não permitida");
            stopSelf();
            return;
        }
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Rastreamento de mãos", NotificationManager.IMPORTANCE_LOW));
            Intent stop = new Intent(this, HandService.class).setAction(ACTION_STOP);
            PendingIntent pending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("QuestCraft: mãos ativas")
                    .setContentText("A câmera está em uso enquanto o jogo está aberto.")
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "Parar câmera", pending).build())
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            else startForeground(NOTIFICATION_ID, notification);
            socket = new DatagramSocket();
            loopback = InetAddress.getByName("127.0.0.1");
            sender = Executors.newSingleThreadScheduledExecutor();
            sender.scheduleAtFixedRate(this::sendState, 0, 40, TimeUnit.MILLISECONDS);
            worker = new HandlerThread("QuestCraftHandsCamera");
            worker.start();
            handler = new Handler(worker.getLooper());
            handler.post(this::initialize);
        } catch (Exception error) {
            Log.e(TAG, "Não foi possível iniciar o rastreamento", error);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            sendBroadcast(new Intent(ACTION_STOPPED).setPackage(getPackageName()));
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void initialize() {
        if (closing) return;
        try {
            BaseOptions base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.VIDEO).setNumHands(2)
                    .setMinHandDetectionConfidence(0.45f).setMinHandPresenceConfidence(0.45f)
                    .setMinTrackingConfidence(0.35f).build();
            landmarker = HandLandmarker.createFromOptions(this, options);
            Log.i(TAG, "Modelo de mãos carregado");
            openCamera();
        } catch (Throwable error) {
            Log.e(TAG, "Falha ao carregar rastreamento ou câmera", error);
            stopSelf();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void openCamera() throws CameraAccessException {
        if (closing) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        String selected = null;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { selected = id; break; }
        }
        if (selected == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Sem câmera traseira");
        CameraCharacteristics info = manager.getCameraCharacteristics(selected);
        Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = orientation == null ? 0 : orientation;
        StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Sem formato de câmera compatível");
        Size size = sizes[0];
        long best = Long.MAX_VALUE;
        for (Size candidate : sizes) {
            long pixels = (long) candidate.getWidth() * candidate.getHeight();
            long score = Math.abs(pixels - 640L * 480L);
            if (score < best) { best = score; size = candidate; }
        }
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onFrame, handler);
        manager.openCamera(selected, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice device) {
                if (closing) { device.close(); return; }
                camera = device;
                try {
                    Surface surface = reader.getSurface();
                    device.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (closing) { configured.close(); return; }
                            session = configured;
                            try {
                                CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                request.addTarget(surface);
                                configured.setRepeatingRequest(request.build(), null, handler);
                                Log.i(TAG, "Câmera de mãos iniciada");
                            } catch (CameraAccessException | IllegalStateException error) {
                                Log.e(TAG, "Falha ao capturar imagens", error); stopSelf();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession configured) {
                            Log.e(TAG, "Falha ao configurar câmera"); stopSelf();
                        }
                    }, handler);
                } catch (CameraAccessException | IllegalStateException error) {
                    Log.e(TAG, "Falha ao abrir sessão da câmera", error); stopSelf();
                }
            }
            @Override public void onDisconnected(CameraDevice device) { device.close(); stopSelf(); }
            @Override public void onError(CameraDevice device, int error) { device.close(); Log.e(TAG, "Erro de câmera: " + error); stopSelf(); }
        }, handler);
    }

    private void onFrame(ImageReader source) {
        Image image = null;
        Bitmap bitmap = null;
        MPImage mpImage = null;
        try {
            image = source.acquireLatestImage();
            if (image == null || closing || landmarker == null) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrame < 65) return;
            lastFrame = now;
            bitmap = toBitmap(image);
            image.close(); image = null;
            if (bitmap == null) return;
            int rotation = displayRotation();
            mpImage = new BitmapImageBuilder(bitmap).build();
            ImageProcessingOptions imageOptions = ImageProcessingOptions.builder().setRotationDegrees(rotation).build();
            long timestamp = Math.max(now, lastTimestamp + 1);
            lastTimestamp = timestamp;
            HandLandmarkerResult result = landmarker.detectForVideo(mpImage, imageOptions, timestamp);
            HandsMath.State nextLeft = new HandsMath.State(), nextRight = new HandsMath.State();
            List<List<NormalizedLandmark>> hands = result.landmarks();
            for (int hand = 0; hand < hands.size(); hand++) {
                List<NormalizedLandmark> points = hands.get(hand);
                if (points.size() < 21) continue;
                float[] x = new float[21], y = new float[21], z = new float[21];
                for (int i = 0; i < 21; i++) {
                    x[i] = points.get(i).x(); y[i] = points.get(i).y(); z[i] = points.get(i).z();
                }
                // MediaPipe reports handedness assuming a mirrored selfie image;
                // the rear-camera frames we provide are not mirrored.
                boolean physicalLeft = hand < result.handednesses().size()
                        && !result.handednesses().get(hand).isEmpty()
                        && "Right".equalsIgnoreCase(result.handednesses().get(hand).get(0).categoryName());
                HandsMath.State state = HandsMath.classify(x, y, z, physicalLeft);
                if (physicalLeft) nextLeft = state; else nextRight = state;
            }
            left = nextLeft; right = nextRight; lastResult = now;
        } catch (Throwable error) {
            Log.e(TAG, "Falha ao processar quadro da câmera", error);
        } finally {
            if (image != null) image.close();
            if (mpImage != null) mpImage.close();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private int displayRotation() {
        WindowManager manager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int rotation = manager.getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
        return (sensorOrientation - degrees + 360) % 360;
    }

    private static Bitmap toBitmap(Image image) {
        int width = image.getWidth(), height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] nv21 = new byte[width * height * 3 / 2];
        copyPlane(planes[0], width, height, nv21, 0, 1);
        int offset = width * height;
        copyPlane(planes[2], width / 2, height / 2, nv21, offset, 2); // V
        copyPlane(planes[1], width / 2, height / 2, nv21, offset + 1, 2); // U
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(new Rect(0, 0, width, height), 75, bytes);
        byte[] jpeg = bytes.toByteArray();
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    private static void copyPlane(Image.Plane plane, int width, int height, byte[] output, int offset, int outputStride) {
        ByteBuffer buffer = plane.getBuffer();
        int base = buffer.position(), rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                output[offset++] = buffer.get(base + row * rowStride + column * pixelStride);
                offset += outputStride - 1;
            }
        }
    }

    private void sendState() {
        try {
            HandsMath.State a = left, b = right;
            if (SystemClock.elapsedRealtime() - lastResult > 500) { a = new HandsMath.State(); b = new HandsMath.State(); }
            byte[] data = HandsMath.packet(a, b).getBytes(StandardCharsets.US_ASCII);
            if (socket != null && !socket.isClosed()) socket.send(new DatagramPacket(data, data.length, loopback, 42424));
        } catch (Exception error) {
            if (!closing) Log.w(TAG, "Falha ao enviar estado local", error);
        }
    }

    @Override public void onDestroy() {
        closing = true;
        if (sender != null) sender.shutdownNow();
        if (socket != null) socket.close();
        if (handler != null) handler.post(() -> {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
            if (landmarker != null) landmarker.close();
            if (worker != null) worker.quitSafely();
        });
        super.onDestroy();
    }
}
