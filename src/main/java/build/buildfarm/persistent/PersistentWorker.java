package build.buildfarm.persistent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

/**
 * Not to be confused with buildfarm's Worker class
 * This is an implementation of Bazel's persistent worker idea
 * It is an object which encapsulates the worker executable subprocess,
 * which is spawned upon prepareExecution().
 */
public class PersistentWorker {

  /**
   * Stream for recording the WorkResponse as it's read, so that it can be printed in the case of
   * parsing failures.
   */
  private InputStream recordingInputStream;
  /** The implementation of the worker protocol (JSON or Proto). */
  private ProtoWorkerProtocol workerProtocol;

  // TODO "Subprocess"?
  private Process process;
  /** True if we deliberately destroyed this process. */
  private boolean wasDestroyed;

  private Thread shutdownHook;


  /** An unique identifier of the work process. */
  protected final WorkerKey workerKey;
  /** An unique ID of the worker. It will be used in WorkRequest and WorkResponse as well. */
  protected final int workerId;
  /** The path of the log file for this worker. */
  protected final Path logFile;

  /** The execution root of the worker. */
  protected final Path workDir;

  public PersistentWorker(WorkerKey workerKey, int workerId, final Path workDir, Path logFile) {
    this.workerKey = workerKey;
    this.workerId = workerId;
    this.logFile = logFile;
    this.workDir = workDir;

    final PersistentWorker self = this;
    this.shutdownHook =
        new Thread(
            () -> {
              System.out.println("SHUTDOWNHOOK");
              self.shutdownHook = null;
              self.destroy();
            });
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  Process createProcess() throws IOException {
    ImmutableList<String> args = workerKey.getArgs();
    File executable = new File(args.get(0));
    if (!executable.isAbsolute() && executable.getParent() != null) {
      List<String> newArgs = new ArrayList<>(args);
      newArgs.set(0, new File(workDir.toFile(), newArgs.get(0)).getAbsolutePath());
      args = ImmutableList.copyOf(newArgs);
    }
    ProcessBuilder pb = new ProcessBuilder()
        .command(args)
        .directory(workDir.toFile())
        .redirectError(logFile.toFile());

    if (pb.environment() != null) {
      pb.environment().clear();
      pb.environment().putAll(workerKey.getEnv());
    }
    System.out.println("Starting process with args: " + String.join(",", args));
    System.out.println("pb.command(): " + String.join(",", pb.command()));
    return pb.start();
  }

  public boolean isSandboxed() {
    return false;
  }

  public void prepareExecution()
      throws IOException {
    if (process == null) {
      process = createProcess();
      recordingInputStream = process.getInputStream();
    }
    if (workerProtocol == null) {
      workerProtocol = new ProtoWorkerProtocol(process.getOutputStream(), recordingInputStream);
    }
  }

  void putRequest(WorkRequest request) throws IOException {
    workerProtocol.putRequest(request);
  }

  WorkResponse getResponse(int requestId) throws IOException, InterruptedException {
    while (recordingInputStream.available() == 0) {
      Thread.sleep(10);
      if (!process.isAlive()) {
        throw new IOException(
            String.format(
                "Worker process for %s died while waiting for response", workerKey.getMnemonic()));
      }
    }
    return workerProtocol.getResponse();
  }

  public void finishExecution() {}

  void destroy() {
    System.out.println("PersistentWorker.destroy");
    if (shutdownHook != null) {
      System.out.println("removing shutdownhook");
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }
    System.out.println("workerProtocol="+workerProtocol);
    if (workerProtocol != null) {
      System.out.println("workerProtocol.close()");
      // TODO try/catch?
      workerProtocol.close();
      workerProtocol = null;
    }
    if (process != null) {
      System.out.println("process != null");
      wasDestroyed = true;
      process.destroy();
      System.out.println("waiting for process to die");
      waitForUninterruptibly(process);
    } else {
      System.out.println("process is null");
    }
  }


  /** Waits for the process to finish in a non-interruptible manner. */
  void waitForUninterruptibly(Process p) {
    boolean wasInterrupted = false;
    try {
      while (true) {
        try {
          p.waitFor();
          return;
        } catch (InterruptedException ie) {
          wasInterrupted = true;
        }
      }
    } finally {
      // Read this for detailed explanation: http://www.ibm.com/developerworks/library/j-jtp05236/
      if (wasInterrupted) {
        Thread.currentThread().interrupt(); // preserve interrupted status
      }
    }
  }

  /** Returns true if this process is dead but we didn't deliberately kill it. */
  boolean diedUnexpectedly() {
    return process != null && !wasDestroyed && !process.isAlive();
  }

  public Optional<Integer> getExitValue() {
    return process != null && !process.isAlive()
        ? Optional.of(process.exitValue())
        : Optional.empty();
  }

  int getWorkerId() {
    return this.workerId;
  }

  /** Returns the path of the log file for this worker. */
  public Path getLogFile() {
    return logFile;
  }

  HashCode getWorkerFilesCombinedHash() {
    return workerKey.getWorkerFilesCombinedHash();
  }

  SortedMap<Path, HashCode> getWorkerFilesWithHashes() {
    return workerKey.getWorkerFilesWithHashes();
  }

  @Override
  public String toString() {
    return workerKey.getMnemonic() + " worker #" + workerId;
  }
}
