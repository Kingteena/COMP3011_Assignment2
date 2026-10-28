# COMP3011 High-Performance Video Player

This submission is for Assignment 2 of COMP3011 Cloud and Concurrent Programming by Vihara Girihagama (a1930250). 
It can be found at [this GitHub repository](https://github.com/Kingteena/COMP3011_Assignment2)

It implements a thread-safe, pipelined, multithreaded video playback engine built with JavaFX and FFmpeg (JavaCV).

---

## 1. Command-Line Architecture & Extensibility (SOLID Design)

Command-line parsing and effect instantiation are decoupled across two classes to adhere to the Single Responsibility and Open-Closed Principles:

-   **`CommandLineController`**: Parses short flags (`-n`), long options (`--number-frames`), and stacked flag clusters (`-axjf`, `-nssnfwyvdjmbp`) in strict left-to-right order. It validates mutually exclusive display options (`-1` vs `-2`), ensures `--help` / `-h` never launches the GUI when combined with a valid file, and rejects invalid option combinations even when `-h` is present.
-   **`FrameProcessorFactory`**: Encapsulates the mapping from CLI tokens to `Supplier<FrameProcessor>` constructor references (`FrameNumberer::new`, `FrameScratcher::new`, etc.). Storing `Supplier` factories rather than singleton instances guarantees that repeated flags (such as `-ss`) instantiate distinct processor objects with independent per-instance random seeds (`instanceIndex`).
-   **Adding a New `FrameProcessor`**:
    1.  Subclass `FrameProcessor` and implement `initialise(InfoVideo)` and `process(Frame, InfoFrame)`. Ensure `process()` is deterministic (seeded via `info.frameNumber()`) and uses local or `ThreadLocal` scratch buffers rather than mutable instance arrays.
    2.  Register the constructor reference in `FrameProcessorFactory`'s `static` block via `register(shortCode, longName, NewProcessor::new)`.
    3.  Add the option description to `CommandLineController.printHelp()`.

---

## 2. Multi-Threaded Concurrency Architecture

In the supplied single-threaded player, demuxing, decoding, multi-pass pixel filtering, and JavaFX image conversion all executed cooperatively on the JavaFX Application Thread. Stacking heavy effects (`FrameMottler`, `FrameBleeder`, `FramePepperer`) on HD media quickly exceeded the 24 fps frame budget (`41,666 us`), causing negative frame headroom, audio stalls, and unresponsive window resizing.

To maximise multicore throughput while preserving 100% bit-exact frame accuracy and responsive UI controls, `VideoPlayerModel` implements a Demuxer + Worker Pool + Bounded Ordered Future Queue architecture:

```
[FFmpeg Demuxer Thread] (VideoPlayer-Demuxer)
                   │
                   ├─► Audio Samples ──► synchronized(pendingAudio) ──► AudioPlayer
                   │
                   ▼ (Video Frame cloned inside grabberLock)
[Worker ThreadPoolExecutor] (N = availableProcessors - 1, min 2)
                   │  Runs full FrameProcessor chain + ThreadLocal
                   │  Checks currentEpoch before each filter for instant seek abortion
                   ▼
[framePipelineQueue: BlockingQueue (Capacity: 16)]
                   │  Enqueued in strict chronological order by Demuxer (FIFO)
                   ▼
[JavaFX Application Thread] (AnimationTimer / pumpPlayback)
Non-blocking peek() & isDone() poll -> displayPreparedFrame(now)
```

### Components

1.  **Dedicated Demuxer Thread (`VideoPlayer-Demuxer`)**:
    
    -   Sole owner of the FFmpeg demuxer read loop (`grabFrame()`).
    -   Deep-clones each raw video frame (`rawFrame.clone()`) before passing it off to worker threads, preventing race conditions and native buffer corruption.
    -   Routes demuxed audio samples immediately into thread-safe pending audio queues.
    -   Pushes an asynchronous task wrapper (`EpochFuture`) into the bounded pipeline queue.
2.  **Parallel Worker Thread Pool (`VideoPlayer-Worker`)**:
    
    -   Sized to `Math.max(2, Runtime.getRuntime().availableProcessors() - 1)` worker threads at `Thread.NORM_PRIORITY - 1` to reserve dedicated CPU capacity for the JavaFX QuantumRenderer and GUI event thread.
    -   Executes the full sequence of `FrameProcessor` effects in parallel across distinct frames.
    -   Employs `ThreadLocal<JavaFXFrameConverter>` instances to perform parallel hardware/software colorspace conversions without lock contention or converter state sharing.
    -   Safely frees native frame memory inside `finally { frameToProcess.close(); }`.
3.  **In-Order Bounded Pipeline Queue (`framePipelineQueue`)**:
    
    -   A `BlockingQueue<EpochFuture>` bounded to 16 frames provides backpressure to prevent unbounded heap growth while buffering ~660 ms of video ahead of time.
    -   Guarantees strict FIFO presentation ordering on the UI thread regardless of worker thread scheduling variances.
4.  **Single-Threaded Reference Path**:
    
    -   The original single-threaded execution model is preserved and cleanly isolated via constructor overload (`boolean multiThreaded`), allowing direct comparative testing and verification in `mvn verify`.

### Architectural Evaluation: Worker Pool vs. Staged Filter Pipeline (Amdahl's Law)

An alternative architecture often considered for video processing is a **Staged Filter Pipeline**, where each filter stage runs in its own dedicated thread with intermediate queues connecting successive stages. While conceptually intuitive, a staged pipeline suffers from fundamental concurrency bottlenecks under **Amdahl's Law**:

1.  **Slowest-Stage Bottleneck (Throughput Throttling)**: In a staged pipeline, the overall throughput cannot exceed the rate of the single slowest stage (e.g. `FrameBleeder`). Faster stages (`FrameFlickerer`, `FrameYellower`) finish early and starve or block, leaving CPU cores idle regardless of how many cores are provisioned.
2.  **Dynamic Workload Imbalance**: Processing time per filter varies across frames based on visual contents (e.g., number of bright pixels in `FrameBleeder`, particle counts in `FramePepperer`). Staged pipelines cannot redistribute idle CPU capacity between stages to absorb transient spikes.
3.  **Queue synchronisation & Cache Overhead**: Chaining 11+ intermediate blocking queues introduces massive thread context switching, thread synchronisation latency, and CPU cache invalidations as frame data bounces between cores.

In contrast, our **Worker Pool + Bounded Ordered Queue** architecture assigns the *entire* filter chain of an individual frame to an available worker thread:

-   **Maximum Multi-Core Saturation**: Every worker thread operates at 100% capacity on independent frames, achieving near-linear scaling up to the parallel limit of Amdahl's Law.
-   **Minimal Queue Overhead**: Exactly one queue submission per frame into `framePipelineQueue`.
-   **Deterministic In-Order Presentation**: Bounded queue with FIFO order guarantees that frames are delivered to JavaFX in exact display order without reordering complexity.

---

## 3. Concurrency Safety & Thread Confinement

### FFmpeg Grabber Confinement (`grabberLock`)

The underlying native `FFmpegFrameGrabber` is not thread-safe. All grabber invocations—including decoding (`grab()`), repositioning (`setTimestamp()`), starting (`start()`), and closing (`close()`)—are strictly guarded by `synchronized (grabberLock)`. Crucially, `grabberLock` is **never** held while blocking on queue operations (`put()` / `take()`), guaranteeing freedom from deadlock.

### Thread-Safe Audio Scheduling (`pendingAudio`)

Audio demuxing runs asynchronously on the demuxer thread while audio buffer submission runs synchronously on the JavaFX rendering thread inside `writeDueAudio(now)`. The `pendingAudio` queue is strictly synchronised to prevent packet corruption during playback, volume/mute toggles, or seek flushes.

### Epoch-Based Rapid-Seek Invalidation

During rapid seeking or user scrubbing, worker threads could waste CPU cycles processing obsolete frames. We implement atomic epoch tracking (`AtomicLong currentEpoch`):

-   Each seek or stop operation atomically increments `currentEpoch.getAndIncrement()`.
-   Unstarted worker tasks in `workerPool.getQueue()` are instantly cleared.
-   In-flight worker threads perform **intra-pipeline cancellation**: checking `if (epoch != currentEpoch.get()) return null;` before *each* `FrameProcessor.process(...)` invocation.
-   Obsolete frames already in the queue are immediately identified and discarded by the display loop.

### Clean End-of-File (EOF) Lifecycle

Because the demuxer operates up to 16 frames ahead of the presentation clock, reaching EOF (`grab() == null`) occurs before the final frames have been displayed:

-   The demuxer thread enqueues a non-null `EpochFuture.eof(epoch)` sentinel token into `framePipelineQueue`.
-   The demuxer thread parks on `demuxerSignal.wait()` instead of terminating.
-   The UI thread cleanly drains and displays all remaining frames and flushes trailing audio.
-   Only when the UI thread consumes the EOF sentinel does the player transition to `Playback finished`.
-   When seeking backwards (`backFiveSeconds()`) or restarting (`startOver()`) from EOF, `demuxerSignal.notifyAll()` cleanly unparks the demuxer thread and resumes streaming with zero deadlocks.

---

## 4. Thread-Safe FrameProcessors & Scratch State Optimisation

Under concurrent processing, multiple worker threads execute `process(Frame, InfoFrame)` simultaneously on shared `FrameProcessor` instances. Mutable instance buffers create severe data races and visual artifacts, while allocating arrays per frame leads to catastrophic GC heap thrashing.

To eliminate thread contention while avoiding allocation overhead, mutable scratch buffers and segment caches were refactored using thread confinement and concurrent maps:

| FrameProcessor | Thread-Safety Hazard | Concurrency & Memory Optimisation Strategy |
|---|---|---|
| `FrameJitterer` | Shared `sourcePixels` byte array reused across frames | Replaced with `ThreadLocal<byte[]>` sized dynamically to image stride. |
| `FrameMottler` | Shared `damageMaskCache` causing multi-worker contention and periodic 72-frame segment cache-miss spikes; shared `blockBlueScaleIndex` | Replaced segment cache with instance-shared `ConcurrentHashMap<Integer, double[]> damageMaskBySegment` (computed once across threads, reusing segment + 1 as mask A); scale indices allocated as thread-confined stack local array; multi-scale noise buffers pooled via `ThreadLocal<MottleScratch>`. |
| `FrameBleeder` | Shared mutable `scratch` buffers (`brightMask`, `horizontalBlurMask`, `blurredMask`) and repeated per-frame `ByteBuffer` pixel reading | Encapsulated all 3 blur masks and a pre-allocated `byte[]` pixel buffer in `ThreadLocal<BleedScratch>` to eliminate data races and heap allocation churn. |

All remaining processors (`FrameBlackAndWhiter`, `FrameDuster`, `FrameFlickerer`, `FrameNumberer`, `FramePepperer`, `FrameScratcher`, `FrameVignetter`, `FrameYellower`) are inherently stateless or operate purely on thread-confined local variables.

---

## 5. Verification & Performance Benchmarking

### Automated Test Suite (25 Tests, 100% Pass Rate)

1.  **`CommandLineControllerTest` (21 Tests)**:
    
    -   Verifies exact character-for-character `--help` output matching the COMP3011 specification.
    -   Tests short flags, long flags, stacked flags (`-jfddn`, `-nssnfwyvdjmbp`), repeated filters, monitor options (`-1`, `-2`), and error handling (`shouldLaunchApplication() == false`).
    -   Validates combined invalid options with `-h` and `--help` for correct error reporting and exit codes.
2.  **`FrameProcessorConcurrencyTest` (2 Tests)**:
    
    -   **Bit-Exact Determinism**: Verifies that running frames through all 11 `FrameProcessor` subclasses across parallel worker threads produces pixel-for-pixel bit-exact results identical to sequential single-threaded execution.
    -   **Thread-Sharing Stress Test**: Concurrently executes 200 iterations over shared instances of `FrameJitterer`, `FrameMottler`, and `FrameBleeder` across 16 worker threads with zero race conditions.
3.  **`PerformanceComparisonTest` (2 Tests)**:
    
    -   **720p HD Concurrency Benchmark**: Stresses a massive 26-filter specification chain (`-nssnfwyvdjmbpnssnfwyvdjmbp`) on true 720p HD frames (`1280x720`).
    -   **EOF & Rapid-Seek Lifecycle**: Confirms display-aligned EOF termination and seamless backward seeking recovery.

### Measured Concurrency Benchmark Results

```
================================================================================
  COMP3011 Video Player 720p HD Benchmark Report (-nssnfwyvdjmbpnssnfwyvdjmbp)  
================================================================================
Architecture       | Total Time    | Avg ms/Frame   | Effective FPS   | Late Frames
-------------------+---------------+----------------+-----------------+------------
Single-Threaded    |        974 ms |       40.58 ms |        24.64 fps |           0
Multi-Threaded     |        288 ms |       12.00 ms |        83.28 fps |           0
================================================================================
Observed Concurrency Speedup: 3.38x faster
================================================================================
```

#### Real Time 24fps Playback Budget Analysis (41.67 ms/frame)

-   **Single threaded Bottleneck**: Under 720p HD resolution with a heavy 26-processor filter chain (`-nssnfwyvdjmbpnssnfwyvdjmbp`), single-threaded processing consumes **40.58 ms/frame** on average, leaving virtually 0 ms of spare headroom. On complex frames, it breaches the 41.67 ms budget, resulting in frame stutter.
-   **Multithreaded Headroom**: The multithreaded worker pipeline processes frames in **12.00 ms/frame** (**83.28 fps**), providing over **29 ms of spare headroom per frame** within the 41.67 ms budget. This achieves a **3.38x concurrency speedup** on multi-core processors with zero late frames.

---

## 6. Building and Running

### Prerequisites

-   JDK 25 (`--enable-native-access=ALL-UNNAMED` enabled)
-   Apache Maven 3.8+

### Build & Package

```bash
# Compile and run all unit & integration tests
mvn test

# Package standard JAR
mvn package

# Build standalone shaded executable fat JAR
mvn package -Pfat-jar
```

### Running the Application

```bash
# Launch GUI with visual effects applied
mvn javafx:run -Djavafx.args="-nssnfwyvdjmbp path/to/video.mp4"

# Print CLI help documentation
mvn javafx:run -Djavafx.args="--help"
```
