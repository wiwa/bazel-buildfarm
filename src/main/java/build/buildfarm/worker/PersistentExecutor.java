package build.buildfarm.worker;

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
import build.buildfarm.v1test.Tree;
import build.buildfarm.worker.resources.ResourceLimits;
import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.ProtoWorkerCoordinator;
import persistent.bazel.client.ProtoWorkerCoordinator.FullResponse;
import persistent.bazel.client.WorkerKey;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class PersistentExecutor {

  private static Logger logger = Logger.getLogger(PersistentExecutor.class.getName());

  private static final ProtoWorkerCoordinator coordinator = ProtoWorkerCoordinator.ofCommonsPool();

  private static final Path workRootsDir = Paths.get("/tmp/worker/persistent/");

  static final String PERSISTENT_WORKER_FLAG = "--persistent_worker";

  static final String JAVABUILDER_JAR = "external/remote_java_tools/java_tools/JavaBuilder_deploy.jar";

  static Code runOnPersistentWorker(
      OperationContext operationContext,
      String operationName,
      Tree execTree,
      Path operationDir,
      List<String> arguments,
      Map<String, String> environmentVariables,
      ResourceLimits limits,
      Duration timeout,
      ActionResult.Builder resultBuilder
  ) throws IOException {

    logger.log(Level.FINE, "executeCommandOnPersistentWorker[" + operationName + "]");

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

    ImmutableMap<Path, Input> pathInputs = new TreeWalker(execTree).getInputs(
        operationDir.toAbsolutePath());
    logger.log(Level.FINE, "pathInputs:");
    for (Input in : pathInputs.values()) {
      logger.log(Level.FINE, "\t" + in.getPath());
    }


    ImmutableList<Path> absInputPaths = pathInputs.keySet().asList();
    ImmutableSet<Path> toolInputPaths = getToolFiles(operationDir, absInputPaths);
    logger.log(Level.FINE, "toolInputPaths=" + toolInputPaths);

    Path binary = Paths.get(workerExecCmd.get(0));
    if (!toolInputPaths.contains(binary) && !binary.isAbsolute()) {
      throw new IllegalArgumentException("Binary isn't a tool?! " + binary);
    }

    Hasher hasher = Hashing.sha256().newHasher();
    ImmutableSortedMap.Builder<Path, HashCode> workerFileHashBuilder = ImmutableSortedMap.naturalOrder();

    for (Path relPath : toolInputPaths) {
      Path absPathFromOpRoot = operationDir.resolve(relPath).toAbsolutePath();
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

    // ImmutableList.Builder<Input> workRootInputs = ImmutableList.builder();
    //
    // for (Map.Entry<Path, Input> pathInput : pathInputs.entrySet()) {
    //   Path opRootPath = pathInput.getKey();
    //   Path relPath = operationDir.relativize(opRootPath);
    //   Path workRootPath = workRoot.resolve(relPath);
    //   Input workRootInput = pathInput.getValue().toBuilder().setPath(
    //       workRootPath.toString()).build();
    //
    //   if (!toolInputPaths.contains(relPath)) {
    //     Files.createDirectories(workRootPath.getParent());
    //     Files.copy(opRootPath, workRootPath, REPLACE_EXISTING, COPY_ATTRIBUTES);
    //   }
    //
    //   workRootInputs.add(workRootInput);
    // }

    ImmutableList<Input> reqInputs = pathInputs.values().asList();

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
        logger.log(Level.FINE, operationContext.command.getOutputPathsList().toString());
        logger.log(Level.FINE, "getOutputFilesList:");
        logger.log(Level.FINE, operationContext.command.getOutputFilesList().toString());
        logger.log(Level.FINE, "getOutputDirectoriesList:");
        logger.log(Level.FINE, operationContext.command.getOutputDirectoriesList().toString());

        for (String relOutput : operationContext.command.getOutputFilesList()) {
          Path relPath = Paths.get(relOutput);
          Path workPath = outputPath.resolve(relPath);
          Path opPath = operationDir.resolve(relPath);
          logger.log(Level.FINE, "Copying output from " + workPath + " to " + opPath);
          Files.copy(workPath, opPath, REPLACE_EXISTING, COPY_ATTRIBUTES);
        }

        // ??? see DockerExecutor::copyOutputsOutOfContainer
        for (String outputDir : operationContext.command.getOutputDirectoriesList()) {
          Path outputDirPath = operationDir.resolve(outputDir);
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

  // Returns file paths under opRoot after relativizing them via opRoot
  static ImmutableSet<Path> getToolFiles(Path opRoot, List<Path> files) {
    return ImmutableSet.copyOf(
        files
            .stream()
            .filter(path -> {
              String pathStr = path.toString();
              return pathStr.contains("external/remotejdk11_linux/") ||
                  pathStr.contains("external/remote_java_tools/") ||
                  pathStr.endsWith("/external/bazel_tools/tools/jdk/platformclasspath.jar") ||
                  pathStr.endsWith("/scalac.jar") ||
                  pathStr.endsWith("_deploy.jar") ||
                  pathStr.endsWith("scalac/scalac") ||
                  pathStr.contains(
                      "external/io_bazel_rules_scala/src/java/io/bazel/rulesscala/scalac/scalac.runfiles");
            })
            .map(opRoot::relativize)
            .iterator()
    );
  }
}
