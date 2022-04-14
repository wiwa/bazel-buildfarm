package persistent.bazel.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.UUID;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.common.KeyedWorker;
import persistent.common.MapPool;
import persistent.common.ObjectPool;
import persistent.common.PersistentCoordinator;

public class ProtoWorkerCoordinator extends PersistentCoordinator<WorkerKey, WorkRequest, WorkResponse> {

  ProtoWorkerCoordinator(
      ObjectPool<WorkerKey, KeyedWorker<WorkerKey, WorkRequest, WorkResponse>> workerPool
  ) {
    super(workerPool);
  }

  public static ProtoWorkerCoordinator simpleMapPool() {

    return new ProtoWorkerCoordinator(MapPool.ofKeyedWorker(ProtoWorkerCoordinator::makeWorker));
  }

  private static PersistentWorker makeWorker(WorkerKey key) {
    try {
      return new PersistentWorker(key);
    } catch (IOException e) {
      System.err.println("Failed to make Persistent Worker:\n" + e);
      return null;
    }
  }
}
