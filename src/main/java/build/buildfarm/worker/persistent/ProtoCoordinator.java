package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkCoordinator;
import persistent.bazel.client.WorkerKey;
import persistent.common.CommonsPool;

public class ProtoCoordinator extends WorkCoordinator<RequestCtx, ResponseCtx> {

  private static final Logger logger = Logger.getLogger(ProtoCoordinator.class.getName());

  public ProtoCoordinator(CommonsPool<WorkerKey, PersistentWorker> workerPool) {
    super(workerPool);
  }

  @Override
  public WorkerProtocol.WorkRequest preWorkInit(
      RequestCtx request, PersistentWorker worker
  ) throws IOException {
    return null;
  }

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
