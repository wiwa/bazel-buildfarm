package persistent.bazel.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.common.KeyedWorker;
import persistent.common.MapPool;
import persistent.common.ObjectPool;
import persistent.common.PersistentCoordinator;

public class ProtoWorkerCoordinator extends PersistentCoordinator<WorkerKey, WorkRequest, WorkResponse> {

  private final HashMap<WorkerKey,Path> errLogs;

  ProtoWorkerCoordinator(
      ObjectPool<WorkerKey, KeyedWorker<WorkerKey, WorkRequest, WorkResponse>> workerPool
  ) {
    super(workerPool);
    this.errLogs = new HashMap<>();
  }

  public Path getLogsFor(WorkerKey key) {
    return errLogs.get(key);
  }

  public static ProtoWorkerCoordinator simpleMapPool() {
    Path logDir = Paths.get("./ProtoWorkerCoordinator-logs");

    return simpleMapPool(logDir);
  }

  public static ProtoWorkerCoordinator simpleMapPool(Path logDir) {

    return new ProtoWorkerCoordinator(MapPool.ofKeyedWorker(k -> makeWorker(k, logDir)));
  }

  private static PersistentWorker makeWorker(WorkerKey key, Path logDir) {
    Path errorFile = logDir.resolve(key.hashCode() + ".stderr");

    try {
      return new PersistentWorker(key, errorFile);
    } catch (IOException e) {
      System.err.println(e.getMessage());
      try {
        Files.write(errorFile, ("Failed to make Persistent Worker:\n" + e).getBytes());
      } catch (IOException x) {
        System.err.println("Failed to write error to file: " + x);
      }
      return null;
    }
  }
}
