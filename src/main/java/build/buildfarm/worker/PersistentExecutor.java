package build.buildfarm.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.rpc.Code;

import build.bazel.remote.execution.v2.ActionResult;
import build.buildfarm.v1test.Tree;
import build.buildfarm.worker.resources.ResourceLimits;
import persistent.bazel.client.ProtoWorkerCoordinator;
import persistent.bazel.client.WorkerKey;

public class PersistentExecutor {
  private static Logger logger = Logger.getLogger(PersistentExecutor.class.getName());

  private static final ProtoWorkerCoordinator coordinator = ProtoWorkerCoordinator.ofCommonsPool();

  private static final Path workRootsDir = Paths.get("/tmp/worker/persistent/");

  static final String PERSISTENT_WORKER_FLAG = "--persistent_worker";

  static final String JAVABUILDER_JAR = "external/remote_java_tools/java_tools/JavaBuilder_deploy.jar";

  static Code runOnPersistentWorker(
      String operationName,
      Tree execTree,
      Path operationDir,
      List<String> arguments,
      Map<String, String> environmentVariables,
      ResourceLimits limits,
      Duration timeout,
      ActionResult.Builder resultBuilder
  ) {

    System.out.println("executeCommandOnPersistentWorker[" + operationName + "]");

    HashCode workerFilesCombinedHash = HashCode.fromInt(0);
    SortedMap<Path, HashCode> workerFilesWithHashes = ImmutableSortedMap.of();

    ImmutableList<String> argsList = ImmutableList.copyOf(arguments);

    ImmutableMap<String, String> env = ImmutableMap.copyOf(environmentVariables);

    int jarOrBinIdx = 0;
    boolean isScalac = arguments.size() > 1 && arguments.get(0).endsWith("scalac/scalac");
    String executionName = "Scalac";
    if (argsList.contains(JAVABUILDER_JAR)) {
      jarOrBinIdx = argsList.indexOf(JAVABUILDER_JAR);
      executionName = "JavaBuilder";
      env = ImmutableMap.of();
    } else if (!isScalac) {
      System.out.println("Invalid Argument?!");
      return Code.INVALID_ARGUMENT;
    }

    ImmutableList<String> workerExecCmd = argsList.subList(0, jarOrBinIdx + 1);
    ImmutableList<String> workerInitArgs = ImmutableList.of(PERSISTENT_WORKER_FLAG);
    ImmutableList<String> requestArgs = argsList.subList(jarOrBinIdx + 1, argsList.size());

    // Unused as of current
    boolean sandboxed = true;
    boolean cancellable = false;

    // Hash of a subset of the WorkerKey
    int workRootId = Objects.hash(
        workerExecCmd,
        workerInitArgs,
        env,
        sandboxed,
        cancellable
    );
    String workRootDirName = "work-root_" + executionName + "_" + workRootId;

    Path workRoot = workRootsDir.resolve(workRootDirName);

    WorkerKey key = new WorkerKey(
        workerExecCmd,
        workerInitArgs,
        env,
        workRoot,
        executionName,
        workerFilesCombinedHash,
        workerFilesWithHashes,
        sandboxed,
        cancellable
    );

    ImmutableList<Input> inputs = new TreeWalker(execTree).getInputs(operationDir.toAbsolutePath());

    WorkRequest request = WorkRequest.newBuilder()
        .addAllArguments(requestArgs)
        .addAllInputs(inputs)
        .setRequestId(0)
        .build();

    System.out.println("Request with key: " + key);
    WorkResponse response;
    try {
      response = coordinator.runRequest(key, request);
    } catch (Exception e) {
      System.out.println("Exception while running request: " + e.getMessage());
      e.printStackTrace();
      response = WorkResponse.newBuilder()
          .setOutput("Exception while running request: " + e)
          .setExitCode(1)
          .build();
    }

    String responseOut = response.getOutput();
    System.out.println("WorkResponse.output: " + responseOut);
    resultBuilder.setStdoutRaw(ByteString.copyFromUtf8(responseOut));

    int exitCode = response.getExitCode();

    if (exitCode == 0) {
      return Code.OK;
    }
    System.out.println("Wtf? " + exitCode + "\n" + responseOut);
    return Code.FAILED_PRECONDITION;
  }
}
