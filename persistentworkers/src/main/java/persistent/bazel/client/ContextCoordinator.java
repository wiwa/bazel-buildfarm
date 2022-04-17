package persistent.bazel.client;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.bazel.client.context.CtxAround;
import persistent.common.Coordinator;
import persistent.common.ObjectPool;

public class ContextCoordinator<I extends CtxAround<WorkRequest>, O extends CtxAround<WorkResponse>>
    extends Coordinator<WorkerKey, WorkRequest, WorkResponse, PersistentWorker, I, O> {

  public ContextCoordinator(
      ObjectPool<WorkerKey, PersistentWorker> workerPool
  ) {
    super(workerPool);
  }

  @Override
  public WorkRequest preWorkInit(I request, PersistentWorker worker) {
    return null;
  }

  @Override
  public O postWorkCleanup(
      WorkResponse response, PersistentWorker worker, I ctxRequest
  ) {
    return null;
  }
}
