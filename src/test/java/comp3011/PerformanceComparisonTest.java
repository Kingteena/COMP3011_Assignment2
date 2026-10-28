/*
 * Automated integration load test comparing single-threaded vs multi-threaded playback,
 * and verifying End-Of-File lifecycle and seek-from-EOF recovery.
 *
 * Authors:
 *   1. Vihara Girihagama, in collaboration with Gemini-3.8 Flash
 *
 * Copyright 2026 Vihara Girihagama
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("BusyWait")
public class PerformanceComparisonTest {

    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;
    private static final int VIDEO_FPS = 24;
    private static final int TOTAL_FRAMES = 24;
    private static final long FRAME_BUDGET_US = 1_000_000L / VIDEO_FPS; // 41,666 us

    @TempDir
    static File tempDir;

    private static File testVideoFile;

    @BeforeAll
    static void setupVideoAndToolkit() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // Toolkit already initialised
        }

        testVideoFile = new File(tempDir, "benchmark_720p.mp4");
        try (FFmpegFrameRecorder recorder =
                new FFmpegFrameRecorder(testVideoFile, VIDEO_WIDTH, VIDEO_HEIGHT)) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(VIDEO_FPS);
            recorder.start();

            int stride = VIDEO_WIDTH * 3;
            for (int f = 0; f < TOTAL_FRAMES; f++) {
                Frame frame = new Frame(VIDEO_WIDTH, VIDEO_HEIGHT, Frame.DEPTH_UBYTE, 3);
                frame.imageStride = stride;
                ByteBuffer buf = ByteBuffer.allocateDirect(stride * VIDEO_HEIGHT);
                // Generate synthetic bright moving scene so bleed, blur, and mottling engage full computational load
                for (int y = 0; y < VIDEO_HEIGHT; y++) {
                    for (int x = 0; x < VIDEO_WIDTH; x++) {
                        int base = y * stride + x * 3;
                        byte b = (byte) (180 + ((x + f * 4) % 60));
                        byte g = (byte) (190 + ((y + f * 2) % 55));
                        byte r = (byte) (210 + ((x + y + f) % 40));
                        buf.put(base, b);
                        buf.put(base + 1, g);
                        buf.put(base + 2, r);
                    }
                }
                frame.image = new java.nio.Buffer[] {buf};
                recorder.record(frame);
            }

            recorder.stop();
        }
        assertTrue(testVideoFile.exists() && testVideoFile.length() > 0, "Video creation failed");
    }

    private static class BenchmarkResult {
        final String mode;
        final long totalTimeMs;
        final double avgMsPerFrame;
        final double effectiveFps;
        final int lateFrames;

        BenchmarkResult(
                String mode, long totalTimeMs, double avgMsPerFrame, double effectiveFps, int lateFrames) {
            this.mode = mode;
            this.totalTimeMs = totalTimeMs;
            this.avgMsPerFrame = avgMsPerFrame;
            this.effectiveFps = effectiveFps;
            this.lateFrames = lateFrames;
        }
    }

    private List<FrameProcessor> createFullStackProcessors() {
        CommandLineController cli =
                new CommandLineController(new String[] {"-nssnfwyvdjmbpnssnfwyvdjmbp", testVideoFile.getAbsolutePath()});
        return cli.getFrameProcessors();
    }

    private BenchmarkResult benchmarkModel(boolean multiThreaded, List<FrameProcessor> processors) throws Exception {
        AtomicInteger framesReceived = new AtomicInteger(0);
        AtomicInteger lateFrames = new AtomicInteger(0);
        AtomicLong firstFrameTimeNs = new AtomicLong(0);
        AtomicReference<String> lastStatus = new AtomicReference<>("");

        long startTime = System.nanoTime();

        VideoPlayerModel model =
                new VideoPlayerModel(
                        false,
                        (_, _) -> {},
                        img -> {
                            if (img != null) {
                                long nowNs = System.nanoTime();
                                firstFrameTimeNs.compareAndSet(0, nowNs);
                                int frameIdx = framesReceived.getAndIncrement();
                                long relativeElapsed = nowNs - firstFrameTimeNs.get();
                                long deadlineNs = (long) (frameIdx + 1) * FRAME_BUDGET_US * 1_000L;
                                if (relativeElapsed > deadlineNs) {
                                    lateFrames.incrementAndGet();
                                }
                            }
                        },
                        lastStatus::set,
                        (_, _) -> {},
                        _ -> {},
                        processors,
                        multiThreaded);

        CountDownLatch playLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            model.play(testVideoFile);
            playLatch.countDown();
        });
        assertTrue(playLatch.await(5, TimeUnit.SECONDS), "Playback start timed out");

        // Synthetic clock unthrottles presentation so VideoPlayerModel presents frames
        // as fast as processing allows rather than waiting for 24fps real-time clock
        long syntheticNow = System.nanoTime() + 1_000_000_000L;
        long stepNs = FRAME_BUDGET_US * 1_000L;
        long deadline = System.currentTimeMillis() + 30_000;

        while (System.currentTimeMillis() < deadline) {
            final long pulse = syntheticNow;
            Platform.runLater(() -> model.pumpPlayback(pulse));
            syntheticNow += stepNs;
            Thread.sleep(2);
            if ("Playback finished".equals(lastStatus.get()) || framesReceived.get() >= TOTAL_FRAMES) {
                break;
            }
        }

        long totalElapsedNs = System.nanoTime() - startTime;

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            model.shutdown();
            shutdownLatch.countDown();
        });
        assertTrue(shutdownLatch.await(5, TimeUnit.SECONDS), "Model shutdown timed out");

        int count = framesReceived.get();
        long totalTimeMs = totalElapsedNs / 1_000_000L;
        double avgMs = count > 0 ? (double) totalTimeMs / count : 0;
        double fps = count > 0 ? (double) count / (totalElapsedNs / 1_000_000_000.0) : 0;
        String modeName = multiThreaded ? "Multi-Threaded" : "Single-Threaded";

        return new BenchmarkResult(modeName, totalTimeMs, avgMs, fps, lateFrames.get());
    }

    @Test
    @DisplayName("Full integration load test comparing single-threaded vs multi-threaded performance at 720p HD")
    void testPerformanceComparisonUnderHeavyLoad() throws Exception {
        List<FrameProcessor> stProcessors = createFullStackProcessors();
        List<FrameProcessor> mtProcessors = createFullStackProcessors();

        assertEquals(26, stProcessors.size(), "Expected 26 processors for specification stack");
        assertEquals(26, mtProcessors.size(), "Expected 26 processors for specification stack");

        BenchmarkResult stResult = benchmarkModel(false, stProcessors);
        BenchmarkResult mtResult = benchmarkModel(true, mtProcessors);

        double speedup = (double) stResult.totalTimeMs / Math.max(1, mtResult.totalTimeMs);

        // Print comparative benchmark report
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("  COMP3011 Video Player 720p HD Benchmark Report (-nssnfwyvdjmbpnssnfwyvdjmbp)  ");
        System.out.println("================================================================================");
        System.out.printf(
                "%-18s | %-13s | %-14s | %-15s | %-11s%n",
                "Architecture", "Total Time", "Avg ms/Frame", "Effective FPS", "Late Frames");
        System.out.println("-------------------+---------------+----------------+-----------------+------------");
        System.out.printf(
                "%-18s | %10d ms | %11.2f ms | %12.2f fps | %11d%n",
                stResult.mode, stResult.totalTimeMs, stResult.avgMsPerFrame, stResult.effectiveFps, stResult.lateFrames);
        System.out.printf(
                "%-18s | %10d ms | %11.2f ms | %12.2f fps | %11d%n",
                mtResult.mode, mtResult.totalTimeMs, mtResult.avgMsPerFrame, mtResult.effectiveFps, mtResult.lateFrames);
        System.out.println("================================================================================");
        System.out.printf("Observed Concurrency Speedup: %.2fx faster%n", speedup);
        System.out.println("================================================================================");
        System.out.println();

        // Verify multi-threaded mode demonstrates measurable performance advantage
        assertTrue(
                mtResult.totalTimeMs < stResult.totalTimeMs,
                String.format(
                        "Multi-threaded time (%d ms) should be strictly faster than single-threaded (%d ms)",
                        mtResult.totalTimeMs, stResult.totalTimeMs));
        assertTrue(
                mtResult.effectiveFps > stResult.effectiveFps,
                String.format(
                        "Multi-threaded FPS (%.2f) should be higher than single-threaded FPS (%.2f)",
                        mtResult.effectiveFps, stResult.effectiveFps));
    }

    @Test
    @DisplayName("Verify end-of-clip lifecycle and seeking backwards from EOF in multi-threaded model")
    void testEndOfClipAndSeekBackwards() throws Exception {
        AtomicInteger framesReceived = new AtomicInteger(0);
        AtomicReference<String> lastStatus = new AtomicReference<>("");

        VideoPlayerModel model =
                new VideoPlayerModel(
                        false,
                        (_, _) -> {},
                        img -> {
                            if (img != null) {
                                framesReceived.incrementAndGet();
                            }
                        },
                        lastStatus::set,
                        (_, _) -> {},
                        _ -> {},
                        List.of(),
                        true);

        try {
            // Start playback on JavaFX thread
            CountDownLatch firstPlayLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                model.play(testVideoFile);
                firstPlayLatch.countDown();
            });
            assertTrue(firstPlayLatch.await(5, TimeUnit.SECONDS));

            // Pump playback pulses until EOF is reached
            long now = System.nanoTime();
            long stepNs = 1_000_000_000L / VIDEO_FPS;
            long deadline = System.currentTimeMillis() + 10_000;

            while (System.currentTimeMillis() < deadline) {
                final long currentNow = now;
                Platform.runLater(() -> model.pumpPlayback(currentNow));
                now += stepNs;
                Thread.sleep(2);
                if ("Playback finished".equals(lastStatus.get())) {
                    break;
                }
            }

            assertEquals("Playback finished", lastStatus.get(), "Expected playback to reach EOF sentinel");
            assertEquals(TOTAL_FRAMES, framesReceived.get(), "Expected all frames to be presented before finishing");

            // Seek backwards from EOF
            int framesBeforeSeek = framesReceived.get();
            CountDownLatch seekLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                model.backFiveSeconds();
                // After EOF, finishPlayback sets pauseRequested=true. Unpause to resume playback.
                model.togglePause();
                seekLatch.countDown();
            });
            assertTrue(seekLatch.await(5, TimeUnit.SECONDS));

            // Pump playback pulses again and verify frames resume delivery smoothly
            deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                final long currentNow = now;
                Platform.runLater(() -> model.pumpPlayback(currentNow));
                now += stepNs;
                Thread.sleep(2);
                if (framesReceived.get() > framesBeforeSeek + 3) {
                    break;
                }
            }

            assertTrue(
                    framesReceived.get() > framesBeforeSeek,
                    "Expected additional frames to be delivered after seeking backwards from EOF");
        } finally {
            CountDownLatch shutdownLatch = new CountDownLatch(1);
            Platform.runLater(() -> {
                model.shutdown();
                shutdownLatch.countDown();
            });
            assertTrue(shutdownLatch.await(5, TimeUnit.SECONDS), "Model shutdown timed out");
        }
    }
}
