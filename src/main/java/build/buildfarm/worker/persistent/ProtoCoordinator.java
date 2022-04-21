package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.Duration;

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
public class ProtoCoordinator extends WorkCoordinator<RequestCtx, ResponseCtx, CommonsWorkerPool> {

  private static final Logger logger = Logger.getLogger(ProtoCoordinator.class.getName());

  private static final String WORKER_INIT_LOG_SUFFIX = ".initargs.log";

  private static final ConcurrentHashMap<RequestCtx, PersistentWorker> pendingReqs = new ConcurrentHashMap<>();

  private static final Timer timeoutScheduler = new Timer("persistent-worker-timeout", true);

  private static final ConcurrentHashMap<WorkerKey, EasyMonitor> toolInputSyncs = new ConcurrentHashMap<>();

  private static EasyMonitor keyLock(WorkerKey key) {
    return toolInputSyncs.computeIfAbsent(key, k -> new EasyMonitor());
  }

  private static class EasyMonitor {
    public EasyMonitor(){}
  }

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
        Path workerExecRoot = keyExecRoot.resolve(workerExecDir);
        copyToolsIntoWorkerExecRoot(workerKey, workerExecRoot);

        Path initArgsLogFile = workerExecRoot.resolve(workerExecDir + WORKER_INIT_LOG_SUFFIX);
        if (!Files.exists(initArgsLogFile)) {
          StringBuilder initArgs = new StringBuilder();
          for (String s : workerKey.getCmd()) {
            initArgs.append(s);
            initArgs.append("\n");
          }
          for (String s : workerKey.getArgs()) {
            initArgs.append(s);
            initArgs.append("\n");
          }

          Files.write(initArgsLogFile, initArgs.toString().getBytes());
        }

        return new PersistentWorker(workerKey, workerExecDir);
      }
    };
    return new ProtoCoordinator(loadToolsOnCreate, maxWorkersPerKey);
  }

  public void copyToolInputsIntoWorkerToolRoot(WorkerKey key, WorkerInputs workerFiles) throws IOException {
    EasyMonitor lock = keyLock(key);
    synchronized (lock) {
      try {
        //// Move tool inputs as needed
        Path workToolRoot = key.getExecRoot().resolve(PersistentWorker.TOOL_INPUT_SUBDIR);
        for (Path opToolPath : workerFiles.opToolInputs) {
          Path workToolPath = workerFiles.relativizeInput(workToolRoot, opToolPath);
          if (!Files.exists(workToolPath)) {
            workerFiles.copyInputFile(opToolPath, workToolPath);
          }
        }
      } finally {
        toolInputSyncs.remove(key);
      }
    }
  }

  private static String getUniqueSubdir(Path workRoot) {
    String uuid = UUID.randomUUID().toString();
    while (Files.exists(workRoot.resolve(uuid))) {
      uuid = UUID.randomUUID().toString();
    }
    return uuid;
  }

  // moveToolInputsIntoWorkerToolRoot() should have been called before this.
  private static void copyToolsIntoWorkerExecRoot(
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

    PersistentWorker pendingWorker = pendingReqs.putIfAbsent(request, worker);
    if (pendingWorker != null) {
      if (pendingWorker != worker) {
        throw new IllegalArgumentException("Already have a persistent worker on the job: " + request.request);
      } else {
        throw new IllegalArgumentException("Got the same request for the same worker while it's running?!: " + request.request);
      }
    }
    startTimeoutTimer(request);

    linkNontoolInputs(request.workerInputs, worker.getExecRoot());

    return request.request;
  }

  // After the worker has finished, we need to copy output files back to the operation directory
  @Override
  public ResponseCtx postWorkCleanup(
      WorkResponse response, PersistentWorker worker, RequestCtx request
  ) throws IOException {

    pendingReqs.remove(request);

    if (response == null) {
      throw new RuntimeException("postWorkCleanup: WorkResponse was null!");
    }

    if (response.getExitCode() == 0) {
      try {
        Path workerExecRoot = worker.getExecRoot();
        moveOutputsToOperationRoot(request.filesContext, workerExecRoot);
        cleanUpNontoolInputs(request.workerInputs, workerExecRoot);
      } catch (IOException e) {
        throw logBadCleanup(request, e);
      }
    }

    return new ResponseCtx(response, worker.flushStdErr());
  }

  private IOException logBadCleanup(RequestCtx request, IOException e) {
    WorkFilesContext context = request.filesContext;

    StringBuilder sb = new StringBuilder();
    // Why is paths empty when files are not?
    sb.append(
        "Output files failure debug for request with args<" + request.request.getArgumentsList() + ">:\n");
    sb.append("getOutputPathsList:\n");
    sb.append(context.outputPaths);
    sb.append("getOutputFilesList:\n");
    sb.append(context.outputFiles);
    sb.append("getOutputDirectoriesList:\n");
    sb.append(context.outputDirectories);
    logger.severe(sb.toString());

    e.printStackTrace();
    return new IOException("Response was OK but failed on exposeOutputFiles", e);
  }

  // This should replace any existing symlinks
  private void linkNontoolInputs(WorkerInputs workerInputs, Path workerExecRoot) throws IOException {
    for (Path opPath : workerInputs.allInputs.keySet()) {
      if (!workerInputs.allToolInputs.contains(opPath)) {
        Path execPath = workerInputs.relativizeInput(workerExecRoot, opPath);
        workerInputs.linkInputFile(opPath, execPath);
      }
    }
  }

  private void moveOutputsToOperationRoot(WorkFilesContext context, Path workerExecRoot) throws IOException {
    Path opRoot = context.opRoot;

    // ??? see DockerExecutor::copyOutputsOutOfContainer
    for (String outputDir : context.outputDirectories) {
      Path outputDirPath = Paths.get(outputDir);
      Files.createDirectories(outputDirPath);
    }

    for (String relOutput : context.outputFiles) {
      Path relPath = Paths.get(relOutput);
      Path opOutputPath = opRoot.resolve(relPath);
      Path execOutputPath = workerExecRoot.resolve(relPath);

      FileAccessUtils.moveFile(execOutputPath, opOutputPath);
    }
  }

  private void cleanUpNontoolInputs(WorkerInputs workerInputs, Path workerExecRoot) throws IOException {
    for (Path opPath : workerInputs.allInputs.keySet()) {
      if (!workerInputs.allToolInputs.contains(opPath)) {
        workerInputs.deleteInputFileIfExists(workerExecRoot, opPath);
      }
    }
  }

  private void startTimeoutTimer(RequestCtx request) {
    Duration timeout = request.timeout;
    if (timeout != null) {
      long timeoutNanos = timeout.getSeconds() * 1000000000L + timeout.getNanos();
      timeoutScheduler.schedule(new RequestTimeoutHandler(request), timeoutNanos);
    }
  }

  private class RequestTimeoutHandler extends TimerTask {

    private final RequestCtx request;

    private RequestTimeoutHandler(RequestCtx request) {
      this.request = request;
    }

    @Override
    public void run() {
      onTimeout(this.request, pendingReqs.get(this.request));
    }
  }

  private void onTimeout(RequestCtx request, PersistentWorker worker) {
    if (worker != null) {
      logger.severe("Persistent Worker timed out on request: " + request.request);
      try {
        this.workerPool.invalidateObject(worker.getKey(), worker);
      } catch (Exception e) {
        logger.severe("Tried to invalidate worker for request:\n" + request + "\n\tbut got: " + e + "\n\nCalling worker.destroy() and moving on.");
        worker.destroy();
      }
    }
  }
}
