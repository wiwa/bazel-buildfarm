package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.rpc.Code;

import build.bazel.remote.execution.v2.ActionResult;
import build.buildfarm.worker.resources.ResourceLimits;
import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkerKey;

/**
 * Responsible for returning information just like Executor/DockerExecutor.
 */
public class PersistentExecutor {

  private static final Logger logger = Logger.getLogger(PersistentExecutor.class.getName());

  private static final ProtoCoordinator coordinator = ProtoCoordinator.ofCommonsPool(4);

  static final Path workRootsDir = Paths.get("/tmp/worker/persistent/");

  static final String PERSISTENT_WORKER_FLAG = "--persistent_worker";

  static final String JAVABUILDER_JAR = "external/remote_java_tools/java_tools/JavaBuilder_deploy.jar";

  private static final String SCALAC_EXEC_NAME = "Scalac";
  private static final String JAVAC_EXEC_NAME = "JavaBuilder";

  /**
   * 1) Parse tool inputs and request inputs
   * 2) Makes the WorkerKey
   * 3) Loads the tool inputs if needed into the WorkerKey tool inputs dir
   * 4) Runs the work request on its Coordinator, passing it the required context
   * 5) Passes output to the resultBuilder
   */
  public static Code runOnPersistentWorker(
      String persistentWorkerInitCmd,
      WorkFilesContext context,
      String operationName,
      ImmutableList<String> argsList,
      ImmutableMap<String, String> envVars,
      ResourceLimits limits,
      Duration timeout,
      ActionResult.Builder resultBuilder
  ) throws IOException {

    //// Start, hardcoding persistent worker actions

    logger.log(Level.FINE, "executeCommandOnPersistentWorker[" + operationName + "]");

    ImmutableList<String> initCmd = parseInitCmd(persistentWorkerInitCmd, argsList);

    String executionName = getExecutionName(argsList);
    if (executionName.isEmpty()) {
      logger.log(Level.SEVERE, "Invalid Argument?!: " + argsList);
      return Code.INVALID_ARGUMENT;
    }

    // int jarOrBinIdx;
    ImmutableMap<String, String> env;
    if (executionName.equals(JAVAC_EXEC_NAME)) {
      env = ImmutableMap.of();
    } else {
      // Scalac
      env = envVars;
    }

    // //// Parse args into initial tool startup and action request

    // // flags aren't part of the request
    // // this should definitely fail on a flag with a param...
    // // Maybe hardcode to first argsfile? if only I could build bazel.
    // int requestArgsIdx = jarOrBinIdx + 1;
    // for (String s : argsList) {
    //   if (s.startsWith("-")) {
    //     requestArgsIdx = Math.max(requestArgsIdx, argsList.lastIndexOf(s) + 1);
    //   }
    // }
    // List<String> flags = argsList.subList(jarOrBinIdx + 1, requestArgsIdx);

    int requestArgsIdx = initCmd.size();
    ImmutableList<String> workerExecCmd = initCmd; // argsList.subList(0, jarOrBinIdx + 1);
    ImmutableList<String> workerInitArgs = ImmutableList.<String>builder()
        //.addAll(flags)
        .add(PERSISTENT_WORKER_FLAG)
        .build();
    ImmutableList<String> requestArgs = argsList.subList(requestArgsIdx, argsList.size());

    //// Make Key

    WorkerInputs workerFiles = WorkerInputs.from(context, requestArgs);

    Path binary = Paths.get(workerExecCmd.get(0));
    if (!workerFiles.containsTool(binary) && !binary.isAbsolute()) {
      throw new IllegalArgumentException("Binary isn't a tool?! " + binary);
    }

    WorkerKey key = Keymaker.make(
        context.opRoot,
        workerExecCmd,
        workerInitArgs,
        env,
        executionName,
        workerFiles
    );

    //// Copy tool inputs as needed
    Path workToolRoot = key.getExecRoot().resolve(PersistentWorker.TOOL_INPUT_SUBDIR);
    for (Path opToolPath : workerFiles.opToolInputs) {
      Path workToolPath = workerFiles.relativizeInput(workToolRoot, opToolPath);
      workerFiles.accessFileFrom(opToolPath, workToolPath);
    }


    //// Make request

    // Inputs should be relative paths (if they are from operation root)
    ImmutableList.Builder<Input> reqInputsBuilder = ImmutableList.builder();

    for (Map.Entry<Path, Input> opInput : workerFiles.allInputs.entrySet()) {
      Input relInput = opInput.getValue();
      Path opPath = opInput.getKey();
      if (opPath.startsWith(workerFiles.opRoot)) {
        relInput = relInput
            .toBuilder()
            .setPath(workerFiles.opRoot.relativize(opPath).toString())
            .build();
      }
      reqInputsBuilder.add(relInput);
    }
    ImmutableList<Input> reqInputs = reqInputsBuilder.build();

    WorkRequest request = WorkRequest.newBuilder()
        .addAllArguments(requestArgs)
        .addAllInputs(reqInputs)
        .setRequestId(0)
        .build();

    RequestCtx requestCtx = new RequestCtx(request, context, workerFiles, timeout);

    //// Run request
    //// Required file operations (in/out) are the responsibility of the coordinator

    logger.log(Level.FINE, "Request with key: " + key);
    WorkResponse response;
    String stdErr = "";
    try {
      ResponseCtx fullResponse = coordinator.runRequest(key, requestCtx);

      response = fullResponse.response;
      stdErr = fullResponse.errorString;
    } catch (Exception e) {

      String debug = "\n\tRequest.initCmd: " + workerExecCmd +
                     "\n\tRequest.initArgs: " + workerInitArgs +
                     "\n\tRequest.requestArgs: " + request.getArgumentsList();
      String msg = "Exception while running request: " + e + debug + "\n\n";

      logger.log(Level.SEVERE, msg);
      e.printStackTrace();
      response = WorkResponse.newBuilder()
          .setOutput(msg)
          .setExitCode(-1) // incomplete
          .build();
    }

    //// Set results

    String responseOut = response.getOutput();
    logger.log(Level.FINE, "WorkResponse.output: " + responseOut);

    int exitCode = response.getExitCode();
    resultBuilder
        .setExitCode(exitCode)
        .setStdoutRaw(response.getOutputBytes())
        .setStderrRaw(ByteString.copyFrom(stdErr, StandardCharsets.UTF_8));

    if (exitCode == 0) {
      return Code.OK;
    }

    if (executionName.equals("SomeOtherExec")) {
      System.out.println("SomeOtherExec inputs: " +
          ImmutableList.copyOf(reqInputs.stream().map(Input::getPath).collect(Collectors.toList()))
      );
    }
    logger.severe(
        "PersistentExecutor.runOnPersistentWorker Failed with code: " +
        exitCode + "\n" + responseOut
    );
    return Code.FAILED_PRECONDITION;
  }

  private static ImmutableList<String> parseInitCmd(String cmdStr, ImmutableList<String> argsList) {
    if (cmdStr.isEmpty() || !cmdStr.endsWith(PERSISTENT_WORKER_FLAG)) {
      throw new IllegalArgumentException("parseInitCmd?[" + cmdStr + "]" + "\n" + argsList);
    }

    String cmd = cmdStr.strip().substring(0, (cmdStr.length() - PERSISTENT_WORKER_FLAG.length()) - 1);

    ImmutableList.Builder<String> initCmdBuilder = ImmutableList.builder();
    for (String s : argsList) {
      if (cmd.length() == 0) {
        break;
      }
      cmd = cmd.substring(s.length()).strip();
      initCmdBuilder.add(s);
    }
    ImmutableList<String> initCmd = initCmdBuilder.build();
    if (!initCmd.equals(argsList.subList(0, initCmd.size()))) {
      throw new IllegalArgumentException("parseInitCmd?![" + initCmd + "]" + "\n" + argsList);
    }
    return initCmd;
  }

  private static String getExecutionName(ImmutableList<String> argsList) {
    boolean isScalac = argsList.size() > 1 && argsList.get(0).endsWith("scalac/scalac");
    if (isScalac) {
      return SCALAC_EXEC_NAME;
    } else if (argsList.contains(JAVABUILDER_JAR)) {
      return JAVAC_EXEC_NAME;
    }
    return "SomeOtherExec";
  }
}
