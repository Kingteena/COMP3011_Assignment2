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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PerformanceComparisonTest {

  private static final int VIDEO_WIDTH = 1280;
  private static final int VIDEO_HEIGHT = 720;
  private static final int VIDEO_FPS = 24;
  private static final int TOTAL_FRAMES = 36;
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
    FFmpegFrameRecorder recorder =
        new FFmpegFrameRecorder(testVideoFile, VIDEO_WIDTH, VIDEO_HEIGHT);
    recorder.setFormat("mp4");
    recorder.setFrameRate(VIDEO_FPS);
    recorder.start();

    int stride = VIDEO_WIDTH * 3;
    for (int f = 0; f < TOTAL_FRAMES; f++) {
      Frame frame = new Frame(VIDEO_WIDTH, VIDEO_HEIGHT, Frame.DEPTH_UBYTE, 3);
      frame.imageStride = stride;
      ByteBuffer buf = ByteBuffer.allocateDirect(stride * VIDEO_HEIGHT);
      // Generate synthetic moving scene so blur, mottling, and bright-mask have real data
      for (int y = 0; y < VIDEO_HEIGHT; y++) {
        for (int x = 0; x < VIDEO_WIDTH; x++) {
          int base = y * stride + x * 3;
          byte b = (byte) ((x + f * 4) & 0xFF);
          byte g = (byte) ((y + f * 2) & 0xFF);
          byte r = (byte) (((x + y) / 2 > 200) ? 250 : 30);
          buf.put(base, b);
          buf.put(base + 1, g);
          buf.put(base + 2, r);
        }
      }
      frame.image = new java.nio.Buffer[] {buf};
      recorder.record(frame);
    }

    recorder.stop();
    recorder.release();
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
        new CommandLineController(new String[] {"-nssnfwyvdjmbp", testVideoFile.getAbsolutePath()});
    return cli.getFrameProcessors();
  }

  private BenchmarkResult benchmarkSingleThreaded(List<FrameProcessor> processors) throws Exception {
    FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(testVideoFile);
    grabber.start();

    InfoVideo videoInfo =
        new InfoVideo(
            "benchmark",
            TOTAL_FRAMES,
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            Frame.DEPTH_UBYTE,
            3,
            VIDEO_WIDTH * 3,
            VIDEO_FPS,
            VIDEO_FPS,
            (int) FRAME_BUDGET_US,
            grabber.getPixelFormat());

    for (FrameProcessor p : processors) {
      p.initialise(videoInfo);
    }

    JavaFXFrameConverter converter = new JavaFXFrameConverter();
    int framesProcessed = 0;
    int lateFrames = 0;

    long startTime = System.nanoTime();
    while (true) {
      long frameStart = System.nanoTime();
      Frame frame = grabber.grabImage();
      if (frame == null) {
        break;
      }

      int frameNumber = grabber.getFrameNumber();
      InfoFrame info = new InfoFrame(frameNumber, grabber.getTimestamp());
      for (FrameProcessor p : processors) {
        p.process(frame, info);
      }
      Image img = converter.convert(frame);
      assertNotNull(img);

      long frameDurationUs = (System.nanoTime() - frameStart) / 1000L;
      if (frameDurationUs > FRAME_BUDGET_US) {
        lateFrames++;
      }
      framesProcessed++;
    }
    long totalElapsedNs = System.nanoTime() - startTime;

    converter.close();
    grabber.stop();
    grabber.close();

    long totalTimeMs = totalElapsedNs / 1_000_000L;
    double avgMs = (double) totalTimeMs / framesProcessed;
    double fps = (double) framesProcessed / (totalElapsedNs / 1_000_000_000.0);

    return new BenchmarkResult("Single-Threaded", totalTimeMs, avgMs, fps, lateFrames);
  }

  private BenchmarkResult benchmarkMultiThreaded(List<FrameProcessor> processors) throws Exception {
    FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(testVideoFile);
    grabber.start();

    InfoVideo videoInfo =
        new InfoVideo(
            "benchmark",
            TOTAL_FRAMES,
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            Frame.DEPTH_UBYTE,
            3,
            VIDEO_WIDTH * 3,
            VIDEO_FPS,
            VIDEO_FPS,
            (int) FRAME_BUDGET_US,
            grabber.getPixelFormat());

    for (FrameProcessor p : processors) {
      p.initialise(videoInfo);
    }

    int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
    ThreadPoolExecutor workerPool =
        new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
              Thread t = new Thread(r, "Benchmark-Worker");
              t.setDaemon(true);
              return t;
            });

    ThreadLocal<JavaFXFrameConverter> workerConverters =
        ThreadLocal.withInitial(JavaFXFrameConverter::new);
    BlockingQueue<CompletableFuture<Long>> pipelineQueue = new LinkedBlockingQueue<>(16);
    AtomicInteger framesProcessed = new AtomicInteger(0);
    AtomicInteger lateFrames = new AtomicInteger(0);

    long startTime = System.nanoTime();

    // Demuxer thread submits frames into pipeline
    Thread demuxer =
        new Thread(
            () -> {
              try {
                while (true) {
                  Frame rawFrame = grabber.grabImage();
                  if (rawFrame == null) {
                    break;
                  }
                  int frameNum = grabber.getFrameNumber();
                  long ts = grabber.getTimestamp();

                  Frame frameToProcess = rawFrame.clone();
                  long submitTimeNs = System.nanoTime();

                  CompletableFuture<Long> future =
                      CompletableFuture.supplyAsync(
                          () -> {
                            try {
                              InfoFrame info = new InfoFrame(frameNum, ts);
                              for (FrameProcessor p : processors) {
                                p.process(frameToProcess, info);
                              }
                              Image img = workerConverters.get().convert(frameToProcess);
                              assertNotNull(img);
                              return (System.nanoTime() - submitTimeNs) / 1000L;
                            } catch (Exception e) {
                              throw new CompletionException(e);
                            } finally {
                              frameToProcess.close();
                            }
                          },
                          workerPool);

                  pipelineQueue.put(future);
                }
              } catch (Exception e) {
                fail("Demuxer failed: " + e.getMessage(), e);
              }
            });

    demuxer.start();

    // Consumer drains frames in order
    while (framesProcessed.get() < TOTAL_FRAMES) {
      CompletableFuture<Long> head = pipelineQueue.poll(5, TimeUnit.SECONDS);
      if (head == null) {
        break;
      }
      long latencyUs = head.join();
      if (latencyUs > FRAME_BUDGET_US * 3) {
        // Only marked late if backlog exceeds pipeline buffer capacity
        lateFrames.incrementAndGet();
      }
      framesProcessed.incrementAndGet();
    }

    long totalElapsedNs = System.nanoTime() - startTime;
    demuxer.join();
    workerPool.shutdown();
    grabber.stop();
    grabber.close();

    long totalTimeMs = totalElapsedNs / 1_000_000L;
    double avgMs = (double) totalTimeMs / framesProcessed.get();
    double fps = (double) framesProcessed.get() / (totalElapsedNs / 1_000_000_000.0);

    return new BenchmarkResult("Multi-Threaded", totalTimeMs, avgMs, fps, lateFrames.get());
  }

  @Test
  @DisplayName("Full integration load test comparing single-threaded vs multi-threaded performance at 720p HD")
  void testPerformanceComparisonUnderHeavyLoad() throws Exception {
    List<FrameProcessor> stProcessors = createFullStackProcessors();
    List<FrameProcessor> mtProcessors = createFullStackProcessors();

    assertEquals(13, stProcessors.size(), "Expected 13 processors for -nssnfwyvdjmbp");
    assertEquals(13, mtProcessors.size(), "Expected 13 processors for -nssnfwyvdjmbp");

    BenchmarkResult stResult = benchmarkSingleThreaded(stProcessors);
    BenchmarkResult mtResult = benchmarkMultiThreaded(mtProcessors);

    double speedup = (double) stResult.totalTimeMs / Math.max(1, mtResult.totalTimeMs);

    // Print comparative benchmark report
    System.out.println();
    System.out.println("================================================================================");
    System.out.println("  COMP3011 Video Player Architecture Benchmark Report (-nssnfwyvdjmbp)  ");
    System.out.println("================================================================================");
    System.out.printf("%-18s | %-13s | %-14s | %-15s | %-11s%n",
        "Architecture", "Total Time", "Avg ms/Frame", "Effective FPS", "Late Frames");
    System.out.println("-------------------+---------------+----------------+-----------------+------------");
    System.out.printf("%-18s | %10d ms | %11.2f ms | %12.2f fps | %11d%n",
        stResult.mode, stResult.totalTimeMs, stResult.avgMsPerFrame, stResult.effectiveFps, stResult.lateFrames);
    System.out.printf("%-18s | %10d ms | %11.2f ms | %12.2f fps | %11d%n",
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
        speedup > 1.2,
        String.format("Expected at least 1.2x speedup on multi-core system, got %.2fx", speedup));
  }

  @Test
  @DisplayName("Verify end-of-clip lifecycle and seeking backwards from EOF in multi-threaded model")
  void testEndOfClipAndSeekBackwards() throws Exception {
    AtomicInteger framesReceived = new AtomicInteger(0);
    AtomicReference<String> lastStatus = new AtomicReference<>("");
    AtomicBoolean playbackOpenRef = new AtomicBoolean(false);
    AtomicBoolean pauseRequestedRef = new AtomicBoolean(false);

    VideoPlayerModel model =
        new VideoPlayerModel(
            false,
            (w, h) -> {},
            img -> {
              if (img != null) {
                framesReceived.incrementAndGet();
              }
            },
            lastStatus::set,
            (open, paused) -> {
              playbackOpenRef.set(open);
              pauseRequestedRef.set(paused);
            },
            audio -> {},
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
        Thread.sleep(20);
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
        Thread.sleep(20);
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
      shutdownLatch.await(5, TimeUnit.SECONDS);
    }
  }
}
