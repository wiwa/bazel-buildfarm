package persistent.bazel;

import java.io.IOException;
import java.nio.file.Path;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.KeyedWorker;
import persistent.ProcessWrapper;

public class PersistentWorker implements KeyedWorker<WorkerKey, WorkRequest, WorkResponse> {

  private final WorkerKey key;

  private final ProcessWrapper processWrapper;

  public PersistentWorker(WorkerKey key, Path errorFile, ImmutableList<String> initCmd) throws IOException {
    this.key = key;
    this.processWrapper = new ProcessWrapper(key.workDir, errorFile, initCmd);
  }

  @Override
  public WorkerKey getKey() {
    return this.key;
  }

  @Override
  public WorkResponse doWork(
      WorkRequest request
  ) {

    return null;
  }
}
