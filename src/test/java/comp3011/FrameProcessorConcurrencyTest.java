/*
 * Unit tests verifying thread safety, shared instance concurrency,
 * and bit-exact frame accuracy of FrameProcessor implementations.
 *
 * Author:
 *   1. Vihara Girihagama, in collaboration with Gemini-3.8 Flash
 *
 * Copyright 2026 Vihara Girihagama
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class FrameProcessorConcurrencyTest {

  private static final int TEST_WIDTH = 160;
  private static final int TEST_HEIGHT = 120;
  private static final int TEST_CHANNELS = 3;
  private static final int TEST_STRIDE = TEST_WIDTH * TEST_CHANNELS;
  private static final int TEST_TOTAL_FRAMES = 50;

  private InfoVideo createTestVideoInfo(String mediaName) {
    return new InfoVideo(
        mediaName,
        TEST_TOTAL_FRAMES,
        TEST_WIDTH,
        TEST_HEIGHT,
        Frame.DEPTH_UBYTE,
        TEST_CHANNELS,
        TEST_STRIDE,
        24.0,
        24,
        41666,
        0);
  }

  private Frame createBaseFrame(int frameNumber) {
    Frame frame = new Frame();
    frame.imageWidth = TEST_WIDTH;
    frame.imageHeight = TEST_HEIGHT;
    frame.imageChannels = TEST_CHANNELS;
    frame.imageDepth = Frame.DEPTH_UBYTE;
    frame.imageStride = TEST_STRIDE;

    int size = TEST_STRIDE * TEST_HEIGHT;
    ByteBuffer buffer = ByteBuffer.allocateDirect(size);
    // Fill with a deterministic synthetic gradient
    for (int y = 0; y < TEST_HEIGHT; y++) {
      for (int x = 0; x < TEST_WIDTH; x++) {
        int base = y * TEST_STRIDE + x * TEST_CHANNELS;
        buffer.put(base, (byte) ((x + frameNumber * 7) & 0xFF));
        buffer.put(base + 1, (byte) ((y + frameNumber * 13) & 0xFF));
        buffer.put(base + 2, (byte) ((x * y + frameNumber * 3) & 0xFF));
      }
    }
    frame.image = new java.nio.Buffer[] {buffer};
    return frame;
  }

  private byte[] extractFrameBytes(Frame frame) {
    ByteBuffer buf = (ByteBuffer) frame.image[0];
    byte[] bytes = new byte[TEST_STRIDE * TEST_HEIGHT];
    ByteBuffer dup = buf.duplicate();
    dup.clear();
    dup.get(bytes);
    return bytes;
  }

  @Test
  @DisplayName("Stress test shared-instance concurrency for FrameJitterer, FrameMottler, and FrameBleeder")
  void testSharedInstanceStressTest() throws Exception {
    InfoVideo videoInfo = createTestVideoInfo("stress_clip");

    FrameJitterer jitterer = new FrameJitterer();
    FrameMottler mottler = new FrameMottler();
    FrameBleeder bleeder = new FrameBleeder();

    jitterer.initialise(videoInfo);
    mottler.initialise(videoInfo);
    bleeder.initialise(videoInfo);

    List<FrameProcessor> sharedProcessors = List.of(jitterer, mottler, bleeder);

    // Compute expected frames sequentially
    int frameCount = 30;
    byte[][] expected = new byte[frameCount][];
    for (int f = 0; f < frameCount; f++) {
      Frame frame = createBaseFrame(f);
      InfoFrame info = new InfoFrame(f, f * 41666L);
      for (FrameProcessor p : sharedProcessors) {
        p.process(frame, info);
      }
      expected[f] = extractFrameBytes(frame);
    }

    // Run same frame numbers concurrently across 8 threads on the EXACT SAME processor instances
    ExecutorService executor = Executors.newFixedThreadPool(8);
    byte[][] actual = new byte[frameCount][];
    List<Future<?>> futures = new ArrayList<>();

    // Submit frames in reverse/jumbled order to maximise contention
    for (int i = 0; i < frameCount; i++) {
      final int f = (i * 7 + 11) % frameCount;
      futures.add(
          executor.submit(
              () -> {
                try {
                  Frame frame = createBaseFrame(f);
                  InfoFrame info = new InfoFrame(f, f * 41666L);
                  for (FrameProcessor p : sharedProcessors) {
                    p.process(frame, info);
                  }
                  actual[f] = extractFrameBytes(frame);
                } catch (Exception e) {
                  fail("Exception during concurrent process: " + e.getMessage(), e);
                }
              }));
    }

    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    // Verify 100% byte-for-byte parity across all frames
    for (int f = 0; f < frameCount; f++) {
      assertArrayEquals(
          expected[f],
          actual[f],
          "Byte mismatch in concurrently processed frame " + f);
    }
  }

  @Test
  @DisplayName("Bit-exact determinism across all 11 FrameProcessor subclasses")
  void testAllElevenProcessorsBitExactDeterminism() throws Exception {
    InfoVideo videoInfo = createTestVideoInfo("all_processors_clip");

    List<FrameProcessor> processors =
        List.of(
            new FrameNumberer(),
            new FrameScratcher(),
            new FrameFlickerer(),
            new FrameBlackAndWhiter(),
            new FrameYellower(),
            new FrameVignetter(),
            new FrameDuster(),
            new FrameJitterer(),
            new FrameMottler(),
            new FrameBleeder(),
            new FramePepperer());

    for (FrameProcessor p : processors) {
      p.initialise(videoInfo);
    }

    int frameCount = 20;
    byte[][] sequentialResults = new byte[frameCount][];
    for (int f = 0; f < frameCount; f++) {
      Frame frame = createBaseFrame(f);
      InfoFrame info = new InfoFrame(f, f * 41666L);
      for (FrameProcessor p : processors) {
        p.process(frame, info);
      }
      sequentialResults[f] = extractFrameBytes(frame);
    }

    // Run concurrently on 8 threads
    ExecutorService executor = Executors.newFixedThreadPool(8);
    byte[][] concurrentResults = new byte[frameCount][];
    List<Future<?>> futures = new ArrayList<>();

    for (int f = 0; f < frameCount; f++) {
      final int frameNum = f;
      futures.add(
          executor.submit(
              () -> {
                try {
                  Frame frame = createBaseFrame(frameNum);
                  InfoFrame info = new InfoFrame(frameNum, frameNum * 41666L);
                  for (FrameProcessor p : processors) {
                    p.process(frame, info);
                  }
                  concurrentResults[frameNum] = extractFrameBytes(frame);
                } catch (Exception e) {
                  fail("Exception in concurrent pipeline for frame " + frameNum, e);
                }
              }));
    }

    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    for (int f = 0; f < frameCount; f++) {
      assertArrayEquals(
          sequentialResults[f],
          concurrentResults[f],
          "Concurrent processing output deviated from single-threaded baseline at frame " + f);
    }
  }
}
