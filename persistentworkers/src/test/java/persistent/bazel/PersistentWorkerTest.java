package persistent.bazel;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.google.common.truth.Truth.assertThat;

@RunWith(JUnit4.class)
public class PersistentWorkerTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {
    Path workDir = Files.createTempDirectory("test-workdir-");

    String filename = "adder-bin_deploy.jar";

    InputStream is = getClass().getClassLoader().getResourceAsStream(filename);

    Path jarPath = workDir.resolve(filename);

    Files.write(jarPath, IOUtils.toByteArray(is));

    assertThat(Files.exists(jarPath)).isTrue();

    assertThat(Files.size(jarPath)).isEqualTo(11768149);
  }
}
