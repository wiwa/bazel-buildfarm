package build.buildfarm.persistent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

class WorkerFactory extends BaseKeyedPooledObjectFactory<WorkerKey, PersistentWorker> {

  // It's fine to use an AtomicInteger here (which is 32-bit), because it is only incremented when
  // spawning a new worker, thus even under worst-case circumstances and buggy workers quitting
  // after each action, this should never overflow.
  // This starts at 1 to avoid hiding latent problems of multiplex workers not returning a
  // request_id (which is indistinguishable from 0 in proto3).
  private static final AtomicInteger pidCounter = new AtomicInteger(1);

  private final Path workerBaseDir;
  private final boolean workerSandboxing;

  public WorkerFactory(Path workerBaseDir, boolean workerSandboxing) {
    this.workerBaseDir = workerBaseDir;
    this.workerSandboxing = workerSandboxing;
  }

  @Override
  public PersistentWorker create(WorkerKey key) {
    int workerId = pidCounter.getAndIncrement();
    String workTypeName = key.getWorkerTypeName();
    Path logFile =
        workerBaseDir.resolve(workTypeName + "-" + workerId + "-" + key.getMnemonic() + ".log");

    // worker = new SingleplexWorker(key, workerId, key.getExecRoot(), logFile);
    PersistentWorker worker = new PersistentWorker(key, workerId, key.getExecRoot(), logFile);
    boolean sandboxed = workerSandboxing || key.isSpeculative();
    String msg =
        String.format(
            "Created new %s %s %s (id %d), logging to %s",
            sandboxed ? "sandboxed" : "non-sandboxed",
            key.getMnemonic(),
            workTypeName,
            workerId,
            logFile);
            //worker.getLogFile());
    System.out.println(msg);
    return worker;
  }

  Path getSandboxedWorkerPath(WorkerKey key, int workerId) {
    Path root = key.getExecRoot();
    String workspaceName = root.getFileName().toString();
    return workerBaseDir
        .resolve(key.getWorkerTypeName() + "-" + workerId + "-" + key.getMnemonic())
        .resolve(workspaceName);
  }

  /** Use the DefaultPooledObject implementation. */
  @Override
  public PooledObject<PersistentWorker> wrap(PersistentWorker worker) {
    return new DefaultPooledObject<>(worker);
  }

  /** When a worker object is discarded, destroy its process, too. */
  @Override
  public void destroyObject(WorkerKey key, PooledObject<PersistentWorker> p) {
    System.out.println("destroyObject");
    int workerId = p.getObject().getWorkerId();
    System.out.println(
        String.format(
            "Destroying %s %s (id %d)",
            key.getMnemonic(), key.getWorkerTypeName(), workerId));
    p.getObject().destroy();
  }

  /**
   * Returns true if this worker is still valid. The worker is considered to be valid as long as its
   * process has not exited and its files have not changed on disk.
   */
  @Override
  public boolean validateObject(WorkerKey key, PooledObject<PersistentWorker> p) {
    System.out.println("validateObject");
    PersistentWorker worker = p.getObject();

    System.out.println("p.gotObject");
    Optional<Integer> exitValue = worker.getExitValue();
    if (exitValue.isPresent()) {
      // TODO log?
      return false;
    }
    boolean filesChanged =
        !key.getWorkerFilesCombinedHash().equals(worker.getWorkerFilesCombinedHash());

    System.out.println("filesChanged=" + filesChanged);
    return !filesChanged;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof WorkerFactory)) {
      return false;
    }
    WorkerFactory that = (WorkerFactory) o;
    return workerSandboxing == that.workerSandboxing && workerBaseDir.equals(that.workerBaseDir);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workerBaseDir, workerSandboxing);
  }
}
