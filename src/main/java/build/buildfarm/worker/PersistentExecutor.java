package build.buildfarm.worker;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import com.google.protobuf.Duration;
import com.google.rpc.Code;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.buildfarm.worker.resources.ResourceLimits;
import persistent.common.PersistentCoordinator;

public class PersistentExecutor {
  private static Logger logger = Logger.getLogger(PersistentExecutor.class.getName());

  private static PersistentCoordinator



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




    return Code.OK;
  }
}
