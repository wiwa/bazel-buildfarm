package build.buildfarm.worker;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import build.buildfarm.worker.resources.ResourceLimits;
import persistent.bazel.client.ProtoWorkerCoordinator;
import persistent.bazel.client.WorkerKey;

public class PersistentExecutor {
  private static Logger logger = Logger.getLogger(PersistentExecutor.class.getName());

  private static final ProtoWorkerCoordinator coordinator = ProtoWorkerCoordinator.ofCommonsPool();

  static final String PERSISTENT_WORKER_FLAG = "--persistent_worker";

  static final String JAVABUILDER_JAR = "external/remote_java_tools/java_tools/JavaBuilder_deploy.jar";

  static Code runOnPersistentWorker(
      OperationContext operationContext,
      String operationName,
      Path execDir,
      List<String> arguments,
      Map<String, String> environmentVariables,
      ResourceLimits limits,
      Duration timeout,
      ActionResult.Builder resultBuilder
  ) {

    System.out.println("executeCommandOnPersistentWorker[" + operationName + "]");

    HashCode workerFilesCombinedHash = HashCode.fromInt(0);
    ImmutableList<Input> inputs = ImmutableList.of();
    SortedMap<Path, HashCode> workerFilesWithHashes = ImmutableSortedMap.of();

    ImmutableList<String> argsList = ImmutableList.copyOf(arguments);

    int jarOrBinIdx = 0;
    boolean isScalac = arguments.size() > 1 && arguments.get(0).endsWith("scalac/scalac");
    String execBinary = "Scalac";
    if (argsList.contains(JAVABUILDER_JAR)) {
      jarOrBinIdx = argsList.indexOf(JAVABUILDER_JAR);
      execBinary = "JavaBuilder";
    } else if (!isScalac) {
      return Code.INVALID_ARGUMENT;
    }


    ImmutableList<String> cmd = argsList.subList(0, jarOrBinIdx + 1);
    ImmutableList<String> args = ImmutableList.of(PERSISTENT_WORKER_FLAG);
    ImmutableList<String> requestArgs = argsList.subList(jarOrBinIdx + 1, argsList.size());

    ImmutableMap<String, String> env = ImmutableMap.copyOf(environmentVariables);

    WorkerKey key = new WorkerKey(
        cmd,
        args,
        env,
        execDir.toAbsolutePath(),
        execBinary,
        workerFilesCombinedHash,
        workerFilesWithHashes,
        true,
        false
    );

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
    return Code.UNKNOWN;
  }
}
