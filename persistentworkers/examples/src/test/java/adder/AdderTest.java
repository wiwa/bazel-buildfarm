package adder;

import java.io.IOException;
import java.nio.file.Paths;

import com.google.devtools.build.lib.worker.WorkerProtocol;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import persistent.JavaProcessWrapper;
import persistent.ProcessWrapper;
import persistent.bazel.ProtoWorkerRW;

import static com.google.common.truth.Truth.assertThat;

@RunWith(JUnit4.class)
public class AdderTest {

  private JavaProcessWrapper spawnAdderProcess() throws IOException {
    JavaProcessWrapper jpw = new JavaProcessWrapper(
        Paths.get("."),
        Paths.get("testerr.txt"),
        System.getProperty("java.class.path"),
        Adder.class,
        new String[]{"--persistent_worker"}
    );
    assertThat(jpw.isAlive()).isTrue();
    return jpw;
  }

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {
    ProcessWrapper pw;
    try (JavaProcessWrapper jpw = spawnAdderProcess()) {
      pw = jpw;
      ProtoWorkerRW rw = new ProtoWorkerRW(jpw);
      rw.write(WorkerProtocol.WorkRequest.newBuilder().addArguments("1").addArguments("3").build());
      assertThat(rw.waitAndRead().getOutput()).isEqualTo("4");
      assertThat(jpw.isAlive()).isTrue();
    }
    assertThat(pw).isNotNull();
    pw.waitFor();
    assertThat(pw.isAlive()).isFalse();
    assertThat(pw.exitCode()).isNotEqualTo(0);
  }

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void canShutdownAdder() throws Exception {
    try (JavaProcessWrapper jpw = spawnAdderProcess()) {
      ProtoWorkerRW rw = new ProtoWorkerRW(jpw);
      rw.write(WorkerProtocol.WorkRequest.newBuilder().addArguments("stop!").build());
      jpw.waitFor();
      assertThat(jpw.isAlive()).isFalse();
      assertThat(jpw.exitCode()).isEqualTo(0);
    }
  }

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void adderBadRequest() throws Exception {
    try (JavaProcessWrapper jpw = spawnAdderProcess()) {
      ProtoWorkerRW rw = new ProtoWorkerRW(jpw);
      rw.write(WorkerProtocol.WorkRequest.newBuilder().addArguments("bad request").build());
      jpw.waitFor();
      assertThat(jpw.isAlive()).isFalse();
      assertThat(jpw.exitCode()).isEqualTo(2);
    }
  }
}
