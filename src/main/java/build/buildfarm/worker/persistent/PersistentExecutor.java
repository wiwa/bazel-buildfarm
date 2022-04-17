package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.rpc.Code;

import build.bazel.remote.execution.v2.ActionResult;
import build.buildfarm.worker.util.TreeWalker;
import build.buildfarm.worker.resources.ResourceLimits;
import persistent.bazel.client.PersistentWorker;
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

  /**
   * 1) Parse tool inputs and request inputs
   * 2) Makes the WorkerKey
   * 3) Runs the work request on its Coordinator, passing it the required context
   * 4) Ensures that results/outputs are in the right place
   */
  public static Code runOnPersistentWorker(
      WorkFilesContext context,
      String operationName,
      List<String> arguments,
      Map<String, String> environmentVariables,
      ResourceLimits limits,
      Duration timeout,
      ActionResult.Builder resultBuilder
  ) throws IOException {

    logger.log(Level.FINE, "executeCommandOnPersistentWorker[" + operationName + "]");

    Path opRoot = context.opRoot;

    ImmutableList<String> argsList = ImmutableList.copyOf(arguments);

    ImmutableMap<String, String> env = ImmutableMap.copyOf(environmentVariables);

    // Let's hardcode for easy win
    int jarOrBinIdx = 0;
    boolean isScalac = arguments.size() > 1 && arguments.get(0).endsWith("scalac/scalac");
    String executionName = "Scalac";
    if (argsList.contains(JAVABUILDER_JAR)) {
      jarOrBinIdx = argsList.indexOf(JAVABUILDER_JAR);
      executionName = "JavaBuilder";
      env = ImmutableMap.of();
    } else if (!isScalac) {
      logger.log(Level.SEVERE, "Invalid Argument?!");
      return Code.INVALID_ARGUMENT;
    }

    // flags aren't part of the request
    int requestArgsIdx = jarOrBinIdx + 1;
    for (String s : argsList) {
      if (s.startsWith("-")) {
        requestArgsIdx = Math.max(requestArgsIdx, argsList.lastIndexOf(s) + 1);
      }
    }

    ImmutableList<String> workerExecCmd = argsList.subList(0, jarOrBinIdx + 1);
    List<String> flags = argsList.subList(jarOrBinIdx + 1, requestArgsIdx);
    ImmutableList<String> workerInitArgs = ImmutableList.<String>builder()
        .addAll(flags)
        .add(PERSISTENT_WORKER_FLAG)
        .build();
    ImmutableList<String> requestArgs = argsList.subList(requestArgsIdx, argsList.size());

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
    Path toolsRoot = workRoot.resolve(PersistentWorker.TOOL_INPUT_SUBDIR);
    Files.createDirectories(toolsRoot);

    ImmutableMap<Path, Input> pathInputs = new TreeWalker(context.execTree).getInputs(
        opRoot.toAbsolutePath());
    logger.log(Level.FINE, "pathInputs: " + pathInputs.keySet());

    ImmutableList<Path> absInputPaths = pathInputs.keySet().asList();
    ImmutableSet<Path> toolInputPaths = InputsExtractor.getToolFiles(opRoot, absInputPaths);
    logger.log(Level.FINE, "toolInputPaths=" + toolInputPaths);

    Path binary = Paths.get(workerExecCmd.get(0));
    if (!toolInputPaths.contains(binary) && !binary.isAbsolute()) {
      throw new IllegalArgumentException("Binary isn't a tool?! " + binary);
    }

    Hasher hasher = Hashing.sha256().newHasher();
    ImmutableSortedMap.Builder<Path, HashCode> workerFileHashBuilder = ImmutableSortedMap.naturalOrder();

    for (Path relPath : toolInputPaths) {
      Path absPathFromOpRoot = opRoot.resolve(relPath).toAbsolutePath();
      Path absPathFromToolsRoot = toolsRoot.resolve(relPath).toAbsolutePath();
      Files.createDirectories(absPathFromToolsRoot.getParent());
      if (!Files.exists(absPathFromToolsRoot)) {
        logger.log(Level.FINE, "Toolcopy: " + absPathFromOpRoot + " to " + absPathFromToolsRoot);
        Files.copy(absPathFromOpRoot, absPathFromToolsRoot, REPLACE_EXISTING, COPY_ATTRIBUTES);
      }

      HashCode toolInputHash = HashCode.fromBytes(
          pathInputs.get(absPathFromOpRoot).getDigest().toByteArray());
      workerFileHashBuilder.put(absPathFromToolsRoot, toolInputHash);
      hasher.putString(absPathFromToolsRoot.toString(), StandardCharsets.UTF_8);
      hasher.putBytes(toolInputHash.asBytes());
    }

    HashCode workerFilesCombinedHash = hasher.hash();
    SortedMap<Path, HashCode> workerFilesWithHashes = workerFileHashBuilder.build();

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

    ImmutableList<Input> reqInputs = pathInputs.values().asList();

    ImmutableList<String> argsWithOpRoot = ImmutableList.<String>builder()
        .add(opRoot.toString())
        .addAll(requestArgs)
        .build();

    WorkRequest request = WorkRequest.newBuilder()
        .addAllArguments(argsWithOpRoot)
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
}
