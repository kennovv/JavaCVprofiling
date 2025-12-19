package org.bytedeco.javacv.profiling;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.javacv.profiling.model.FileInfoDomain;

public class ProfilingApp {

  private static final long PID = ProcessHandle.current().pid();

  private static final String JCMD = "jcmd";

  // Timestamp formatter for filename-safe timestamps
  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

  // Shared counter to track completed tasks
  private static final AtomicLong completedTasks = new AtomicLong(0);

  // NMT dumping scheduler
  private static ScheduledExecutorService scheduler = null;
  private static ScheduledFuture<?> periodicNmtTask = null;
  private static final AtomicBoolean finalDumpCaptured = new AtomicBoolean(false);

  private static void doWork(final Path videoPath) {
    final FileInfoDomain fileInfo = new FileInfoDomain(videoPath);
    ThumbnailGenerator.processMediaFile(fileInfo);
    // Clean-up: remove thumbnail
    try {
      Files.delete(fileInfo.getThumbnailPath());
    } catch (IOException exc) {
      throw new IllegalStateException("Error deleting thumbnail", exc);
    }

    // Increment the counter and get the new value atomically
    long count = completedTasks.incrementAndGet();
    System.out.print(
        "\rTasks completed: " + count + " (by thread: " + Thread.currentThread().getName() + ")");
    System.out.flush();
  }

  private static String getCurrentTimestamp() {
    return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
  }

  private static void captureNmtDump(final String outputLog, final String errorLog,
      final String description) {
    try {
      System.out.println("\nCapturing NMT dump: " + description);
      final Process proc =
          new ProcessBuilder(JCMD, String.valueOf(PID), "VM.native_memory", "summary")
              .redirectOutput(new File(outputLog)).redirectError(new File(errorLog)).start();
      int exitCode = proc.waitFor();
      if (exitCode != 0) {
        System.err.println("Failed to capture NMT dump. Exit code: " + exitCode);
      }
    } catch (Exception exc) {
      System.err.println("Error capturing NMT dump: " + description);
      exc.printStackTrace();
    }
  }

  private static void capturePeriodicNmtDump() {
    captureNmtDumpWithTimestamp("nmt-periodic", "Periodic dump");
  }

  private static void captureFinalNmtDump(final String reason) {
    // Ensure final dump is only captured once
    if (finalDumpCaptured.compareAndSet(false, true)) {
      captureNmtDumpWithTimestamp("nmt-final", "Final (" + reason + ")");
    }
  }

  private static void captureNmtDumpWithTimestamp(final String baseName, final String description) {
    final String timestamp = getCurrentTimestamp();
    final String outputLog = baseName + "-" + timestamp + ".log";
    final String errorLog = baseName + "-" + timestamp + ".err";
    captureNmtDump(outputLog, errorLog, description);
  }

  private static void shutdownScheduler() {
    if (scheduler != null) {
      if (periodicNmtTask != null) {
        periodicNmtTask.cancel(false);
      }
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  public static void main(final String[] args) {
    if (args.length != 4) {
      System.err.println(
          "Usage: java -jar my-parallel-app.jar <numThreads> <numInvocations> <videoFilePath> <dumpIntervalSeconds>");
      System.err.println(
          "  dumpIntervalSeconds: NMT dump interval in seconds (0 to disable periodic dumps)");
      System.exit(1);
    }

    final int numThreads;
    final long numInvocations;
    final Path videoFilePath;
    final int dumpIntervalSeconds;

    try {
      numThreads = Integer.parseInt(args[0]);
      numInvocations = Long.parseLong(args[1]);
      videoFilePath = Paths.get(args[2]);
      dumpIntervalSeconds = Integer.parseInt(args[3]);

      if (numThreads <= 0 || numInvocations <= 0) {
        throw new IllegalArgumentException(
            "Thread count and invocation count must be positive integers.");
      }

      if (dumpIntervalSeconds < 0) {
        throw new IllegalArgumentException("Dump interval cannot be negative.");
      }

      if (!Files.exists(videoFilePath)) {
        throw new IOException("Video file does not exist: " + videoFilePath.toAbsolutePath());
      }
      if (!Files.isRegularFile(videoFilePath)) {
        throw new IOException("Path is not a regular file: " + videoFilePath.toAbsolutePath());
      }

    } catch (NumberFormatException exc) {
      System.err.println("Error: Arguments must be valid integers.");
      System.exit(1);
      return;
    } catch (IllegalArgumentException | IOException exc) {
      System.err.println("Error: " + exc.getMessage());
      System.exit(1);
      return;
    }

    // Print PID to help manual profiling tools connecting
    System.out.println("PID: " + PID);

    // Get the startup timestamp for consistent naming
    final String startupTimestamp = getCurrentTimestamp();

    // 1. Capture initial baseline NMT dump before starting any threads
    final String baselineOutputLog = "nmt-baseline-" + startupTimestamp + ".log";
    final String baselineErrorLog = "nmt-baseline-" + startupTimestamp + ".err";
    captureNmtDump(baselineOutputLog, baselineErrorLog, "Baseline (before thread start)");

    // Setup periodic NMT dumps if interval > 0
    if (dumpIntervalSeconds > 0) {
      scheduler = Executors.newSingleThreadScheduledExecutor();
      periodicNmtTask = scheduler.scheduleAtFixedRate(ProfilingApp::capturePeriodicNmtDump,
          dumpIntervalSeconds, dumpIntervalSeconds, TimeUnit.SECONDS);
      System.out.println("Periodic NMT dumps enabled every " + dumpIntervalSeconds + " seconds");
    }

    // Submit tasks
    final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    for (long i = 0; i < numInvocations; i++) {
      executor.submit(() -> doWork(videoFilePath));
    }
    executor.shutdown();

    // Wait for all tasks to complete (1 hour max)
    try {
      if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
        System.err.println("\nTimeout while waiting for tasks to complete.");
        executor.shutdownNow();

        // Shutdown scheduler and capture final NMT dump
        shutdownScheduler();
        captureFinalNmtDump("timeout");
      } else {
        // After all tasks are done, print a final summary
        System.out.println("\nAll tasks completed. Final count: " + completedTasks.get());
        System.out.println("Processed video file: " + videoFilePath.toAbsolutePath());

        // Shutdown scheduler
        shutdownScheduler();
        // Capture final NMT dump before exit
        captureFinalNmtDump("normal completion");
      }
    } catch (InterruptedException e) {
      System.err.println("\nInterrupted while waiting for executor to terminate.");
      executor.shutdownNow();
      Thread.currentThread().interrupt();

      // Shutdown scheduler and capture final NMT dump
      shutdownScheduler();
      captureFinalNmtDump("interrupted");
    }
  }

}
