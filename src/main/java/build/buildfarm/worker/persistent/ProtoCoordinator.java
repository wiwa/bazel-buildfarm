package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.bazel.client.CommonsWorkerPool;
import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkCoordinator;
import persistent.bazel.client.WorkerKey;

import static persistent.bazel.client.PersistentWorker.TOOL_INPUT_SUBDIR;

/**
 * Responsible for:
 * 1) Initializing a new Worker's file environment correctly
 * 2) pre-request requirements
 * 3) post-response requirements, i.e. putting output files in the right place
 */
public class ProtoCoordinator extends WorkCoordinator<RequestCtx, ResponseCtx> {

  private static final Logger logger = Logger.getLogger(ProtoCoordinator.class.getName());

  public ProtoCoordinator(CommonsWorkerPool workerPool) {
    super(workerPool);
  }

  public ProtoCoordinator(PersistentWorker.Supervisor supervisor, int maxWorkersPerKey) {
    super(new CommonsWorkerPool(supervisor, maxWorkersPerKey));
  }

  // We copy tool inputs from the shared WorkerKey tools directory into our worker exec root,
  //    since there are multiple workers per key,
  //    and presumably there might be writes to tool inputs?
  // Tool inputs which are absolute-paths (e.g. /usr/bin/...) are not affected
  public static ProtoCoordinator ofCommonsPool(int maxWorkersPerKey) {
    PersistentWorker.Supervisor loadToolsOnCreate = new PersistentWorker.Supervisor() {
      @Override
      public PersistentWorker create(WorkerKey workerKey) throws Exception {
        Path keyExecRoot = workerKey.getExecRoot();
        String workerExecDir = getUniqueSubdir(keyExecRoot);
        loadToolsIntoWorkerRoot(workerKey, keyExecRoot.resolve(workerExecDir));
        return new PersistentWorker(workerKey, workerExecDir);
      }
    };
    return new ProtoCoordinator(loadToolsOnCreate, maxWorkersPerKey);
  }

  private static String getUniqueSubdir(Path workRoot) {
    String uuid = UUID.randomUUID().toString();
    while (Files.exists(workRoot.resolve(uuid))) {
      uuid = UUID.randomUUID().toString();
    }
    return uuid;
  }

  private static void loadToolsIntoWorkerRoot(
      WorkerKey key, Path workerExecRoot
  ) throws IOException {
    logger.log(Level.FINE, "loadToolsIntoWorkerRoot() into: " + workerExecRoot);

    Path toolInputRoot = key.getExecRoot().resolve(TOOL_INPUT_SUBDIR);
    for (Path relPath : key.getWorkerFilesWithHashes().keySet()) {
      Path toolInputPath = toolInputRoot.resolve(relPath);
      Path execRootPath = workerExecRoot.resolve(relPath);

      FileAccessUtils.copyFile(toolInputPath, execRootPath);
    }
  }

  // For now, we assume that each operation corresponds to a unique worker
  @Override
  public WorkRequest preWorkInit(
      WorkerKey key, RequestCtx request, PersistentWorker worker
  ) throws IOException {

    copyInputs(request.workerInputs, worker.getExecRoot());

    return request.request;
  }

  private void copyInputs(WorkerInputs workerInputs, Path execRoot) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (Path opPath : workerInputs.allInputs.keySet()) {
      if (opPath.endsWith(".params")) {
        sb.append("\n\t" + opPath.toString());
      }
      Path execPath = workerInputs.relativizeInput(execRoot, opPath);
      workerInputs.accessFileFrom(opPath, execPath);
    }
    logger.fine("copyInputs[.params]: " + sb.toString() + "\n");
  }

  // After the worker has finished, we need to copy output files back to the operation directory
  @Override
  public ResponseCtx postWorkCleanup(
      WorkResponse response, PersistentWorker worker, RequestCtx request
  ) throws IOException {

    exposeOutputFiles(request.filesContext, worker.getExecRoot());

    return new ResponseCtx(response, worker.flushStdErr());
  }

  private void exposeOutputFiles(WorkFilesContext context, Path workerExecRoot) throws IOException {
    Path opRoot = context.opRoot;

    StringBuilder sb = new StringBuilder();
    // Why is paths empty when files are not?
    sb.append("getOutputPathsList:\n");
    sb.append(context.outputPaths);
    sb.append("getOutputFilesList:\n");
    sb.append(context.outputFiles);
    sb.append("getOutputDirectoriesList:\n");
    sb.append(context.outputDirectories);
    logger.fine(sb.toString());

    // ??? see DockerExecutor::copyOutputsOutOfContainer
    for (String outputDir : context.outputDirectories) {
      Path outputDirPath = Paths.get(outputDir);
      Files.createDirectories(outputDirPath);
    }

    for (String relOutput : context.outputFiles) {
      Path relPath = Paths.get(relOutput);
      Path opOutputPath = opRoot.resolve(relPath);
      Path execOutputPath = workerExecRoot.resolve(relPath);

      FileAccessUtils.copyFile(execOutputPath, opOutputPath);
    }
  }
}
