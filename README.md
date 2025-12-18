# JavaCV Native Memory Profiling Test Application

## Overview

This application is developed to profile the [JavaCV library](https://github.com/bytedeco/javacv) for native memory leaks by repeatedly processing video files and generating thumbnails. It supports parallel execution with configurable thread counts and invocation numbers to simulate realistic workloads and stress test native memory management.

## Features

- **Parallel Video Processing**: Uses a thread pool to process multiple video files concurrently
- **Thumbnail Generation**: Extracts frames from video files and creates thumbnail images
- **Auto-Cleanup**: Automatically removes generated thumbnails after processing
- **Native Memory Tracking (NMT)**: Captures JVM Native Memory Tracking data on shutdown
- **Progress Monitoring**: Real-time progress display of completed tasks


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

See official [JavaCPP documentation](https://github.com/bytedeco/javacpp-presets/wiki/Reducing-the-Number-of-Dependencies).

Without specifying a platform, the build will include binaries for all supported platforms, resulting in a much larger JAR file.

## Usage

### Basic Command

```bash
java -jar target/javacv-profiling-0.0.1-SNAPSHOT-uber.jar <numThreads> <numInvocations> <videoFilePath>
```

### Parameters

- **numThreads**: Number of concurrent threads to use for processing (positive integer)
- **numInvocations**: Total number of times to process the video file (positive integer)
- **videoFilePath**: Path to the video file to process

## Output

- **Console Output**: Shows PID and progress of completed tasks
- **NMT Logs**: Creates `nmt-final.log` and `nmt-final.err` on shutdown with native memory statistics
- **Thumbnails**: Temporarily created in the same directory as the source video (with timestamp-UUID naming for uniqueness)

## Profiling for Memory Leaks

To profile for native memory leaks, run the JVM with Native Memory Tracking enabled with profiler agent attached. For instance using [async-profiler](https://github.com/async-profiler/async-profiler) in `nativemem` mode:

```bash
java \
  -XX:+PreserveFramePointer -XX:NativeMemoryTracking=summary \
  -agentpath:/path/to/libasyncProfiler.so=start,event=nativemem,file=leaks.jfr \
  -jar target/javacv-profiling-0.0.1-SNAPSHOT-uber.jar 20 10000 /path/to/video/video.mp4
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