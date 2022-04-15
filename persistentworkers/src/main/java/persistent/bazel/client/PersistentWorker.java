package persistent.bazel.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import persistent.common.KeyedWorker;
import persistent.common.processes.ProcessWrapper;
import persistent.bazel.processes.ProtoWorkerRW;

import static java.nio.charset.StandardCharsets.UTF_8;

public class PersistentWorker implements KeyedWorker<WorkerKey, WorkRequest, WorkResponse> {

  private final WorkerKey key;

  private final ProtoWorkerRW workerRW;

  public PersistentWorker(WorkerKey key) throws IOException {
    this.key = key;
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableList<String> initCmd = builder
        .addAll(key.getCmd())
        .addAll(key.getArgs())
        .build();

    Set<Path> workerFiles = ImmutableSet.copyOf(key.getWorkerFilesWithHashes().keySet());
    System.out.println("Starting Worker[" + key.getMnemonic() + "] with files: \n" + workerFiles);

    Path execRoot = key.getExecRoot();
    Files.createDirectories(execRoot);
    ProcessWrapper processWrapper = new ProcessWrapper(execRoot, initCmd, key.getEnv());
    this.workerRW = new ProtoWorkerRW(processWrapper);
  }

  @Override
  public WorkerKey getKey() {
    return this.key;
  }

  @Override
  public WorkResponse doWork(WorkRequest request) {
    WorkResponse response = null;
    try {
      String reqMsg = "------<" +
          "Got request with args: " +
          request.getArgumentsList() +
          "Request inputs: " +
          request.getInputsList() +
          "------>";
      System.out.println(reqMsg);

      workerRW.write(request);
      response = workerRW.waitAndRead();
      int returnCode = response.getExitCode();
      if (returnCode != 0) {
        StringBuilder sb = new StringBuilder();
        sb.append("Response non-zero exit_code: ");
        sb.append(returnCode);
        sb.append("\n\tProcess stderr: ");
        IOUtils.readLines(workerRW.getProcessWrapper().getStdErr(), UTF_8).forEach(s -> {
          sb.append(s);
          sb.append("\n\t");
        });
        System.out.println(sb);

        // TODO might be able to remove this; scared that stdout might crash.
        StringBuilder sb2 = new StringBuilder();
        sb2.append("\n\tProcess stdout: ");
        IOUtils.readLines(workerRW.getProcessWrapper().getStdOut(), UTF_8).forEach(s -> {
          sb2.append(s);
          sb2.append("\n\t");
        });
        System.out.println(sb2);
      }
    } catch (IOException e) {
      System.out.println("IO Failing with : " + e.getMessage());
    } catch (Exception e) {
      System.out.println("Failing with : " + e.getMessage());
    }
    return response;
  }

  @Override
  public void destroy() {
    this.workerRW.getProcessWrapper().destroy();
  }

  public Optional<Integer> getExitValue() {
    ProcessWrapper pw = workerRW.getProcessWrapper();
    return pw != null && !pw.isAlive()
        ? Optional.of(pw.exitValue())
        : Optional.empty();
  }

  public static class Supervisor extends BaseKeyedPooledObjectFactory<WorkerKey, PersistentWorker> {

    private static Supervisor singleton = null;

    public static synchronized Supervisor get() {
      if (singleton == null) {
        singleton = new Supervisor();
      }
      return singleton;
    }

    @Override
    public PersistentWorker create(WorkerKey workerKey) throws Exception {
      return new PersistentWorker(workerKey);
    }

    @Override
    public PooledObject<PersistentWorker> wrap(PersistentWorker persistentWorker) {
      return new DefaultPooledObject<>(persistentWorker);
    }


    /** When a worker process is discarded, destroy its process, too. */
    @Override
    public void destroyObject(WorkerKey key, PooledObject<PersistentWorker> p) {
      StringBuilder msgBuilder = new StringBuilder();
      msgBuilder.append("Destroying worker from WorkerKey: ");
      msgBuilder.append(key);
      for (StackTraceElement e: Thread.currentThread().getStackTrace()) {
        msgBuilder.append("\n\t");
        msgBuilder.append(e);
      }
      System.out.println(msgBuilder);
      p.getObject().destroy();
    }

    @Override
    public boolean validateObject(WorkerKey key, PooledObject<PersistentWorker> p) {
      PersistentWorker worker = p.getObject();
      Optional<Integer> exitValue = worker.getExitValue();
      if (exitValue.isPresent()) {
        String msg = String.format("Worker unexpectedly died with exit code %d. Key:\n%s", exitValue.get(), key);
        System.out.println(msg);
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
        System.out.println(msg);
      }

      return !filesChanged;
    }
  }
}
