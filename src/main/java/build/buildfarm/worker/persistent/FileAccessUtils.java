package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
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
    EasyMonitor toLock = fileLock(absTo);
    synchronized(toLock) {
      try {
        if (!Files.exists(absTo)) {
          Files.createDirectories(to.getParent());
          logger.finer("copyFile: " + from + " to " + absTo);
          if (!Files.exists(from)) {
            throw new IOException("copyFile: source file doesn't exist: " + from);
          }
          Files.copy(from, to, REPLACE_EXISTING, COPY_ATTRIBUTES);
        }
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
