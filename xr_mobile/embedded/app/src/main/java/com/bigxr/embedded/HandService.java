package com.bigxr.embedded;

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
import android.graphics.ImageFormat;
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

import com.bigxr.olfx.HandFrameClock;
import com.bigxr.olfx.HandImageGeometry;
import com.bigxr.olfx.HandRouting;
import com.bigxr.olfx.MediaPipeRouting;
import com.bigxr.olfx.Yuv420;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Camera-to-controller bridge contained in the same APK as a game. */
public final class HandService extends Service {
    public static final String ACTION_STOPPED = "com.bigxr.embedded.HANDS_STOPPED";
    private static final String ACTION_STOP = "com.bigxr.embedded.STOP_HANDS";
    private static final String TAG = "BigXRHands";
    private static final String CHANNEL = "bigxr_hands";
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
    private boolean cameraTimestampRealtime, loggedFirstFrame;
    private int frameFailures;
    private final HandRouting routing = new HandRouting();

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
                    .setContentTitle("BigXR: mãos ativas")
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
            worker = new HandlerThread("BigXRHandsCamera");
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
        Integer source = info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        cameraTimestampRealtime = source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Sem formato de câmera compatível");
        Size size = null;
        long best = Long.MAX_VALUE;
        for (Size candidate : sizes) {
            long pixels = (long) candidate.getWidth() * candidate.getHeight();
            if (candidate.getWidth() <= 0 || candidate.getHeight() <= 0 || candidate.getWidth() > 4096
                    || candidate.getHeight() > 4096 || pixels > Yuv420.MAX_FRAME_PIXELS) continue;
            long score = Math.abs(pixels - 640L * 480L);
            if (score < best) { best = score; size = candidate; }
        }
        if (size == null) throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Quadros da câmera excedem o limite de memória");
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
            int width = image.getWidth(), height = image.getHeight();
            long monotonicNow = System.nanoTime();
            long frameNs = HandFrameClock.normalizeTimestampNs(image.getTimestamp(), monotonicNow,
                    SystemClock.elapsedRealtimeNanos(), cameraTimestampRealtime);
            bitmap = toBitmap(image);
            image.close(); image = null;
            int rotation = displayRotation();
            mpImage = new BitmapImageBuilder(bitmap).build();
            ImageProcessingOptions imageOptions = ImageProcessingOptions.builder().setRotationDegrees(rotation).build();
            long timestamp = HandFrameClock.nextMediaPipeTimestampMs(lastTimestamp, frameNs);
            lastTimestamp = timestamp;
            HandLandmarkerResult result = landmarker.detectForVideo(mpImage, imageOptions, timestamp);
            HandRouting.Hand[] hands = MediaPipeRouting.route(routing, result, rotation, width, height, monotonicNow);
            HandsMath.State nextLeft = new HandsMath.State(), nextRight = new HandsMath.State();
            for (int side = 0; side < hands.length; side++) {
                HandRouting.Hand hand = hands[side];
                if (hand == null) continue;
                HandsMath.State state = HandsMath.classify(hand.x, hand.y, hand.z, side == HandRouting.LEFT,
                        hand.metricScaleX, hand.metricScaleY);
                state.trigger = hand.trigger;
                if (side == HandRouting.LEFT) nextLeft = state; else nextRight = state;
            }
            if (closing) return;
            left = nextLeft; right = nextRight; lastResult = now;
            frameFailures = 0;
            if (!loggedFirstFrame) { Log.i(TAG, "OLFX_HAND_PIPELINE_FRAME_OK"); loggedFirstFrame = true; }
        } catch (Throwable error) {
            Log.e(TAG, "Falha ao processar quadro da câmera", error);
            if (++frameFailures >= 10 && !closing) {
                closing = true; left = new HandsMath.State(); right = new HandsMath.State();
                sendBroadcast(new Intent(ACTION_STOPPED).setPackage(getPackageName()));
                Log.e(TAG, "Câmera parada após falhas repetidas na base OLFX");
                stopSelf();
            }
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
        return HandImageGeometry.clockwiseRotation(sensorOrientation, degrees);
    }

    private static Bitmap toBitmap(Image image) {
        int width = image.getWidth(), height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) throw new IllegalArgumentException("Formato da câmera não é YUV_420_888");
        int[] argb = Yuv420.toArgb(width, height,
                planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride());
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
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
