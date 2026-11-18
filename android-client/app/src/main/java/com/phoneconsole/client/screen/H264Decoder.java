package com.phoneconsole.client.screen;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * H.264 Annex-B 解码：MediaCodec 输出到 TextureView 的 Surface。
 * 每个 WebSocket 二进制帧即一个访问单元（AUD 开头）；
 * 编码尺寸与帧率由服务端 screen_state / screen_stats 下发（默认 1280x720@30），不再写死 1080p60。
 */
public final class H264Decoder {

    public interface Listener {
        void onFirstFrame();

        void onError(String message);
    }

    /** 服务端未下发格式时的兜底值，与 PC 端 config.default.json 保持一致 */
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int DEFAULT_FPS = 30;
    private static final int QUEUE_CAPACITY = 12;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private volatile boolean running;
    private volatile int generation;
    private Thread worker;

    public H264Decoder(Listener listener) {
        this.listener = listener;
    }

    /**
     * 启动解码线程；Surface 由调用方持有生命周期。
     * 分辨率与帧率来自服务端下发值，<=0 时退回 720p30 兜底。
     */
    public synchronized void start(Surface surface, int width, int height, int fps) {
        stopLocked();
        if (surface == null || !surface.isValid()) {
            return;
        }
        int resolvedWidth = width > 0 ? width : DEFAULT_WIDTH;
        int resolvedHeight = height > 0 ? height : DEFAULT_HEIGHT;
        int resolvedFps = fps > 0 ? Math.min(120, fps) : DEFAULT_FPS;
        queue.clear();
        running = true;
        int gen = ++generation;
        worker = new Thread(() -> loop(gen, surface, resolvedWidth, resolvedHeight, resolvedFps),
                "h264-decoder");
        worker.start();
    }

    public void stop() {
        synchronized (this) {
            stopLocked();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 停止并等待工作线程退出。工作线程在 finally 里释放 MediaCodec，
     * 必须等它结束后才能启动新实例，否则会出现 Released state 异常。
     */
    private void stopLocked() {
        running = false;
        generation++;
        queue.clear();
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 由 WebSocket 线程调用；队列满时丢弃最旧一帧 */
    public void feed(byte[] data) {
        if (!running || data == null || data.length == 0) {
            return;
        }
        if (!queue.offer(data)) {
            queue.poll();
            queue.offer(data);
        }
    }

    /** 解码格式：尺寸来自服务端下发，低延迟附加键按系统版本选配 */
    private static MediaFormat decodeFormat(int width, int height, int fps, boolean lowLatency) {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        if (lowLatency) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            }
        }
        return format;
    }

    private void loop(int gen, Surface surface, int width, int height, int fps) {
        MediaCodec codec = null;
        boolean firstFrame = false;
        // 输入缓冲暂时拿不到时保留当前帧下一轮重试，直接丢弃会造成码流断点花屏
        byte[] pending = null;
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            try {
                codec.configure(decodeFormat(width, height, fps, true), surface, null, 0);
            } catch (Exception e) {
                // 个别老机型不认低延迟/优先级附加键，重建解码器后按基础格式重试
                try {
                    codec.release();
                } catch (Exception ignored) {
                    // 已释放
                }
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                codec.configure(decodeFormat(width, height, fps, false), surface, null, 0);
            }
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long ptsUs = 0;
            long frameIntervalUs = 1_000_000L / Math.max(1, fps);
            while (running && gen == generation) {
                if (pending == null) {
                    pending = queue.poll(30, TimeUnit.MILLISECONDS);
                }
                if (pending != null) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        if (buffer != null && buffer.capacity() >= pending.length) {
                            buffer.clear();
                            buffer.put(pending);
                            codec.queueInputBuffer(index, 0, pending.length, ptsUs, 0);
                            ptsUs += frameIntervalUs;
                            pending = null;
                        } else {
                            // 缓冲不足：连同空帧一起回填，保留 pending 下一轮重试
                            codec.queueInputBuffer(index, 0, 0, ptsUs, 0);
                        }
                    }
                }

                while (running && gen == generation) {
                    int index = codec.dequeueOutputBuffer(info, 0);
                    if (index >= 0) {
                        codec.releaseOutputBuffer(index, true);
                        if (!firstFrame) {
                            firstFrame = true;
                            main.post(listener::onFirstFrame);
                        }
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // 尺寸以 SPS 为准，服务端改分辨率会重新推流并下发新格式
                    } else {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (gen == generation) {
                String message = "H.264 解码失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                main.post(() -> listener.onError(message));
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // 已停止
                }
                try {
                    codec.release();
                } catch (Exception ignored) {
                    // 已释放
                }
            }
        }
    }
}
