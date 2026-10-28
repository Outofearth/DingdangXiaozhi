package com.altc.xiaozhi;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 用 Camera1 API 拍照并输出 JPEG 字节。
 *
 * 本机（MT8167 智能屏）只有一个前置摄像头：Camera 0 / CAMERA_FACING_FRONT /
 * sensor orientation 90，不支持 Camera2，所以统一走 Camera1。
 *
 * 关键点：takePicture 需要预览处于运行状态，而这里没有预览界面 —— 用一个
 * 空闲的 SurfaceTexture 作为预览 sinks 即可（屏幕不显示、不占 GPU 上下文）。
 */
public class CameraCapture {
    private static final String TAG = "CameraCapture";
    /** 上行图片最大宽度：太大 vision 端会慢，1600px 足够看清物品。 */
    private static final int MAX_WIDTH = 1600;

    private CameraCapture() {}

    /** 是否存在可用摄像头。 */
    public static boolean available() {
        try {
            return Camera.getNumberOfCameras() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 优先前置（智能屏的摄像头在正面），否则退到第一个。 */
    private static int pickCameraId() {
        int n = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < n; i++) {
            try {
                Camera.getCameraInfo(i, info);
            } catch (Throwable t) {
                continue;
            }
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return n > 0 ? 0 : -1;
    }

    private static Camera.Size pickSize(List<Camera.Size> sizes) {
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (s.width <= MAX_WIDTH && s.width >= 640) {
                if (best == null || s.width > best.width) best = s;
            }
        }
        if (best != null) return best;
        // 全部超出上限时取最小的那个
        for (Camera.Size s : sizes) {
            if (best == null || s.width < best.width) best = s;
        }
        return best;
    }

    /**
     * 拍照并返回 JPEG 数据。
     *
     * @return JPEG 字节；失败抛异常（调用方转成工具错误信息）
     */
    public static byte[] captureJpeg(Context ctx) throws Exception {
        int id = pickCameraId();
        if (id < 0) throw new Exception("设备没有可用摄像头");

        Camera cam = null;
        try {
            cam = Camera.open(id);
            Camera.Parameters p = cam.getParameters();
            Camera.Size size = pickSize(p.getSupportedPictureSizes());
            if (size != null) p.setPictureSize(size.width, size.height);
            p.setPictureFormat(ImageFormat.JPEG);
            p.setJpegQuality(85);
            try { p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE); } catch (Throwable ignore) {}
            // 本机前置 sensor orientation=90，而屏幕固定横屏（rotation=0），
            // 实测直出画面整体倒置 180°，这里把 JPEG 顺时针转 180° 修正。
            try { p.setRotation(180); } catch (Throwable ignore) {}
            cam.setParameters(p);

            SurfaceTexture dummy = new SurfaceTexture(10);
            cam.setPreviewTexture(dummy);
            cam.startPreview();

            final CountDownLatch latch = new CountDownLatch(1);
            final byte[][] holder = new byte[1][];
            cam.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    holder[0] = data;
                    latch.countDown();
                }
            });

            if (!latch.await(8, TimeUnit.SECONDS)) throw new Exception("拍照超时");
            byte[] jpeg = holder[0];
            if (jpeg == null || jpeg.length == 0) throw new Exception("拍照返回空数据");
            Log.i(TAG, "capture ok: " + jpeg.length + " bytes"
                    + (size != null ? (" @" + size.width + "x" + size.height) : ""));
            return jpeg;
        } finally {
            if (cam != null) {
                try { cam.stopPreview(); } catch (Throwable ignore) {}
                try { cam.release(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 供界面/状态文件展示的分辨率摘要。 */
    public static String describe() {
        int id = pickCameraId();
        if (id < 0) return "无摄像头";
        try {
            Camera c = Camera.open(id);
            Camera.Size s = pickSize(c.getParameters().getSupportedPictureSizes());
            c.release();
            return "Camera" + id + (s != null ? (" " + s.width + "x" + s.height) : "");
        } catch (Throwable t) {
            return "Camera" + id + " (参数读取失败)";
        }
    }
}
