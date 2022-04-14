package persistent.bazel.client;

import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;

import persistent.common.CommonsPool;

public class CommonsWorkerPool extends CommonsPool<WorkerKey, PersistentWorker> {

  public CommonsWorkerPool(
      BaseKeyedPooledObjectFactory<WorkerKey, PersistentWorker> factory,
      int maxPerKey
  ) {
    super(factory, maxPerKey);
  }

  public CommonsWorkerPool(int maxPerkey) {
    this(PersistentWorker.Supervisor.get(), maxPerkey);
  }
}
