package persistent.bazel.processes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.protobuf.ByteString;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkerKey;
import persistent.testutil.ProcessUtils;

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

    HashCode workerFilesCombinedHash = HashCode.fromInt(0);
    ImmutableList<WorkerProtocol.Input> inputs = ImmutableList.of();
    SortedMap<Path, HashCode> workerFilesWithHashes = ImmutableSortedMap.of();

    WorkerKey key = new WorkerKey(
        ImmutableList.copyOf(initArgs),
        ImmutableList.of(),
        ImmutableMap.of(),
        workDir,
        "TestOp-Adder",
        workerFilesCombinedHash,
        workerFilesWithHashes,
        false,
        false
    );

    ImmutableList<String> arguments = ImmutableList.of("2", "4");

    WorkerProtocol.WorkRequest request = WorkerProtocol.WorkRequest.newBuilder()
        .addAllArguments(arguments)
        .addAllInputs(inputs)
        .setRequestId(0)
        .build();

    Path stdErrLog = workDir.resolve("test-err.log");
    PersistentWorker worker = new PersistentWorker(key, stdErrLog);

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

    assert exitCode == 0;
    assert responseOut.equals("6");
  }
}
