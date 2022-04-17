package persistent.common;

public interface KeyedWorker<K, I, O> {

  K getKey();

  O doWork(I request);

  default void destroy() {}
}
