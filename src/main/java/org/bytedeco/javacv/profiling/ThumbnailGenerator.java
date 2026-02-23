package org.bytedeco.javacv.profiling;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.profiling.model.FileInfoDomain;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.IplImage;

public class ThumbnailGenerator {

  private static final String ROTATE = "rotate";

  public static void processMediaFile(final FileInfoDomain fileInfo) {
    // Make JavaCV less verbose in logs
    avutil.av_log_set_level(avutil.AV_LOG_PANIC);
    // PointerScope resource is added for better native memory handling by JavaCV
    try (PointerScope scope = new PointerScope();
        FFmpegFrameGrabber grabber =
            FFmpegFrameGrabber.createDefault(fileInfo.getFilePath().toString())) {
      grabber.start();
      fileInfo.setDuration(grabber.getLengthInTime() / 1_000_000); // in seconds
      fileInfo.setWidth(grabber.getImageWidth());
      fileInfo.setHeight(grabber.getImageHeight());
      createThumbnail(fileInfo, grabber);
    } catch (FrameGrabber.Exception exc) {
      throw new IllegalStateException("Error getting media information from JavaCV", exc);
    } catch (Error err) {
      // Catching, logging and re-throwing error happened in JavaCV native library call
      System.err.println("\nFatal error in JavaCV native call");
      throw err;
    }
    // Uncomment to call Pointer.trimMemory() explicitly.
    // It was suggested as potential fix in https://github.com/bytedeco/javacv/issues/2371 and added into JavaCV 1.5.13 code too.
    // finally {
    //   Pointer.trimMemory();
    // }
  }

  private static void createThumbnail(final FileInfoDomain fileInfo,
      final FFmpegFrameGrabber grabber) {
    // Declare Closeable resources to be released (can't use try-with-resource here due to
    // implementation specifics).
    Frame frame = null;
    IplImage iplImage = null;
    IplImage rotatedImage = null;
    try {
      String rotate = grabber.getVideoMetadata(ROTATE);
      frame = grabber.grabImage();
      if (null != rotate && rotate.length() > 1) {
        try (OpenCVFrameConverter.ToIplImage frameConverter =
            new OpenCVFrameConverter.ToIplImage()) {
          iplImage = frameConverter.convert(frame);
          rotatedImage = rotate(iplImage, Integer.parseInt(rotate));
          frame = frameConverter.convert(rotatedImage);
        }
      }
      try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
        BufferedImage bufferedImage = converter.getBufferedImage(frame);
        writeThumbnail(fileInfo, bufferedImage);
      }
    } catch (FrameGrabber.Exception exc) {
      throw new IllegalStateException("Error creating thumbnail", exc);
    } catch (IOException exc) {
      throw new IllegalStateException("Error saving thumbnail", exc);
    } finally {
      if (rotatedImage != null) {
        rotatedImage.close();
      }
      if (iplImage != null) {
        iplImage.close();
      }
      if (frame != null) {
        frame.close();
      }
    }
  }

  private static IplImage rotate(final IplImage src, final int angle) {
    final IplImage dst;
    switch (angle) {
      case 90:
        // Rotate 90° clockwise: transpose + flip vertically
        dst = IplImage.create(src.height(), src.width(), src.depth(), src.nChannels());
        opencv_core.cvTranspose(src, dst);
        opencv_core.cvFlip(dst, dst, 0); // 0 = flip around x-axis (vertical flip)
        break;
      case 180:
        // Rotate 180°: flip vertically and horizontally
        dst = IplImage.create(src.width(), src.height(), src.depth(), src.nChannels());
        opencv_core.cvFlip(src, dst, -1); // -1 = flip around both axes
        break;
      case 270:
        // Rotate 270° clockwise (or 90° CCW): transpose + flip horizontally
        dst = IplImage.create(src.height(), src.width(), src.depth(), src.nChannels());
        opencv_core.cvTranspose(src, dst);
        opencv_core.cvFlip(dst, dst, 1); // 1 = flip around y-axis (horizontal flip)
        break;
      default:
        // No rotation or unsupported angle: return copy
        dst = IplImage.create(src.width(), src.height(), src.depth(), src.nChannels());
        opencv_core.cvCopy(src, dst, null);
        break;
    }
    return dst;
  }

  private static void writeThumbnail(final FileInfoDomain fileInfo, final BufferedImage image)
      throws IOException {
    final String filePath = fileInfo.getFilePath().toString() + generateUniqueId() + ".png";
    final File file = new File(filePath);
    ImageIO.write(image, "png", file);
    fileInfo.setThumbnailPath(file.toPath());
  }

  private static String generateUniqueId() {
    final StringBuilder idBuilder = new StringBuilder();
    // 1. Timestamp for ordering
    final String timestamp =
        Instant.now().toString().substring(0, 19).replace("T", "-").replace(":", "");
    idBuilder.append(timestamp).append('-');
    // 2. Short UUID for uniqueness
    final String shortUuid = UUID.randomUUID().toString().substring(0, 8);
    idBuilder.append(shortUuid);

    return idBuilder.toString();
  }
}
