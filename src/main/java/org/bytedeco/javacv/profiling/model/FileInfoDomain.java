package org.bytedeco.javacv.profiling.model;

import java.nio.file.Path;

public class FileInfoDomain {

  private final Path filePath;
  private long duration;
  private int width;
  private int height;
  private Path thumbnailPath;

  public FileInfoDomain(final Path filePath) {
    this.filePath = filePath;
  }

  public Path getFilePath() {
    return filePath;
  }

  public long getDuration() {
    return duration;
  }

  public void setDuration(final long duration) {
    this.duration = duration;
  }

  public int getWidth() {
    return width;
  }

  public void setWidth(final int width) {
    this.width = width;
  }

  public int getHeight() {
    return height;
  }

  public void setHeight(final int height) {
    this.height = height;
  }

  public Path getThumbnailPath() {
    return thumbnailPath;
  }

  public void setThumbnailPath(final Path thumbnailPath) {
    this.thumbnailPath = thumbnailPath;
  }

}
