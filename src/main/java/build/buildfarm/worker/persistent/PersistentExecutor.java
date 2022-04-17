package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import persistent.bazel.client.ProtoWorkerCoordinator;
import persistent.bazel.client.ProtoWorkerCoordinator.FullResponse;
import persistent.bazel.client.WorkerKey;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Responsible for returning information just like Executor/DockerExecutor.
 */
public class PersistentExecutor {

  private static Logger logger = Logger.getLogger(PersistentExecutor.class.getName());

  private static final ProtoWorkerCoordinator coordinator = ProtoWorkerCoordinator.ofCommonsPool();

  static final Path workRootsDir = Paths.get("/tmp/worker/persistent/");

  static final String PERSISTENT_WORKER_FLAG = "--persistent_worker";

  static final String JAVABUILDER_JAR = "external/remote_java_tools/java_tools/JavaBuilder_deploy.jar";

  private static final String SCALAC_EXEC_NAME = "Scalac";
  private static final String JAVAC_EXEC_NAME = "JavaBuilder";

  /**
   * 1) Parse tool inputs and request inputs
   * 2) Makes the WorkerKey
   * 3) Runs the work request on its Coordinator, passing it the required context
   * 4) Ensures that results/outputs are in the right place
   */
  public static Code runOnPersistentWorker(
      WorkFilesContext context,
      String operationName,
      ImmutableList<String> argsList,
      ImmutableMap<String, String> envVars,
      ResourceLimits limits,
      Duration timeout,
      ActionResult.Builder resultBuilder
  ) throws IOException {

    logger.log(Level.FINE, "executeCommandOnPersistentWorker[" + operationName + "]");

    // Let's hardcode for easy win
    String executionName = getExecutionName(argsList);
    if (executionName.isEmpty()) {
      logger.log(Level.SEVERE, "Invalid Argument?!: " + argsList);
      return Code.INVALID_ARGUMENT;
    }

    int jarOrBinIdx;
    ImmutableMap<String, String> env;
    if (executionName.equals(JAVAC_EXEC_NAME)) {
      env = ImmutableMap.of();
      jarOrBinIdx = argsList.indexOf(JAVABUILDER_JAR);
    } else {
      // Scalac
      jarOrBinIdx = 0;
      env = envVars;
    }

    // flags aren't part of the request
    // this should definitely fail on a flag with a param...
    // Maybe hardcode to first argsfile? if only I could build bazel.
    int requestArgsIdx = jarOrBinIdx + 1;
    for (String s : argsList) {
      if (s.startsWith("-")) {
        requestArgsIdx = Math.max(requestArgsIdx, argsList.lastIndexOf(s) + 1);
      }
    }
    List<String> flags = argsList.subList(jarOrBinIdx + 1, requestArgsIdx);

    ImmutableList<String> workerExecCmd = argsList.subList(0, jarOrBinIdx + 1);
    ImmutableList<String> workerInitArgs = ImmutableList.<String>builder()
        .addAll(flags)
        .add(PERSISTENT_WORKER_FLAG)
        .build();
    ImmutableList<String> requestArgs = argsList.subList(requestArgsIdx, argsList.size());

    ParsedWorkFiles workerFiles = ParsedWorkFiles.from(context);

    Path binary = Paths.get(workerExecCmd.get(0));
    if (!workerFiles.containsTool(binary)) {
      throw new IllegalArgumentException("Binary isn't a tool?! " + binary);
    }

    WorkerKey key = Keymaker.make(
        workerExecCmd,
        workerInitArgs,
        env,
        executionName,
        workerFiles
    );

    ImmutableList<Input> reqInputs = workerFiles.allInputs.values().asList();

    WorkRequest request = WorkRequest.newBuilder()
        .addAllArguments(requestArgs)
        .addAllInputs(reqInputs)
        .setRequestId(0)
        .build();

    logger.log(Level.FINE, "Request with key: " + key);
    WorkResponse response;
    String stdErr = "";
    try {
      FullResponse fullResponse = coordinator.runRequest(key, request);
      response = fullResponse.response;
      stdErr = fullResponse.errorString;
      Path outputPath = fullResponse.outputPath;

      if (response.getExitCode() == 0) {
        // Why is paths empty when files are not?
        logger.log(Level.FINE, "getOutputPathsList:");
        logger.log(Level.FINE, context.outputPaths.toString());
        logger.log(Level.FINE, "getOutputFilesList:");
        logger.log(Level.FINE, context.outputFiles.toString());
        logger.log(Level.FINE, "getOutputDirectoriesList:");
        logger.log(Level.FINE, context.outputDirectories.toString());

        for (String relOutput : context.outputFiles) {
          Path relPath = Paths.get(relOutput);
          Path workPath = outputPath.resolve(relPath);
          Path opPath = opRoot.resolve(relPath);
          logger.log(Level.FINE, "Copying output from " + workPath + " to " + opPath);
          Files.copy(workPath, opPath, REPLACE_EXISTING, COPY_ATTRIBUTES);
        }

        // ??? see DockerExecutor::copyOutputsOutOfContainer
        for (String outputDir : context.outputDirectories) {
          Path outputDirPath = opRoot.resolve(outputDir);
          outputDirPath.toFile().mkdirs();
        }
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Exception while running request: " + e.getMessage());
      e.printStackTrace();
      response = WorkResponse.newBuilder()
          .setOutput("Exception while running request: " + e)
          .setExitCode(1)
          .build();
    }

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
    logger.log(Level.SEVERE, "Wtf? " + exitCode + "\n" + responseOut);
    return Code.FAILED_PRECONDITION;
  }

  private static String getExecutionName(ImmutableList<String> argsList) {
    boolean isScalac = argsList.size() > 1 && argsList.get(0).endsWith("scalac/scalac");
    if (isScalac) {
      return SCALAC_EXEC_NAME;
    } else if (argsList.contains(JAVABUILDER_JAR)) {
      return JAVAC_EXEC_NAME;
    }
    return "";
  }
}
