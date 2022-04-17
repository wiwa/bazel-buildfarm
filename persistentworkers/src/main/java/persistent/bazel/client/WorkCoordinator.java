package persistent.bazel.client;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.common.CtxAround;
import persistent.common.Coordinator;
import persistent.common.ObjectPool;

/**
 * Fills in the type parameters so that it will be specifically for PersistentWorker
 */
public abstract class WorkCoordinator<I extends CtxAround<WorkRequest>, O extends CtxAround<WorkResponse>>
    extends Coordinator<WorkerKey, WorkRequest, WorkResponse, PersistentWorker, I, O> {

  public WorkCoordinator(ObjectPool<WorkerKey, PersistentWorker> workerPool) {
    super(workerPool);
  }
}
