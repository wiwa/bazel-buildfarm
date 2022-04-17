package persistent.common;

/**
 * Manages worker lifetimes and acts as the mediator between executors and workers.
 * It also manages initi
 *
 * @param <K> worker key type
 * @param <I> request type
 * @param <O> work response type
 * @param <W> worker type
 */
public abstract class Coordinator<K, I, O, W extends KeyedWorker<K, I, O>> {

  protected final ObjectPool<K, W> workerPool;

  public Coordinator(ObjectPool<K, W> workerPool) {
    this.workerPool = workerPool;
  }

  public O runRequest(K workerKey, I request) {
    W worker = workerPool.obtain(workerKey);

    I contextualizedRequest = preWorkInit(request, worker);
    O workResponse = worker.doWork(contextualizedRequest);
    O responseAfterCLeanup = postWorkCleanup(workResponse, worker, contextualizedRequest);

    workerPool.release(worker);
    return responseAfterCLeanup;
  }

  public abstract I preWorkInit(I request, W worker);

  public abstract O postWorkCleanup(O response, W worker, I request);

  public static <K, I, O, W extends KeyedWorker<K, I, O>> SimpleCoordinator<K, I, O, W> simple(
      ObjectPool<K, W> workerPool
  ) {
    return new SimpleCoordinator<>(workerPool);
  }

  public static class SimpleCoordinator<K, I, O, W extends KeyedWorker<K, I, O>> extends Coordinator<K, I, O, W> {

    public SimpleCoordinator(ObjectPool<K, W> workerPool) {
      super(workerPool);
    }

    @Override
    public I preWorkInit(I request, W worker) {
      return request;
    }

    @Override
    public O postWorkCleanup(O response, W worker, I request) {
      return response;
    }
  }
}
