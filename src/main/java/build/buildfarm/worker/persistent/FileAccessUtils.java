package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class FileAccessUtils {

  private static final Logger logger = Logger.getLogger(FileAccessUtils.class.getName());

  /**
   * Copies a file, creating necessary directories.
   * Uses REPLACE_EXISTING, COPY_ATTRIBUTES, but is not thread-safe.
   *
   * @param from
   * @param to
   * @throws IOException
   */
  public static void copyFile(Path from, Path to) throws IOException {
    Files.createDirectories(to.getParent());
    if (!Files.exists(to)) {
      logger.log(Level.FINE, "copyFile: " + from + " to " + to);
      Files.copy(from, to, REPLACE_EXISTING, COPY_ATTRIBUTES);
    }
  }
}
