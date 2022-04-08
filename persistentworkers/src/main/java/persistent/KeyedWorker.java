package persistent;

public interface KeyedWorker<K, I, O> {

  K getKey();

  O doWork(I request);
}
