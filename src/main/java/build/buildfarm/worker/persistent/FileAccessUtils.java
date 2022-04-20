package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Files;
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
   * Copies a file, creating necessary directories.
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
    IOException ioException = safeFileOp(
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
   * Moves a file, creating necessary directories.
   * Thread-safe against writes to the same path.
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
    IOException ioException = safeFileOp(
        absTo,
        () -> {
          try {
            Files.move(from, absTo, REPLACE_EXISTING, COPY_ATTRIBUTES);
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
    IOException ioException = safeFileOp(
        toDelete,
        () -> {
          try {
            Files.deleteIfExists(toDelete);
            return null;
          } catch (IOException e) {
            return e;
          }
        },
        false
    );
    if (ioException != null) {
      throw ioException;
    }
  }

  private static IOException safeFileOp(Path absTo, Supplier<IOException> writeOp) {
    return safeFileOp(absTo, writeOp, true);
  }

  private static IOException safeFileOp(Path absTo, Supplier<IOException> writeOp, boolean createDirs) {
    EasyMonitor toLock = fileLock(absTo);
    synchronized(toLock) {
      try {
        if (!Files.exists(absTo)) {
          if (createDirs) {
            Files.createDirectories(absTo.getParent());
          }
          return writeOp.get();
        }
        return null;
      } catch (IOException e) {
        return e;
      }finally {
        // Clean up to prevent too many locks.
        chm.remove(absTo);
      }
    }
  }

  private static EasyMonitor fileLock(Path writeTo) {
    return chm.computeIfAbsent(writeTo, k -> new EasyMonitor());
  }
}
