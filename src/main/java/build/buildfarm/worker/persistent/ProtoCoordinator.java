package build.buildfarm.worker.persistent;

import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkCoordinator;
import persistent.bazel.client.WorkerKey;
import persistent.common.CommonsPool;

public class ProtoCoordinator extends WorkCoordinator<RequestCtx, ResponseCtx> {

  public ProtoCoordinator(CommonsPool<WorkerKey, PersistentWorker> workerPool) {
    super(workerPool);
  }

  @Override
  public WorkerProtocol.WorkRequest preWorkInit(
      RequestCtx request, PersistentWorker worker
  ) {
    return null;
  }

  @Override
  public ResponseCtx postWorkCleanup(
      WorkResponse response, PersistentWorker worker, RequestCtx request
  ) {
    return null;
  }
}
