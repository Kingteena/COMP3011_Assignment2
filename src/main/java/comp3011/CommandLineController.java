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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's
 * command-line arguments.
 *
 * <p>Extracts the video file and supported launch options, reports invalid input or help text, and
 * exposes the resulting launch configuration via methods to the {@link VideoPlayerApp}.
 */
public class CommandLineController {
    private final String[] args;
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();
    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        // Defensive copy of the input array prevents external callers from mutating parsed arguments.
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        } else if (errorMessage != null && !errorMessage.startsWith("Usage:")) {
            printUsage();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            if (errorMessage == null) {
                errorMessage = "Only one display option can be used";
            }
            return;
        }
        this.displayId = displayId;
    }

    public List<FrameProcessor> getFrameProcessors() {
        // Returning an unmodifiable view guarantees the parsed CLI filter sequence cannot be mutated.
        return Collections.unmodifiableList(frameProcessors);
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        // Suppress GUI launch when help is requested, even if a valid video file was provided on the CLI.
        return errorMessage == null && videoFile != null && !helpRequested;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (arg.startsWith("-") && arg.length() > 1) {
                parseShortOptions(arg);
            } else if (arg.startsWith("-")) {
                if (errorMessage == null) {
                    errorMessage = "Unknown option: " + arg;
                }
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            if (errorMessage == null) {
                errorMessage = "Usage: VideoPlayer [options] [video-file]";
            }
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.getFirst());
            if (!videoFile.isFile()) {
                if (errorMessage == null) {
                    errorMessage = "File not found: " + videoFile.getPath();
                }
                videoFile = null;
            }
        } else {
            if (!helpRequested || !frameProcessors.isEmpty() || audioRequested || maximiseRequested || displayId != null) {
                if (errorMessage == null) {
                    errorMessage = "No video file specified.";
                }
            }
        }
    }

    private void parseLongOption(String arg) {
        String opt = arg.substring(2);
        switch (opt) {
            case "help" -> helpRequested = true;
            case "audio" -> audioRequested = true;
            case "maximise" -> maximiseRequested = true;
            case "monitor-1" -> setDisplayId(1);
            case "monitor-2" -> setDisplayId(2);
            default -> {
                // Processor instantiation is delegated to FrameProcessorFactory (Open-Closed Principle),
                // keeping CLI syntax parsing decoupled from concrete effect implementations.
                Optional<FrameProcessor> processor = FrameProcessorFactory.create(opt);
                if (processor.isPresent()) {
                    frameProcessors.add(processor.get());
                } else if (errorMessage == null) {
                    errorMessage = "Unknown option: " + arg;
                }
            }
        }
    }

    private void parseShortOptions(String arg) {
        for (int i = 1; i < arg.length(); i++) {
            char c = arg.charAt(i);
            switch (c) {
                case 'h' -> helpRequested = true;
                case 'a' -> audioRequested = true;
                case 'x' -> maximiseRequested = true;
                case '1' -> setDisplayId(1);
                case '2' -> setDisplayId(2);
                default -> {
                    // Delegated to FrameProcessorFactory to decouple CLI parsing from effect classes.
                    Optional<FrameProcessor> processor = FrameProcessorFactory.create(c);
                    if (processor.isPresent()) {
                        frameProcessors.add(processor.get());
                    } else if (errorMessage == null) {
                        errorMessage = "Unknown option: " + arg;
                    }
                }
            }
        }
    }

    public void printUsage() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
    }

    private void printHelp() {
        printUsage();
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println(
                "Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println(
                "converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
