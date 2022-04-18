package persistent.bazel.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import persistent.bazel.processes.ProtoWorkerRW;
import persistent.common.Worker;
import persistent.common.processes.ProcessWrapper;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Takes care of the underlying process's environment, i.e. directories, files
 */
public class PersistentWorker implements Worker<WorkRequest, WorkResponse> {

  private static final Logger logger = Logger.getLogger(PersistentWorker.class.getName());

  public static final String TOOL_INPUT_SUBDIR = "tool_inputs";

  private final WorkerKey key;
  private final ImmutableList<String> initCmd;
  private final Path execRoot;
  private final ProtoWorkerRW workerRW;

  public PersistentWorker(WorkerKey key, String workerDir) throws IOException {
    this.key = key;
    this.execRoot = key.getExecRoot().resolve(workerDir);
    this.initCmd = ImmutableList.<String>builder()
        .addAll(key.getCmd())
        .addAll(key.getArgs())
        .build();

    Files.createDirectories(execRoot);

    Set<Path> workerFiles = ImmutableSet.copyOf(key.getWorkerFilesWithHashes().keySet());
    logger.log(
        Level.FINE,
        "Starting Worker[" + key.getMnemonic() + "]<" + execRoot + ">("
            + initCmd + ") with files: \n"
            + workerFiles
    );

    ProcessWrapper processWrapper = new ProcessWrapper(execRoot, initCmd, key.getEnv());
    this.workerRW = new ProtoWorkerRW(processWrapper);
  }

  @Override
  public WorkResponse doWork(WorkRequest request) {
    WorkResponse response = null;
    try {
      logRequest(request);

      workerRW.write(request);
      response = workerRW.waitAndRead();

      logIfBadResponse(response);
    } catch (IOException e) {
      e.printStackTrace();
      logger.severe("IO Failing with : " + e.getMessage());
    } catch (Exception e) {
      e.printStackTrace();
      logger.severe("Failing with : " + e.getMessage());
    }
    return response;
  }

  public Optional<Integer> getExitValue() {
    ProcessWrapper pw = workerRW.getProcessWrapper();
    return pw != null && !pw.isAlive()
        ? Optional.of(pw.exitValue())
        : Optional.empty();
  }

  public String flushStdErr() {
    try {
      return this.workerRW.getProcessWrapper().flushErrorString();
    } catch (IOException ignored) {
      return "";
    }
  }

  public ImmutableList<String> getInitCmd() {
    return this.initCmd;
  }

  public Path getExecRoot() {
    return this.execRoot;
  }

  private void logRequest(WorkRequest request) {
    logger.log(
        Level.FINE,
        "doWork()------<" +
            "Got request with args: " + request.getArgumentsList() +
            "------>"
    );
  }

  private void logIfBadResponse(WorkResponse response) throws IOException {
    int returnCode = response.getExitCode();
    if (returnCode != 0) {
      StringBuilder sb = new StringBuilder();
      sb.append("logBadResponse()");
      sb.append("\nResponse non-zero exit_code: ");
      sb.append(returnCode);
      sb.append("Response output: ");
      sb.append(response.getOutput());
      sb.append("\n\tProcess stderr: ");
      String stderr = workerRW.getProcessWrapper().getErrorString();
      sb.append(stderr);
      logger.log(Level.SEVERE, sb.toString());

      // TODO might be able to remove this; scared that stdout might crash.
      StringBuilder sb2 = new StringBuilder();
      sb2.append("logBadResponse()");
      sb2.append("\n\tProcess stdout: ");
      IOUtils.readLines(workerRW.getProcessWrapper().getStdOut(), UTF_8).forEach(s -> {
        sb2.append(s);
        sb2.append("\n\t");
      });
      logger.log(Level.SEVERE, sb2.toString());
    }
  }

  @Override
  public void destroy() {
    this.workerRW.getProcessWrapper().destroy();
  }

  public static abstract class Supervisor extends persistent.common.Supervisor<WorkerKey, PersistentWorker> {

    public static Supervisor simple() {
      return new Supervisor() {
        @Override
        public PersistentWorker create(WorkerKey workerKey) throws Exception {
          return new PersistentWorker(workerKey, "");
        }
      };
    }

    public abstract PersistentWorker create(WorkerKey workerKey) throws Exception;

    @Override
    public PooledObject<PersistentWorker> wrap(PersistentWorker persistentWorker) {
      return new DefaultPooledObject<>(persistentWorker);
    }

    @Override
    public boolean validateObject(WorkerKey key, PooledObject<PersistentWorker> p) {
      PersistentWorker worker = p.getObject();
      Optional<Integer> exitValue = worker.getExitValue();
      if (exitValue.isPresent()) {
        String errorStr;
        try {
          String err = worker.workerRW.getProcessWrapper().getErrorString();
          errorStr = "Stderr:\n" + err;
        } catch (Exception e) {
          errorStr = "Couldn't read Stderr: " + e;
        }
        String msg = String.format(
            "Worker unexpectedly died with exit code %d. Key:\n%s\n%s",
            exitValue.get(),
            key,
            errorStr
        );
        logger.log(Level.SEVERE, msg);
        return false;
      }
      boolean filesChanged =
          !key.getWorkerFilesCombinedHash().equals(worker.key.getWorkerFilesCombinedHash());

      if (filesChanged) {
        StringBuilder msg = new StringBuilder();
        msg.append("Worker can no longer be used, because its files have changed on disk:\n");
        msg.append(key);
        TreeSet<Path> files = new TreeSet<>();
        files.addAll(key.getWorkerFilesWithHashes().keySet());
        files.addAll(worker.key.getWorkerFilesWithHashes().keySet());
        for (Path file : files) {
          HashCode oldHash = worker.key.getWorkerFilesWithHashes().get(file);
          HashCode newHash = key.getWorkerFilesWithHashes().get(file);
          if (!oldHash.equals(newHash)) {
            msg.append("\n")
                .append(file.normalize())
                .append(": ")
                .append(oldHash != null ? oldHash : "<none>")
                .append(" -> ")
                .append(newHash != null ? newHash : "<none>");
          }
        }
        logger.log(Level.SEVERE, msg.toString());
      }

      return !filesChanged;
    }
  }
}
