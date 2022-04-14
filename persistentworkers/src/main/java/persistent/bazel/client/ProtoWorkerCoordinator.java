package persistent.bazel.client;

import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;

public class ProtoWorkerCoordinator {

  private final GenericKeyedObjectPool<WorkerKey, PersistentWorker> workerPool;

  ProtoWorkerCoordinator(GenericKeyedObjectPool<WorkerKey, PersistentWorker> workerPool) {
    this.workerPool = workerPool;
  }

  public static ProtoWorkerCoordinator ofCommonsPool() {
    return new ProtoWorkerCoordinator(new CommonsWorkerPool(4));
  }

  public WorkResponse runRequest(WorkerKey workerKey, WorkerProtocol.WorkRequest request) throws Exception {
    PersistentWorker worker = workerPool.borrowObject(workerKey);
    WorkResponse response = worker.doWork(request);
    workerPool.returnObject(workerKey, worker);
    return response;
  }
}
