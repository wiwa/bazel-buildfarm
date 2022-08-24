package build.buildfarm.common.gencache;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import build.buildfarm.common.gencache.Gencache.RedisDriver;
import io.grpc.Status;

public abstract class RedisClient implements Closeable {

  protected final String MISCONF_RESPONSE = "MISCONF";

  protected final RedisDriver redis;

  public RedisClient(RedisDriver redis) {
    this.redis = redis;
  }

  public static class RedisException extends RuntimeException {

    public RedisException(String message) {
      super(message);
    }

    public RedisException(Throwable e) {
      super(e);
    }

    public RedisException(String message, Throwable cause) {
      super(message, cause);
    }

  }

  @FunctionalInterface
  public static interface RedisContext<T> {
    T run(RedisDriver redis) throws RedisException;
  }

  @FunctionalInterface
  public static interface RedisInterruptibleContext<T> {
    T run(RedisDriver redis) throws InterruptedException, RedisException;
  }

  private boolean closed = false;

  @Override
  public synchronized void close() {
    closed = true;
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  protected synchronized void throwIfClosed() throws IOException {
    if (closed) {
      throw new IOException(
          Status.UNAVAILABLE.withDescription("client is closed").asRuntimeException());
    }
  }

  public void run(Consumer<RedisDriver> withRedis) throws IOException {
    call((RedisContext<Void>)
        redis -> {
          withRedis.accept(redis);
          return null;
        });
  }

  public <T> T blockingCall(RedisInterruptibleContext<T> withRedis)
      throws IOException, InterruptedException {
    AtomicReference<InterruptedException> interruption = new AtomicReference<>(null);
    T result =
        call(
            redis -> {
              try {
                return withRedis.run(redis);
              } catch (InterruptedException e) {
                interruption.set(e);
                return null;
              }
            });
    InterruptedException e = interruption.get();
    if (e != null) {
      throw e;
    }
    return result;
  }

  @SuppressWarnings("ConstantConditions")
  public abstract <T> T call(RedisContext<T> withRedis) throws IOException;
}
