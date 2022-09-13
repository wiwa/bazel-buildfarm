package persistent.bazel.client;

import java.nio.file.Path;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;

public class ProtoWorkerCoordinator {

  private final GenericKeyedObjectPool<WorkerKey, PersistentWorker> workerPool;

  ProtoWorkerCoordinator(GenericKeyedObjectPool<WorkerKey, PersistentWorker> workerPool) {
    this.workerPool = workerPool;
  }

  public static ProtoWorkerCoordinator ofCommonsPool() {
    return new ProtoWorkerCoordinator(
        new CommonsWorkerPool(PersistentWorker.Supervisor.simple(), 4));
  }

  public FullResponse runRequest(WorkerKey workerKey, WorkRequest request) throws Exception {
    PersistentWorker worker = workerPool.borrowObject(workerKey);
    WorkResponse response = worker.doWork(request);
    FullResponse fullResponse = new FullResponse(response, worker.flushStdErr(),
        worker.getExecRoot());
    workerPool.returnObject(workerKey, worker);
    return fullResponse;
  }

  public static class FullResponse {
    public final WorkResponse response;
    public final String errorString;
    public final Path outputPath;

    public FullResponse(WorkResponse response, String errorString, Path outputPath) {
      this.response = response;
      this.errorString = errorString;
      this.outputPath = outputPath;
    }
  }
}
