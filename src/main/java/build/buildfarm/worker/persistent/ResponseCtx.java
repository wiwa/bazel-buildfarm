package build.buildfarm.worker.persistent;

import java.nio.file.Path;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.common.CtxAround;

public class ResponseCtx implements CtxAround<WorkResponse> {

  public final WorkResponse response;

  public final String errorString;

  public final Path outputPath;

  public ResponseCtx(WorkResponse response, String errorString, Path outputPath) {
    this.response = response;
    this.errorString = errorString;
    this.outputPath = outputPath;
  }

  @Override
  public WorkResponse get() {
    return response;
  }
}
