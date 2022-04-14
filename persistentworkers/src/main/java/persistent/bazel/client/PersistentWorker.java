package persistent.bazel.client;

import java.io.IOException;
import java.nio.file.Path;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.common.KeyedWorker;
import persistent.common.processes.ProcessWrapper;
import persistent.bazel.processes.ProtoWorkerRW;

public class PersistentWorker implements KeyedWorker<WorkerKey, WorkRequest, WorkResponse> {

  private final WorkerKey key;

  private final ProtoWorkerRW workerRW;

  public PersistentWorker(WorkerKey key) throws IOException {
    this.key = key;
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableList<String> initCmd = builder
        .addAll(key.getCmd())
        .addAll(key.getArgs())
        .build();
    ProcessWrapper processWrapper = new ProcessWrapper(key.getExecRoot(), initCmd);
    this.workerRW = new ProtoWorkerRW(processWrapper);
  }

  @Override
  public WorkerKey getKey() {
    return this.key;
  }

  @Override
  public WorkResponse doWork(WorkRequest request) {
    try {
      workerRW.write(request);
      return workerRW.waitAndRead();
    } catch (IOException e) {
      System.out.println("IO Failing with : " + e.getMessage());
    } catch (Exception e) {
      System.out.println("Failing with : " + e.getMessage());
    }
    return null;
  }
}
