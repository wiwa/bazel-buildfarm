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
    logger.log(Level.FINE, "loadToolInputFiles() into: " + workerExecRoot);

    Path toolInputRoot = key.getExecRoot().resolve(TOOL_INPUT_SUBDIR);
    for (Path relPath : key.getWorkerFilesWithHashes().keySet()) {
      Path toolInputPath = toolInputRoot.resolve(relPath);
      Path execRootPath = workerExecRoot.resolve(relPath);

      FileAccessUtils.copyFile(toolInputPath, execRootPath);
    }
  }

  // For now, we assume that each operation corresponds to a unique worker
  // Thus, we don't need to copy any files
  // The request context will have its inputs point to the operation directory
  @Override
  public WorkRequest preWorkInit(
      WorkerKey key, RequestCtx request, PersistentWorker worker
  ) throws IOException {
    return request.request;
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

    // Why is paths empty when files are not?
    logger.log(Level.FINE, "getOutputPathsList:");
    logger.log(Level.FINE, context.outputPaths.toString());
    logger.log(Level.FINE, "getOutputFilesList:");
    logger.log(Level.FINE, context.outputFiles.toString());
    logger.log(Level.FINE, "getOutputDirectoriesList:");
    logger.log(Level.FINE, context.outputDirectories.toString());

    // ??? see DockerExecutor::copyOutputsOutOfContainer
    for (String outputDir : context.outputDirectories) {
      Path outputDirPath = Paths.get(outputDir);
      Files.createDirectories(outputDirPath);
    }

    for (String opOutput : context.outputFiles) {
      Path opOutputPath = Paths.get(opOutput);
      Path relPath = opRoot.relativize(opOutputPath);
      Path execOutputPath = workerExecRoot.resolve(relPath);

      FileAccessUtils.copyFile(execOutputPath, opOutputPath);
    }
  }
}
