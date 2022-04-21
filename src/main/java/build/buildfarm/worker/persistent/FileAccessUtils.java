package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class FileAccessUtils {

  private static final Logger logger = Logger.getLogger(FileAccessUtils.class.getName());

  private static final ConcurrentHashMap<Path, EasyMonitor> chm = new ConcurrentHashMap<>();

  private static class EasyMonitor {
    public EasyMonitor(){}
  }

  /**
   * Copies a file, creating necessary directories, replacing existing files.
   * Thread-safe against writes to the same path.
   *
   * @param from
   * @param to
   * @throws IOException
   */
  public static void copyFile(Path from, Path to) throws IOException {
    Path absTo = to.toAbsolutePath();
    logger.finer("copyFile: " + from + " to " + absTo);
    if (!Files.exists(from)) {
      throw new IOException("copyFile: source file doesn't exist: " + from);
    }
    IOException ioException = writeFileSafe(
        to,
        () -> {
          try {
            Files.copy(from, absTo, REPLACE_EXISTING, COPY_ATTRIBUTES);
            return null;
          } catch (IOException e) {
            return e;
          }
        }
    );
    if (ioException != null) {
      throw ioException;
    }
  }

  /**
   * Moves a file, creating necessary directories, replacing existing files.
   * Thread-safe against writes to the same path,
   *    but may get AccessDeniedException if the file is not writeable.
   *
   * @param from
   * @param to
   * @throws IOException
   */
  public static void moveFile(Path from, Path to) throws IOException {
    Path absTo = to.toAbsolutePath();
    logger.finer("moveFile: " + from + " to " + absTo);
    if (!Files.exists(from)) {
      throw new IOException("moveFile: source file doesn't exist: " + from);
    }
    IOException ioException = writeFileSafe(
        absTo,
        () -> {
          try {
            Files.move(from, absTo, REPLACE_EXISTING);
            return null;
          } catch (IOException e) {
            return e;
          }
        }
    );
    if (ioException != null) {
      throw ioException;
    }
  }

  /**
   * Creates a symlink, creating necessary directories.
   * Deletes pre-existing files/links which have the same path as the specified link,
   *   effectively overwriting any existing files/links.
   *
   * @param from
   * @param to
   * @throws IOException
   */
  public static void linkFile(Path from, Path to) throws IOException {
    Path absTo = to.toAbsolutePath();
    logger.finer("linkFile: " + from + " to " + absTo);
    if (!Files.exists(from)) {
      throw new IOException("linkFile: source file doesn't exist: " + from);
    }
    IOException ioException = writeFileSafe(
        absTo,
        () -> {
          try {
            Files.deleteIfExists(absTo);
            Files.createSymbolicLink(absTo, from);
            return null;
          } catch (IOException e) {
            return e;
          }
        }
    );
    if (ioException != null) {
      throw ioException;
    }
  }

  /**
   * Deletes a file; Thread-safe against writes to the same path.
   *
   * @param toDelete
   * @throws IOException
   */
  public static void deleteFileIfExists(Path toDelete) throws IOException {
    Path absTo = toDelete.toAbsolutePath();
    EasyMonitor toLock = fileLock(absTo);
    synchronized(toLock) {
      try {
        Files.deleteIfExists(absTo);
      } finally {
        chm.remove(absTo);
      }
    }
  }

  /**
   * Thread-safe (not multi-process-safe) wrapper for locking paths before a write operation.
   *
   * This method will create necessary parent directories.
   *
   * It is up to the write operation to specify whether or not to overwrite existing files.
   */
  private static IOException writeFileSafe(Path absTo, Supplier<IOException> writeOp) {
    EasyMonitor toLock = fileLock(absTo);
    synchronized(toLock) {
      try {
        // If 'absTo' is a symlink, checks if its target file exists
        Files.createDirectories(absTo.getParent());
        return writeOp.get();
      } catch (IOException e) {
        return e;
      } finally {
        // Clean up to prevent too many locks.
        chm.remove(absTo);
      }
    }
  }

  private static EasyMonitor fileLock(Path writeTo) {
    return chm.computeIfAbsent(writeTo, k -> new EasyMonitor());
  }
}
