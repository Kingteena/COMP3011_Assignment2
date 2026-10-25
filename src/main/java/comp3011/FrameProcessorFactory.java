/*
 * Helper for Adelaide University COMP3011 Assignment 2.
 *
 * Author:
 *   1. Vihara Girihagama, in collaboration with Gemini-3.8 Flash
 *
 * Copyright 2026 Vihara Girihagama
 */
package comp3011;

import org.bytedeco.javacv.Frame;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Factory responsible for instantiating {@link FrameProcessor} effects from short CLI
 * character codes and long option names. Isolating object creation here keeps
 * {@link CommandLineController} decoupled from concrete effect implementations.
 *
 * <p>
 * To add a new frame processor, subclass {@link FrameProcessor} and implement
 * {@link FrameProcessor#initialise(InfoVideo)} and {@link FrameProcessor#process(Frame, InfoFrame)}.
 * Ensure {@code process()} is deterministic (seeded from {@code info.frameNumber()}) and avoids
 * mutating shared instance buffers so a single instance can safely process frames across multiple
 * worker threads. Then add a single {@code register(shortCode, longName, NewProcessor::new)}
 * entry in the static block below and document the flag in {@link CommandLineController#printHelp()}.
 * </p>
 */
public final class FrameProcessorFactory {

    private static final Map<Character, Supplier<FrameProcessor>> SHORT_MAP = new HashMap<>();
    private static final Map<String, Supplier<FrameProcessor>> LONG_MAP = new HashMap<>();

    static {
        register('n', "number-frames", FrameNumberer::new);
        register('s', "scratch-frames", FrameScratcher::new);
        register('f', "flicker-frames", FrameFlickerer::new);
        register('w', "black-and-white", FrameBlackAndWhiter::new);
        register('y', "yellow-frames", FrameYellower::new);
        register('v', "vignette-frames", FrameVignetter::new);
        register('d', "dust-frames", FrameDuster::new);
        register('j', "jitter-frames", FrameJitterer::new);
        register('m', "mottle-frames", FrameMottler::new);
        register('b', "bleed-frames", FrameBleeder::new);
        register('p', "pepper-frames", FramePepperer::new);
    }

    private FrameProcessorFactory() {
        // Utility class
    }

    private static void register(char shortCode, String longName, Supplier<FrameProcessor> supplier) {
        SHORT_MAP.put(shortCode, supplier);
        LONG_MAP.put(longName, supplier);
    }

    /**
     * Instantiates a new FrameProcessor for the given single-character code.
     */
    public static Optional<FrameProcessor> create(char shortCode) {
        Supplier<FrameProcessor> supplier = SHORT_MAP.get(shortCode);
        return supplier != null ? Optional.of(supplier.get()) : Optional.empty();
    }

    /**
     * Instantiates a new FrameProcessor for the given long option name.
     */
    public static Optional<FrameProcessor> create(String longName) {
        Supplier<FrameProcessor> supplier = LONG_MAP.get(longName);
        return supplier != null ? Optional.of(supplier.get()) : Optional.empty();
    }
}
