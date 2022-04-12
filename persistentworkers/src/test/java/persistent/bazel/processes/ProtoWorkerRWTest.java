package persistent.bazel.processes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.devtools.build.lib.worker.WorkerProtocol;

import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import persistent.bazel.processes.ProtoWorkerRW;
import persistent.common.processes.JavaProcessWrapper;
import persistent.common.processes.ProcessWrapper;

import static com.google.common.truth.Truth.assertThat;

import static persistent.testutil.ProcessUtils.spawnPersistentWorkerProcess;

@RunWith(JUnit4.class)
public class ProtoWorkerRWTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {
    Path workDir = Files.createTempDirectory("test-workdir-");

    String filename = "adder-bin_deploy.jar";

    InputStream is = getClass().getClassLoader().getResourceAsStream(filename);

    Path jarPath = workDir.resolve(filename);

    Files.write(jarPath, IOUtils.toByteArray(is));

    assertThat(Files.exists(jarPath)).isTrue();

    assertThat(Files.size(jarPath)).isAtLeast(11000000L); // at least 11mb
  }

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void canAddWithAdder() throws Exception {
    Path workDir = Files.createTempDirectory("test-workdir-");

    String filename = "adder-bin_deploy.jar";

    InputStream is = getClass().getClassLoader().getResourceAsStream(filename);

    Path jarPath = workDir.resolve(filename);

    Files.write(jarPath, IOUtils.toByteArray(is));

    ProcessWrapper pw;
    try (JavaProcessWrapper jpw = spawnPersistentWorkerProcess(jarPath.toString(), "adder.Adder")) {
      pw = jpw;
      ProtoWorkerRW rw = new ProtoWorkerRW(jpw);
      assertThat(jpw.isAlive()).isTrue();
      rw.write(WorkerProtocol.WorkRequest.newBuilder().addArguments("1").addArguments("3").build());
      assertThat(rw.waitAndRead().getOutput()).isEqualTo("4");
      assertThat(jpw.isAlive()).isTrue();
    }
    assertThat(pw).isNotNull();
    pw.waitFor();
    assertThat(pw.isAlive()).isFalse();
    assertThat(pw.exitCode()).isNotEqualTo(0);
  }
}
