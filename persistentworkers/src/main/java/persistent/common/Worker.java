package persistent.common;

public interface Worker<I, O> {

  O doWork(I request);

  default void destroy() {}
}
