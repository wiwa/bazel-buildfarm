package persistent;

public interface PersistentWorker<K, I, O> {

  K getKey();

  O doWork(I request);
}
