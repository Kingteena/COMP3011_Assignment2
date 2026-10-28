/*
 * Testing suite for Adelaide University COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Vihara Girihagama, in collaboration with Gemini-3.8 Flash
 *
 * Copyright 2026 Vihara Girihagama
 */
package comp3011;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression and validation test suite for {@link CommandLineController}.
 */
public class CommandLineControllerTest {

    private final PrintStream standardOut = System.out;
    @TempDir
    File tempDir;
    private ByteArrayOutputStream capturedOut;
    private File validVideoFile;

    @BeforeEach
    void setUp() throws IOException {
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        validVideoFile = new File(tempDir, "sample_test_video.mp4");
        assertTrue(validVideoFile.createNewFile(), "Failed to create temporary test video file");
    }

    @AfterEach
    void tearDown() {
        System.setOut(standardOut);
    }

    private String getNormalizedOutput() {
        return capturedOut.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("Verify character-for-character exact help text output for --help")
    void testHelpOutputCharacterForCharacterLongOption() {
        CommandLineController controller = new CommandLineController(new String[]{"--help"});

        assertEquals(0, controller.getExitCode());
        assertNull(controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());

        String expectedHelp =
                """
                        Usage: VideoPlayer [options] [video-file]
                        
                        Options:
                          -h, --help         Show this help message
                          -a, --audio        Play audio
                          -x, --maximise     Open the player maximised
                          -1, --monitor-1    Open the player on display 1
                          -2, --monitor-2    Open the player on display 2
                        
                        Frame processors:
                          -n, --number-frames    Render the frame number onto each frame
                          -s, --scratch-frames   Render vertical film scratches
                          -f, --flicker-frames   Randomly dim frames
                          -w, --black-and-white  Convert frames to black and white
                          -y, --yellow-frames    Apply a warmer colour temperature
                          -v, --vignette-frames  Darken the frame edges
                          -d, --dust-frames      Render dust and hair marks
                          -j, --jitter-frames    Randomly displace frames by a few pixels
                          -m, --mottle-frames    Add cloudy emulsion mottling
                          -b, --bleed-frames     Bleed light into frames
                          -p, --pepper-frames    Pepper frames with dark spots/blotches
                        
                        Frame processors are applied in command-line order and may be repeated.
                        Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,
                        converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.
                        """;

        assertEquals(expectedHelp, getNormalizedOutput());
    }

    @Test
    @DisplayName("Verify -h produces identical help output to --help")
    void testHelpOutputShortOption() {
        CommandLineController controller = new CommandLineController(new String[]{"-h"});

        assertEquals(0, controller.getExitCode());
        assertNull(controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());

        String expectedPrefix = "Usage: VideoPlayer [options] [video-file]\n";
        assertTrue(getNormalizedOutput().startsWith(expectedPrefix));
    }

    @Test
    @DisplayName("Verify -h combined with a valid video file prints help and exits with code 0 without launching GUI")
    void testHelpWithVideoFileDoesNotLaunch() {
        CommandLineController controller =
                new CommandLineController(new String[]{"-h", validVideoFile.getAbsolutePath()});

        assertEquals(0, controller.getExitCode());
        assertNull(controller.getErrorMessage());
        assertNotNull(controller.getVideoFile());
        assertFalse(
                controller.shouldLaunchApplication(),
                "shouldLaunchApplication() must be false when help is requested");

        String expectedPrefix = "Usage: VideoPlayer [options] [video-file]\n";
        assertTrue(getNormalizedOutput().startsWith(expectedPrefix));
    }

    @Test
    @DisplayName("Verify --help combined with a valid video file does not launch application")
    void testLongHelpWithVideoFileDoesNotLaunch() {
        CommandLineController controller =
                new CommandLineController(new String[]{"--help", validVideoFile.getAbsolutePath()});

        assertEquals(0, controller.getExitCode());
        assertNull(controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    @DisplayName("Verify general short and long flags (-a, --audio, -x, --maximise, -1, -2)")
    void testGeneralFlags() {
        // Audio flag
        CommandLineController c1 =
                new CommandLineController(new String[]{"-a", validVideoFile.getAbsolutePath()});
        assertTrue(c1.isAudioRequested());
        assertTrue(c1.shouldLaunchApplication());

        CommandLineController c2 =
                new CommandLineController(new String[]{"--audio", validVideoFile.getAbsolutePath()});
        assertTrue(c2.isAudioRequested());

        // Maximise flag
        CommandLineController c3 =
                new CommandLineController(new String[]{"-x", validVideoFile.getAbsolutePath()});
        assertTrue(c3.isMaximiseRequested());

        CommandLineController c4 =
                new CommandLineController(new String[]{"--maximise", validVideoFile.getAbsolutePath()});
        assertTrue(c4.isMaximiseRequested());

        // Monitor flags
        CommandLineController c5 =
                new CommandLineController(new String[]{"-1", validVideoFile.getAbsolutePath()});
        assertEquals(1, c5.getDisplayId());

        CommandLineController c6 =
                new CommandLineController(new String[]{"--monitor-1", validVideoFile.getAbsolutePath()});
        assertEquals(1, c6.getDisplayId());

        CommandLineController c7 =
                new CommandLineController(new String[]{"-2", validVideoFile.getAbsolutePath()});
        assertEquals(2, c7.getDisplayId());

        CommandLineController c8 =
                new CommandLineController(new String[]{"--monitor-2", validVideoFile.getAbsolutePath()});
        assertEquals(2, c8.getDisplayId());
    }

    @Test
    @DisplayName("Verify all 11 individual FrameProcessors with short and long flags")
    void testAllIndividualFrameProcessors() {
        String[][] processorSpecs = {
                {"-n", "--number-frames", FrameNumberer.class.getName()},
                {"-s", "--scratch-frames", FrameScratcher.class.getName()},
                {"-f", "--flicker-frames", FrameFlickerer.class.getName()},
                {"-w", "--black-and-white", FrameBlackAndWhiter.class.getName()},
                {"-y", "--yellow-frames", FrameYellower.class.getName()},
                {"-v", "--vignette-frames", FrameVignetter.class.getName()},
                {"-d", "--dust-frames", FrameDuster.class.getName()},
                {"-j", "--jitter-frames", FrameJitterer.class.getName()},
                {"-m", "--mottle-frames", FrameMottler.class.getName()},
                {"-b", "--bleed-frames", FrameBleeder.class.getName()},
                {"-p", "--pepper-frames", FramePepperer.class.getName()},
        };

        for (String[] spec : processorSpecs) {
            String shortFlag = spec[0];
            String longFlag = spec[1];
            String expectedClass = spec[2];

            // Test short flag
            CommandLineController cShort =
                    new CommandLineController(new String[]{shortFlag, validVideoFile.getAbsolutePath()});
            assertEquals(0, cShort.getExitCode());
            assertEquals(1, cShort.getFrameProcessors().size());
            assertEquals(expectedClass, cShort.getFrameProcessors().getFirst().getClass().getName());

            // Test long flag
            CommandLineController cLong =
                    new CommandLineController(new String[]{longFlag, validVideoFile.getAbsolutePath()});
            assertEquals(0, cLong.getExitCode());
            assertEquals(1, cLong.getFrameProcessors().size());
            assertEquals(expectedClass, cLong.getFrameProcessors().getFirst().getClass().getName());
        }
    }

    @Test
    @DisplayName("Verify stacked short flags: -jfddn with exact ordering and repetition")
    void testStackedFlagsExample1() {
        CommandLineController controller =
                new CommandLineController(new String[]{"-jfddn", validVideoFile.getAbsolutePath()});

        assertEquals(0, controller.getExitCode());
        assertNull(controller.getErrorMessage());
        assertTrue(controller.shouldLaunchApplication());

        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(5, processors.size());

        assertInstanceOf(FrameJitterer.class, processors.get(0));
        assertInstanceOf(FrameFlickerer.class, processors.get(1));
        assertInstanceOf(FrameDuster.class, processors.get(2));
        assertInstanceOf(FrameDuster.class, processors.get(3));
        assertInstanceOf(FrameNumberer.class, processors.get(4));
    }

    @Test
    @DisplayName("Verify specification example -nssnfwyvdjmbp order and instance counts")
    void testSpecificationFullExample() {
        CommandLineController controller =
                new CommandLineController(
                        new String[]{"-nssnfwyvdjmbp", validVideoFile.getAbsolutePath()});

        assertEquals(0, controller.getExitCode());
        assertNull(controller.getErrorMessage());
        assertTrue(controller.shouldLaunchApplication());

        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(13, processors.size());

        assertInstanceOf(FrameNumberer.class, processors.get(0));
        assertInstanceOf(FrameScratcher.class, processors.get(1));
        assertInstanceOf(FrameScratcher.class, processors.get(2));
        assertInstanceOf(FrameNumberer.class, processors.get(3));
        assertInstanceOf(FrameFlickerer.class, processors.get(4));
        assertInstanceOf(FrameBlackAndWhiter.class, processors.get(5));
        assertInstanceOf(FrameYellower.class, processors.get(6));
        assertInstanceOf(FrameVignetter.class, processors.get(7));
        assertInstanceOf(FrameDuster.class, processors.get(8));
        assertInstanceOf(FrameJitterer.class, processors.get(9));
        assertInstanceOf(FrameMottler.class, processors.get(10));
        assertInstanceOf(FrameBleeder.class, processors.get(11));
        assertInstanceOf(FramePepperer.class, processors.get(12));
    }

    @Test
    @DisplayName("Verify stacked flags combining general options and processor flags (-axjf)")
    void testStackedMixedGeneralAndProcessorFlags() {
        CommandLineController controller =
                new CommandLineController(new String[]{"-axjf", validVideoFile.getAbsolutePath()});

        assertEquals(0, controller.getExitCode());
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());

        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(2, processors.size());
        assertInstanceOf(FrameJitterer.class, processors.get(0));
        assertInstanceOf(FrameFlickerer.class, processors.get(1));
    }

    @Test
    @DisplayName("Verify separate repeated flags preserve CLI order across separate arguments")
    void testSeparateRepeatedFlags() {
        CommandLineController controller =
                new CommandLineController(
                        new String[]{
                                "-n", "-s", "--scratch-frames", "-n", validVideoFile.getAbsolutePath()
                        });

        assertEquals(0, controller.getExitCode());
        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(4, processors.size());
        assertInstanceOf(FrameNumberer.class, processors.get(0));
        assertInstanceOf(FrameScratcher.class, processors.get(1));
        assertInstanceOf(FrameScratcher.class, processors.get(2));
        assertInstanceOf(FrameNumberer.class, processors.get(3));
    }

    @Test
    @DisplayName("Verify error on unknown short flag (-z)")
    void testUnknownShortFlag() {
        CommandLineController controller =
                new CommandLineController(new String[]{"-z", validVideoFile.getAbsolutePath()});

        assertEquals(1, controller.getExitCode());
        assertEquals("Unknown option: -z", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    @DisplayName("Verify error on unknown stacked short flag (-jfz)")
    void testUnknownStackedShortFlag() {
        CommandLineController controller =
                new CommandLineController(new String[]{"-jfz", validVideoFile.getAbsolutePath()});

        assertEquals(1, controller.getExitCode());
        assertEquals("Unknown option: -jfz", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    @DisplayName("Verify error on unknown long option (--unknown)")
    void testUnknownLongOption() {
        CommandLineController controller =
                new CommandLineController(new String[]{"--unknown", validVideoFile.getAbsolutePath()});

        assertEquals(1, controller.getExitCode());
        assertEquals("Unknown option: --unknown", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    @DisplayName("Verify error on bare hyphen (-)")
    void testBareHyphenOption() {
        CommandLineController controller =
                new CommandLineController(new String[]{"-", validVideoFile.getAbsolutePath()});

        assertEquals(1, controller.getExitCode());
        assertEquals("Unknown option: -", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    @DisplayName("Verify error when conflicting display monitors are specified")
    void testConflictingMonitorOptions() {
        // Separate flags: -1 and -2
        CommandLineController c1 =
                new CommandLineController(new String[]{"-1", "-2", validVideoFile.getAbsolutePath()});
        assertEquals(1, c1.getExitCode());
        assertEquals("Only one display option can be used", c1.getErrorMessage());

        // Long flags: --monitor-1 and --monitor-2
        CommandLineController c2 =
                new CommandLineController(
                        new String[]{"--monitor-1", "--monitor-2", validVideoFile.getAbsolutePath()});
        assertEquals(1, c2.getExitCode());
        assertEquals("Only one display option can be used", c2.getErrorMessage());

        // Stacked flag: -12
        CommandLineController c3 =
                new CommandLineController(new String[]{"-12", validVideoFile.getAbsolutePath()});
        assertEquals(1, c3.getExitCode());
        assertEquals("Only one display option can be used", c3.getErrorMessage());

        // Duplicate same monitor should be allowed
        CommandLineController c4 =
                new CommandLineController(new String[]{"-1", "-1", validVideoFile.getAbsolutePath()});
        assertEquals(0, c4.getExitCode());
        assertEquals(1, c4.getDisplayId());
    }

    @Test
    @DisplayName("Verify error when no video file is specified")
    void testMissingVideoFile() {
        // No arguments at all
        CommandLineController c1 = new CommandLineController(new String[]{});
        assertEquals(1, c1.getExitCode());
        assertEquals("No video file specified.", c1.getErrorMessage());
        assertFalse(c1.shouldLaunchApplication());

        // Only options, no file
        CommandLineController c2 = new CommandLineController(new String[]{"-a", "-x"});
        assertEquals(1, c2.getExitCode());
        assertEquals("No video file specified.", c2.getErrorMessage());
        assertFalse(c2.shouldLaunchApplication());
    }

    @Test
    @DisplayName("Verify TITAN T11-T16: options combined with -h or invalid args produce non-zero exit code and full help text")
    void testHelpCombinedWithInvalidArgsExitsNonZero() {
        // T11: Processor option + help without video file
        capturedOut.reset();
        CommandLineController c1 =
                new CommandLineController(new String[]{"--number-frames", "-h"});
        assertEquals(1, c1.getExitCode());
        assertEquals("No video file specified.", c1.getErrorMessage());
        assertFalse(c1.shouldLaunchApplication());
        assertTrue(getNormalizedOutput().contains("No video file specified."));
        assertTrue(getNormalizedOutput().contains("Frame processors are applied in command-line order"));

        // T12: Illegal long option with video file and --help
        capturedOut.reset();
        CommandLineController c2 =
                new CommandLineController(
                        new String[]{"--illegal-argument", validVideoFile.getAbsolutePath(), "--help"});
        assertEquals(1, c2.getExitCode());
        assertEquals("Unknown option: --illegal-argument", c2.getErrorMessage());
        assertFalse(c2.shouldLaunchApplication());
        assertTrue(getNormalizedOutput().contains("Unknown option: --illegal-argument"));
        assertTrue(getNormalizedOutput().contains("Frame processors are applied in command-line order"));

        // T13: Multiple video files with -h
        capturedOut.reset();
        CommandLineController c3 =
                new CommandLineController(
                        new String[]{validVideoFile.getAbsolutePath(), validVideoFile.getAbsolutePath(), "-h"});
        assertEquals(1, c3.getExitCode());
        assertEquals("Usage: VideoPlayer [options] [video-file]", c3.getErrorMessage());
        assertFalse(c3.shouldLaunchApplication());
        assertTrue(getNormalizedOutput().contains("Usage: VideoPlayer [options] [video-file]"));
        assertTrue(getNormalizedOutput().contains("Frame processors are applied in command-line order"));

        // T14: -h with video file and miscapitalized option
        capturedOut.reset();
        CommandLineController c4 =
                new CommandLineController(
                        new String[]{"-h", validVideoFile.getAbsolutePath(), "--Flicker-frames"});
        assertEquals(1, c4.getExitCode());
        assertEquals("Unknown option: --Flicker-frames", c4.getErrorMessage());
        assertFalse(c4.shouldLaunchApplication());
        assertTrue(getNormalizedOutput().contains("Unknown option: --Flicker-frames"));
        assertTrue(getNormalizedOutput().contains("Frame processors are applied in command-line order"));

        // T15/T16: Stacked options with invalid character and --help
        capturedOut.reset();
        CommandLineController c5 =
                new CommandLineController(
                        new String[]{"-sfbnz", validVideoFile.getAbsolutePath(), "--help"});
        assertEquals(1, c5.getExitCode());
        assertEquals("Unknown option: -sfbnz", c5.getErrorMessage());
        assertFalse(c5.shouldLaunchApplication());
        assertTrue(getNormalizedOutput().contains("Unknown option: -sfbnz"));
        assertTrue(getNormalizedOutput().contains("Frame processors are applied in command-line order"));
    }

    @Test
    @DisplayName("Verify illegal argument (e.g. --illegal-argument) prints error and usage, and stops cleanly with exit code 1")
    void testIllegalArgumentPrintsUsageAndExitsCleanly() {
        CommandLineController controller =
                new CommandLineController(new String[]{"--illegal-argument", validVideoFile.getAbsolutePath()});

        assertEquals(1, controller.getExitCode(), "Exit code must be 1 on illegal argument");
        assertEquals("Unknown option: --illegal-argument", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication(), "Application must not launch on illegal argument");

        String output = getNormalizedOutput();
        assertTrue(output.contains("Unknown option: --illegal-argument"),
                "Output must report the unknown option error");
        assertTrue(output.contains("Usage: VideoPlayer [options] [video-file]"),
                "Output must print the usage message on illegal argument");
    }

    @Test
    @DisplayName("Verify standalone illegal argument (--illegal-argument) prints error, usage, and does not launch")
    void testStandaloneIllegalArgumentPrintsUsage() {
        CommandLineController controller =
                new CommandLineController(new String[]{"--illegal-argument"});

        assertEquals(1, controller.getExitCode());
        assertEquals("Unknown option: --illegal-argument", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());

        String output = getNormalizedOutput();
        assertTrue(output.contains("Unknown option: --illegal-argument"));
        assertTrue(output.contains("Usage: VideoPlayer [options] [video-file]"));
    }

    @Test
    @DisplayName("Verify error when non-existent video file is specified")
    void testNonExistentVideoFile() {
        File missing = new File(tempDir, "non_existent_movie.mp4");
        CommandLineController controller =
                new CommandLineController(new String[]{missing.getAbsolutePath()});

        assertEquals(1, controller.getExitCode());
        assertEquals("File not found: " + missing.getAbsolutePath(), controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    @DisplayName("Verify error when multiple video files are specified")
    void testMultipleVideoFiles() throws IOException {
        File secondVideo = new File(tempDir, "sample_second.mp4");
        assertTrue(secondVideo.createNewFile());

        CommandLineController controller =
                new CommandLineController(
                        new String[]{validVideoFile.getAbsolutePath(), secondVideo.getAbsolutePath()});

        assertEquals(1, controller.getExitCode());
        assertEquals("Usage: VideoPlayer [options] [video-file]", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }
}
