# JavaCV Native Memory Profiling Test Application

## Overview

This application is developed to profile the [JavaCV library](https://github.com/bytedeco/javacv) for native memory leaks by repeatedly processing video files and generating thumbnails. It supports parallel execution with configurable thread counts and invocation numbers to simulate realistic workloads and stress test native memory management.

## Features

- **Parallel Video Processing**: Uses a thread pool to process multiple video files concurrently
- **Thumbnail Generation**: Extracts frames from video files and creates thumbnail images
- **Auto-Cleanup**: Automatically removes generated thumbnails after processing
- **Comprehensive Native Memory Tracking (NMT)**: 
  - **Baseline dump**: Captured before any processing starts
  - **Periodic dumps**: Configurable interval during execution
  - **Final dump**: Captured when all tasks complete

## Prerequisites

- **Java 11** or higher
- **Maven 3.6+** for building
- **FFmpeg libraries** (included via JavaCV dependencies)

## Building the Application

### Standard Build

```bash
mvn clean package
```

The numerous warnings in build log are common when using the [Maven Shade Plugin](https://maven.apache.org/plugins/maven-shade-plugin) with complex dependency sets like `JavaCV`.
The resulting uber-jar, named like `javacv-profiling-0.0.1-SNAPSHOT-uber.jar` will be fully functional despite of them.

### Platform-Specific Build (Recommended)

To reduce the size of the executable JAR and build time, specify the target platform:

```bash
mvn clean package -Djavacpp.platform=linux-x86_64
```

**Available platforms:**

See official [JavaCPP documentation](https://github.com/bytedeco/javacpp-presets/wiki/Reducing-the-Number-of-Dependencies) for more platforms.

Without specifying a platform, the build will include binaries for all supported platforms, resulting in a much larger JAR file.

## Usage

### Basic Command

```bash
java -jar target/javacv-profiling-0.0.1-SNAPSHOT-uber.jar <numThreads> <numInvocations> <videoFilePath> <dumpIntervalSeconds>
```

### Parameters

- **numThreads**: Number of concurrent threads to use for processing (positive integer)
- **numInvocations**: Total number of times to process the video file (positive integer)
- **videoFilePath**: Path to the video file to process
- **dumpIntervalSeconds**: NMT dump interval in seconds (0 to disable periodic dumps, positive integer for interval)

## NMT Dump Output Files

The application generates timestamped NMT dump files with the following naming pattern:

```
nmt-baseline-YYYYMMDD-HHMMSS-SSS.log      # Initial baseline (before processing)
nmt-baseline-YYYYMMDD-HHMMSS-SSS.err      # Baseline error output
nmt-periodic-YYYYMMDD-HHMMSS-SSS.log      # Periodic dumps during execution
nmt-periodic-YYYYMMDD-HHMMSS-SSS.err      # Periodic error output
nmt-final-YYYYMMDD-HHMMSS-SSS.log         # Final dump (after all processing)
nmt-final-YYYYMMDD-HHMMSS-SSS.err         # Final error output
```

## Profiling for Memory Leaks with Async-Profiler

To profile for native memory leaks, run the JVM with Native Memory Tracking enabled with profiler agent attached.
For instance using [async-profiler](https://github.com/async-profiler/async-profiler) in `nativemem` mode:

```bash
java \
  -XX:+PreserveFramePointer -XX:NativeMemoryTracking=detail \
  -agentpath:/path/to/libasyncProfiler.so=start,event=nativemem,file=leaks.jfr \
  -jar target/javacv-profiling-0.0.1-SNAPSHOT-uber.jar 20 10000 /path/to/video/video.mp4 10
```

With Serial Garbage Collector (single-threaded), disabled JIT (Just-In-Time) compilation, diagnostic JVM options enabled, keeping frame pointers for better profiling accuracy and detailed Native Memory Tracking (NMT) to monitor native allocations:

```bash
java \
  -Xmx512m -XX:+UseSerialGC -Xss1m -Djava.compiler=NONE -XX:+UnlockDiagnosticVMOptions -XX:+PreserveFramePointer -XX:NativeMemoryTracking=detail \
  -agentpath:/path/to/libasyncProfiler.so=start,event=nativemem,file=leaks.jfr \
  -jar target/javacv-profiling-0.0.1-SNAPSHOT-uber.jar 20 10000 /path/to/video/video.mp4 10
```

After the application finishes, process the `.jfr` file to detect unfreed allocations:

```bash
jfrconv \
  --total \
  --nativemem \
  --leak \
  leaks.jfr \
  native-leaks.html
```

Produced `native-leaks.html` flame graph must contain unfreed allocations only according to [async-profiler documentation](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md#native-memory-leaks), which are the likely to be a source of a memory leak.