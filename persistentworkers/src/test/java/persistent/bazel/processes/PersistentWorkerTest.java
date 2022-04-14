package persistent.bazel.processes;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.worker.WorkerProtocol;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkerKey;
import persistent.testutil.ProcessUtils;
import persistent.testutil.WorkerUtils;

@RunWith(JUnit4.class)
public class PersistentWorkerTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {
    Path workDir = Files.createTempDirectory("test-workdir-");

    String filename = "adder-bin_deploy.jar";

    Path jarPath = ProcessUtils.retrieveFileResource(
        getClass().getClassLoader(),
        filename,
        workDir.resolve(filename)
    );

    ImmutableList<String> initArgs = ImmutableList.of(
        "java",
        "-cp",
        jarPath.toString(),
        "adder.Adder",
        "--persistent_worker"
    );

    WorkerKey key = WorkerUtils.emptyWorkerKey(workDir, initArgs);

    Path stdErrLog = workDir.resolve("test-err.log");
    PersistentWorker worker = new PersistentWorker(key);

    ImmutableList<String> arguments = ImmutableList.of("2", "4");
    String expectedOutput = "6";

    WorkerProtocol.WorkRequest request = WorkerProtocol.WorkRequest.newBuilder()
        .addAllArguments(arguments)
        .setRequestId(0)
        .build();

    WorkerProtocol.WorkResponse response;
    try {
      response = worker.doWork(request);
    } catch (Exception e) {
      System.err.println(e.getMessage());
      System.err.println(Files.readAllLines(stdErrLog));
      throw e;
    }

    String responseOut = response.getOutput();
    int exitCode = response.getExitCode();

    Assert.assertEquals(exitCode, 0);
    Assert.assertEquals(responseOut, expectedOutput);
  }
}
