package persistent.bazel.client;

import persistent.common.CommonsPool;

/**
 * Specializes CommmonsPool for PersistentWorker
 */
public class CommonsWorkerPool extends CommonsPool<WorkerKey, PersistentWorker> {

  public CommonsWorkerPool(PersistentWorker.Supervisor supervisor, int maxPerKey) {
    super(supervisor, maxPerKey);
  }
}
