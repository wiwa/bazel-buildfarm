package persistent.common;

/**
 * Manages persistent worker lifetimes and
 * acts as the mediator between executors and workers
 *
 * @param <K> worker key type
 * @param <I> request type
 * @param <O> work response type
 */
public class PersistentCoordinator<K, I, O> {

  private final ObjectPool<K, KeyedWorker<K, I, O>> workerPool;

  public PersistentCoordinator(ObjectPool<K, KeyedWorker<K, I, O>> workerPool) {
    this.workerPool = workerPool;
  }

  public O runRequest(K workerKey, I request) {
    return workerPool.obtain(workerKey).doWork(request);
  }
}
