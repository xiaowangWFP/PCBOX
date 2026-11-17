package com.phoneconsole.client.screen;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * H.264 Annex-B 解码：MediaCodec 输出到 TextureView 的 Surface。
 * 服务端固定 1920x1080@60，每个 WebSocket 二进制帧即一个访问单元（AUD 开头）。
 */
public final class H264Decoder {

    public interface Listener {
        void onFirstFrame();

        void onError(String message);
    }

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final long FRAME_INTERVAL_US = 1_000_000L / 60;
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

    /** 启动解码线程；Surface 由调用方持有生命周期 */
    public synchronized void start(Surface surface) {
        stopLocked();
        if (surface == null || !surface.isValid()) {
            return;
        }
        queue.clear();
        running = true;
        int gen = ++generation;
        worker = new Thread(() -> loop(gen, surface), "h264-decoder");
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

    private void loop(int gen, Surface surface) {
        MediaCodec codec = null;
        boolean firstFrame = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, surface, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long ptsUs = 0;
            while (running && gen == generation) {
                byte[] data = queue.poll(30, TimeUnit.MILLISECONDS);
                if (data != null) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        if (buffer != null && buffer.capacity() >= data.length) {
                            buffer.clear();
                            buffer.put(data);
                            codec.queueInputBuffer(index, 0, data.length, ptsUs, 0);
                            ptsUs += FRAME_INTERVAL_US;
                        } else {
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
                        // 输出尺寸固定 1080p，无需处理
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
