package build.buildfarm.worker.persistent;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;

import persistent.common.CtxAround;

public class RequestCtx implements CtxAround<WorkRequest> {

  public final WorkRequest request;

  public final WorkFilesContext filesContext;

  public final WorkerInputs workerInputs;

  public RequestCtx(WorkRequest request, WorkFilesContext ctx, WorkerInputs workFiles) {
    this.request = request;
    this.filesContext = ctx;
    this.workerInputs = workFiles;
  }

  @Override
  public WorkRequest get() {
    return request;
  }
}
