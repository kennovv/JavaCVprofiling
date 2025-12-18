package org.bytedeco.javacv.profiling;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.javacv.profiling.model.FileInfoDomain;

public class ProfilingApp {

  private static final String JCMD = "jcmd";

  private static final String NMT_LOG_NAME = "nmt-final.log";
  private static final String NMT_ERROR_LOG_NAME = "nmt-final.err";

  // Shared counter to track completed tasks
  private static final AtomicLong completedTasks = new AtomicLong(0);

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

  public static void main(final String[] args) {
    if (args.length != 3) {
      System.err.println(
          "Usage: java -jar my-parallel-app.jar <numThreads> <numInvocations> <videoFilePath>");
      System.exit(1);
    }
    final int numThreads;
    final long numInvocations;
    final Path videoFilePath;
    try {
      numThreads = Integer.parseInt(args[0]);
      numInvocations = Long.parseLong(args[1]);
      videoFilePath = Paths.get(args[2]);

      if (numThreads <= 0 || numInvocations <= 0) {
        throw new IllegalArgumentException("Arguments must be positive integers.");
      }

      if (!Files.exists(videoFilePath)) {
        throw new IOException("Video file does not exist: " + videoFilePath.toAbsolutePath());
      }
      if (!Files.isRegularFile(videoFilePath)) {
        throw new IOException("Path is not a regular file: " + videoFilePath.toAbsolutePath());
      }

    } catch (NumberFormatException exc) {
      System.err.println("Error: Both arguments must be valid integers.");
      System.exit(1);
      return;
    } catch (IllegalArgumentException | IOException exc) {
      System.err.println("Error: " + exc.getMessage());
      System.exit(1);
      return;
    }

    // Get PID
    final long pid = ProcessHandle.current().pid();
    // Print PID to help manual profiling tools connecting
    System.out.println("PID: " + String.valueOf(pid));

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
      } else {
        // After all tasks are done, print a final summary
        System.out.println("\nAll tasks completed. Final count: " + completedTasks.get());
        System.out.println("Processed video file: " + videoFilePath.toAbsolutePath());
        // Shutdown hook to capture NMT Dump
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          try {
            final Process proc =
                new ProcessBuilder(JCMD, String.valueOf(pid), "VM.native_memory", "summary")
                    .redirectOutput(new File(NMT_LOG_NAME))
                    .redirectError(new File(NMT_ERROR_LOG_NAME)).start();
            proc.waitFor();
          } catch (Exception exc) {
            System.err.println("Unable to create shutdown hook to capture NMT Dump.");
            exc.printStackTrace();
          }
        }));
      }
    } catch (InterruptedException e) {
      System.err.println("\nInterrupted while waiting for executor to terminate.");
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

}
