package build.buildfarm.worker;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.rpc.Code;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.buildfarm.worker.resources.ResourceLimits;
import persistent.bazel.client.ProtoWorkerCoordinator;
import persistent.bazel.client.WorkerKey;

public class PersistentExecutor {
  private static Logger logger = Logger.getLogger(PersistentExecutor.class.getName());

  private static ProtoWorkerCoordinator coordinator = ProtoWorkerCoordinator.simpleMapPool();

  static Code runOnPersistentWorker(
      String operationName,
      Path execDir,
      List<String> arguments,
      List<Command.EnvironmentVariable> environmentVariables,
      ResourceLimits limits,
      Duration timeout,
      ActionResult.Builder resultBuilder
  ) {

    System.out.println("executeCommandOnPersistentWorker[" + operationName + "]");

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    environmentVariables.forEach(envVar -> builder.put(envVar.getName(), envVar.getValue()));
    ImmutableMap<String, String> env = builder.build();


    byte[] noBytes = new byte[0];
    HashCode workerFilesCombinedHash = HashCode.fromBytes(noBytes);
    ImmutableList<Input> inputs = ImmutableList.of();
    SortedMap<Path, HashCode> workerFilesWithHashes = ImmutableSortedMap.of();

    WorkerKey key = new WorkerKey(
        ImmutableList.copyOf(arguments),
        ImmutableList.of(),
        env,
        execDir,
        operationName,
        workerFilesCombinedHash,
        workerFilesWithHashes,
        false,
        false
    );

    WorkRequest request = WorkRequest.newBuilder()
            .addAllArguments(arguments)
            .addAllInputs(inputs)
            .setRequestId(0)
            .build();

    WorkResponse response = coordinator.runRequest(key, request);

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
