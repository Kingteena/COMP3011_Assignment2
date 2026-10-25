/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Vihara Girihagama, in collaboration with Gemini-3.8 Flash
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling, and frame processing.
 * Supports both single-threaded cooperative multi-tasking and high-performance multi-threaded
 * pipelining with deterministic frame ordering and thread-safe FFmpeg synchronization.</p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int PIPELINE_QUEUE_CAPACITY = 16;

    private final boolean multiThreaded;
    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // Concurrency controls for multi-threaded pipelining
    private final Object grabberLock = new Object();
    private final AtomicLong currentEpoch = new AtomicLong(0);
    private final BlockingQueue<EpochFuture> framePipelineQueue = new LinkedBlockingQueue<>(PIPELINE_QUEUE_CAPACITY);
    private final ThreadLocal<JavaFXFrameConverter> workerConverters = ThreadLocal.withInitial(JavaFXFrameConverter::new);

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.

    private final AnimationTimer playbackTimer =
     new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private ExecutorService workerPool;
    private Thread demuxerThread;
    private File videoFile;
    private FFmpegFrameGrabber grabber;
    private JavaFXFrameConverter converter; // Used in single-threaded fallback mode
    private AudioPlayer audioPlayer;
    private PreparedFrame preparedFrame;
    private boolean prepareNextFrameQueued;
    private boolean playbackOpen;
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    /**
     * Standard constructor enabling multi-threaded decoding and processing by default.
     */
    public VideoPlayerModel(boolean audioEnabled, BiConsumer<Integer, Integer> videoSizeChangedHandler, Consumer<Image> frameReadyHandler, Consumer<String> statusChangedHandler, BiConsumer<Boolean, Boolean> playbackStateChangedHandler, Consumer<Boolean> audioOutputStateChangedHandler, List<FrameProcessor> frameProcessors) {
        this(audioEnabled, videoSizeChangedHandler, frameReadyHandler, statusChangedHandler, playbackStateChangedHandler, audioOutputStateChangedHandler, frameProcessors, true);
    }

    /**
     * Overloaded constructor allowing explicit selection between multi-threaded and
     * single-threaded modes for comparative benchmarking and testing.
     */
    public VideoPlayerModel(boolean audioEnabled, BiConsumer<Integer, Integer> videoSizeChangedHandler, Consumer<Image> frameReadyHandler, Consumer<String> statusChangedHandler, BiConsumer<Boolean, Boolean> playbackStateChangedHandler, Consumer<Boolean> audioOutputStateChangedHandler, List<FrameProcessor> frameProcessors, boolean multiThreaded) {
        this.audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = frameProcessors;
        this.multiThreaded = multiThreaded;
    }

    public boolean isMultiThreaded() {
        return multiThreaded;
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        synchronized (pendingAudio) {
            pendingAudio.clear();
        }
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        currentEpoch.incrementAndGet();
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        currentEpoch.incrementAndGet();
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST ? relativeSeekBaseUs : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        long newEpoch = currentEpoch.incrementAndGet();
        clearPipelineQueue();

        try {
            synchronized (grabberLock) {
                grabber.setTimestamp(grabTimestampUs);
            }
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            if (!multiThreaded) {
                prepareNextFrame();
            }
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        currentEpoch.incrementAndGet();
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST ? initialRelativeSeekBaseUs : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;

            if (multiThreaded) {
                startMultiThreadedPipeline();
            }

            playbackTimer.start();
            if (!multiThreaded) {
                prepareNextFrame();
            }
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0 ? (int) Math.round(1_000_000.0 / frameRate) : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0 ? durationUs : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void startMultiThreadedPipeline() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        workerPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "VideoPlayer-Worker");
            t.setDaemon(true);
            return t;
        });

        demuxerThread = new Thread(this::runDemuxerLoop, "VideoPlayer-Demuxer");
        demuxerThread.setDaemon(true);
        demuxerThread.start();
    }

    private void runDemuxerLoop() {
        while (playbackOpen && !Thread.currentThread().isInterrupted()) {
            long epoch = currentEpoch.get();
            Frame rawFrame;

            try {
                synchronized (grabberLock) {
                    if (!playbackOpen || grabber == null) {
                        break;
                    }
                    rawFrame = grabFrame();
                }
            } catch (Exception e) {
                if (playbackOpen) {
                    Platform.runLater(() -> handlePlaybackError(e));
                }
                break;
            }

            if (rawFrame == null) {
                Platform.runLater(this::finishPlayback);
                break;
            }

            if (epoch != currentEpoch.get()) {
                continue;
            }

            long timestampUs;
            int frameNumber;
            synchronized (grabberLock) {
                if (!playbackOpen || grabber == null) {
                    break;
                }
                timestampUs = grabber.getTimestamp();
                frameNumber = grabber.getFrameNumber();
            }

            if (audioAvailable && rawFrame.samples != null) {
                queueAudio(timestampUs, rawFrame);
            }

            if (rawFrame.image == null) {
                continue;
            }

            // Ensure frame processors are initialized with video metadata
            if (!frameProcessorsInitialised) {
                try {
                    initialiseFrameProcessors(new InfoVideo(mediaName(videoFile), totalVideoFrames, rawFrame.imageWidth, rawFrame.imageHeight, rawFrame.imageDepth, rawFrame.imageChannels, rawFrame.imageStride, frameRate, intFrameRate, videoFrameDurationUs, grabber.getPixelFormat()));
                    frameProcessorsInitialised = true;
                } catch (Exception e) {
                    Platform.runLater(() -> handlePlaybackError(e));
                    break;
                }
            }

            // Deep-clone video frame so subsequent grabber.grab() calls do not corrupt buffer
            Frame frameToProcess = rawFrame.clone();

            CompletableFuture<PreparedFrame> future = CompletableFuture.supplyAsync(() -> {
                try {
                    if (epoch != currentEpoch.get()) {
                        return null;
                    }

                    InfoFrame info = new InfoFrame(frameNumber, timestampUs);
                    processFrame(frameToProcess, info);

                    JavaFXFrameConverter localConverter = workerConverters.get();
                    Image image = localConverter.convert(frameToProcess);

                    long targetTimeNs = computeTargetTimeNs(timestampUs);
                    long relativeTimestampUs = firstTimestampUs != NO_SEEK_REQUEST ? Math.max(0, timestampUs - firstTimestampUs) : 0;
                    long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;

                    return new PreparedFrame(image, frameNumber, timestampUs, logicalTimestampUs, targetTimeNs, System.nanoTime());
                } catch (Exception e) {
                    throw new CompletionException(e);
                } finally {
                    frameToProcess.close();
                }
            }, workerPool);

            try {
                framePipelineQueue.put(new EpochFuture(epoch, future));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private synchronized long computeTargetTimeNs(long timestampUs) {
        if (firstTimestampUs == NO_SEEK_REQUEST) {
            firstTimestampUs = timestampUs;
            playbackStartNs = System.nanoTime();
            if (pauseRequested) {
                pauseStartedNs = playbackStartNs;
            }
        }
        long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
        return playbackStartNs + relativeTimestampUs * 1_000L;
    }

    private void clearPipelineQueue() {
        EpochFuture ef;
        while ((ef = framePipelineQueue.poll()) != null) {
            ef.future().cancel(true);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        synchronized (pendingAudio) {
            pendingAudio.clear();
        }
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    private void prepareNextFrame() {
        if (!playbackOpen || preparedFrame != null) {
            return;
        }

        try {
            preparedFrame = readNextVideoFrame();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private PreparedFrame readNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                finishPlayback();
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(mediaName(videoFile), totalVideoFrames, frame.imageWidth, frame.imageHeight, frame.imageDepth, frame.imageChannels, frame.imageStride, frameRate, intFrameRate, videoFrameDurationUs, grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            processFrame(frame, info);

            Image image = converter.convert(frame);
            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            return new PreparedFrame(image, frameNumber, timestampUs, logicalTimestampUs, targetTimeNs, System.nanoTime());
        }

        return null;
    }

    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);

        if (multiThreaded) {
            pumpMultiThreadedPlayback(now);
        } else {
            pumpSingleThreadedPlayback(now);
        }
    }

    private void pumpSingleThreadedPlayback(long now) {
        if (preparedFrame != null && preparedFrame.targetTimeNs() <= now) {
            displayPreparedFrame(now);
        }

        if (!pauseRequested && preparedFrame == null && !prepareNextFrameQueued) {
            prepareNextFrameQueued = true;
            Platform.runLater(() -> {
                prepareNextFrameQueued = false;

                if (!pauseRequested) {
                    prepareNextFrame();
                }
            });
        }
    }

    private void pumpMultiThreadedPlayback(long now) {
        long epoch = currentEpoch.get();

        if (preparedFrame != null) {
            if (preparedFrame.targetTimeNs() <= now) {
                displayPreparedFrame(now);
            } else {
                return; // Current frame is not due yet
            }
        }

        while (preparedFrame == null) {
            EpochFuture head = framePipelineQueue.peek();
            if (head == null) {
                break;
            }

            if (head.epoch() != epoch) {
                framePipelineQueue.poll();
                head.future().cancel(true);
                continue;
            }

            if (!head.future().isDone()) {
                break; // Head frame is still being processed
            }

            framePipelineQueue.poll();
            try {
                PreparedFrame frame = head.future().join();
                if (frame != null) {
                    if (frame.targetTimeNs() <= now) {
                        preparedFrame = frame;
                        displayPreparedFrame(now);
                    } else {
                        preparedFrame = frame;
                    }
                    break;
                }
            } catch (Exception e) {
                handlePlaybackError(e);
                break;
            }
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be
        // monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n", remainingUs, videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n", remainingUs, videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            synchronized (pendingAudio) {
                pendingAudio.add(new PendingAudio(timestampUs, samples));
            }
        }
    }

    private void writeDueAudio(long now) {
        if (!audioOutputEnabled || audioPlayer == null) {
            synchronized (pendingAudio) {
                pendingAudio.clear();
            }
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        synchronized (pendingAudio) {
            while (!pendingAudio.isEmpty()) {
                PendingAudio audio = pendingAudio.peek();
                if (audio.timestampUs() > dueTimestampUs) {
                    return;
                }

                int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
                if (written <= 0) {
                    return;
                }

                audio.advance(written);
                if (!audio.finished()) {
                    return;
                }
                pendingAudio.remove();
            }
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST ? videoDurationUs : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;

        clearPipelineQueue();

        if (demuxerThread != null) {
            demuxerThread.interrupt();
            demuxerThread = null;
        }

        if (workerPool != null) {
            workerPool.shutdownNow();
            workerPool = null;
        }

        synchronized (pendingAudio) {
            pendingAudio.clear();
        }
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        synchronized (grabberLock) {
            if (grabber != null) {
                try {
                    grabber.stop();
                } catch (Exception e) {
                    // Resource being closed
                }
                try {
                    grabber.close();
                } catch (Exception e) {
                    // Resource being closed
                }
                grabber = null;
            }
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record PreparedFrame(Image image, int frameNumber, long mediaTimestampUs, long logicalTimestampUs,
                                 long targetTimeNs, long preparedAtNs) {
    }

    private record EpochFuture(long epoch, CompletableFuture<PreparedFrame> future) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
