package build.buildfarm.worker.persistent;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;

import persistent.common.CtxAround;

public class RequestCtx implements CtxAround<WorkRequest> {

  public final WorkRequest request;

  public final WorkFilesContext filesContext;

  public final ParsedWorkFiles workFiles;

  public RequestCtx(WorkRequest request, WorkFilesContext ctx, ParsedWorkFiles workFiles) {
    this.request = request;
    this.filesContext = ctx;
    this.workFiles = workFiles;
  }

  @Override
  public WorkRequest get() {
    return request;
  }
}
