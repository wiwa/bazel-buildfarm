package persistent.bazel;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PersistentWorkerTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {
    Path workDir = Files.createTempDirectory("test-workdir-");

    System.out.println(getClass().getResource("adder-bin_deploy.jar"));
  }
}
