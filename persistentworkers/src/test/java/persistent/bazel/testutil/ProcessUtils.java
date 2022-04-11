package persistent.bazel.testutil;

import java.io.IOException;
import java.nio.file.Paths;

import persistent.JavaProcessWrapper;

import static com.google.common.truth.Truth.assertThat;

public class ProcessUtils {

  public static JavaProcessWrapper spawnPersistentWorkerProcess(String classpath, String className) throws IOException {
    JavaProcessWrapper jpw = new JavaProcessWrapper(
        Paths.get("."),
        Paths.get("testerr.txt"),
        classpath,
        className,
        new String[]{"--persistent_worker"}
    );
    assertThat(jpw.isAlive()).isTrue();
    return jpw;
  }

  public static JavaProcessWrapper spawnPersistentWorkerProcess(String classpath, Class<?> clazz) throws IOException {
    String className = clazz.getPackage().getName() + "." + clazz.getSimpleName();
    return spawnPersistentWorkerProcess(classpath, className);
  }
}
