package build.buildfarm.persistent;

import java.io.IOException;
import java.util.Objects;

import com.google.common.base.Throwables;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;

public class SimpleWorkerPool  extends GenericKeyedObjectPool<WorkerKey, PersistentWorker>{

  public SimpleWorkerPool(WorkerFactory factory, int max) {
    super(factory, makeConfig(max));
  }

  static SimpleWorkerPoolConfig makeConfig(int max) {
    SimpleWorkerPoolConfig config = new SimpleWorkerPoolConfig();

    // It's better to re-use a worker as often as possible and keep it hot, in order to profit
    // from JIT optimizations as much as possible.
    config.setLifo(true);

    // Keep a fixed number of workers running per key.
    config.setMaxIdlePerKey(max);
    config.setMaxTotalPerKey(max);
    config.setMinIdlePerKey(max);

    // Don't limit the total number of worker processes, as otherwise the pool might be full of
    // workers for one WorkerKey and can't accommodate a worker for another WorkerKey.
    config.setMaxTotal(-1);

    // Wait for a worker to become ready when a thread needs one.
    config.setBlockWhenExhausted(true);

    // Always test the liveliness of worker processes.
    config.setTestOnBorrow(true);
    config.setTestOnCreate(true);
    config.setTestOnReturn(true);

    // No eviction of idle workers.
    config.setTimeBetweenEvictionRunsMillis(-1);

    return config;
  }

  @Override
  public PersistentWorker borrowObject(WorkerKey key) throws IOException, InterruptedException {
    try {
      return super.borrowObject(key);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class, InterruptedException.class);
      throw new RuntimeException("unexpected", t);
    }
  }

  @Override
  public void invalidateObject(WorkerKey key, PersistentWorker obj) throws IOException, InterruptedException {
    try {
      super.invalidateObject(key, obj);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class, InterruptedException.class);
      throw new RuntimeException("unexpected", t);
    }
  }

  /**
   * Our own configuration class for the {@code SimpleWorkerPool} that correctly implements {@code
   * equals()} and {@code hashCode()}.
   */
  static final class SimpleWorkerPoolConfig extends GenericKeyedObjectPoolConfig<PersistentWorker> {
    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SimpleWorkerPoolConfig that = (SimpleWorkerPoolConfig) o;
      return getBlockWhenExhausted() == that.getBlockWhenExhausted()
          && getFairness() == that.getFairness()
          && getJmxEnabled() == that.getJmxEnabled()
          && getLifo() == that.getLifo()
          && getMaxWaitMillis() == that.getMaxWaitMillis()
          && getMinEvictableIdleTimeMillis() == that.getMinEvictableIdleTimeMillis()
          && getNumTestsPerEvictionRun() == that.getNumTestsPerEvictionRun()
          && getSoftMinEvictableIdleTimeMillis() == that.getSoftMinEvictableIdleTimeMillis()
          && getTestOnBorrow() == that.getTestOnBorrow()
          && getTestOnCreate() == that.getTestOnCreate()
          && getTestOnReturn() == that.getTestOnReturn()
          && getTestWhileIdle() == that.getTestWhileIdle()
          && getTimeBetweenEvictionRunsMillis() == that.getTimeBetweenEvictionRunsMillis()
          && getMaxIdlePerKey() == that.getMaxIdlePerKey()
          && getMaxTotal() == that.getMaxTotal()
          && getMaxTotalPerKey() == that.getMaxTotalPerKey()
          && getMinIdlePerKey() == that.getMinIdlePerKey()
          && Objects.equals(getEvictionPolicyClassName(), that.getEvictionPolicyClassName())
          && Objects.equals(getJmxNameBase(), that.getJmxNameBase())
          && Objects.equals(getJmxNamePrefix(), that.getJmxNamePrefix());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          getBlockWhenExhausted(),
          getFairness(),
          getJmxEnabled(),
          getLifo(),
          getMaxWaitMillis(),
          getMinEvictableIdleTimeMillis(),
          getNumTestsPerEvictionRun(),
          getSoftMinEvictableIdleTimeMillis(),
          getTestOnBorrow(),
          getTestOnCreate(),
          getTestOnReturn(),
          getTestWhileIdle(),
          getTimeBetweenEvictionRunsMillis(),
          getMaxIdlePerKey(),
          getMaxTotal(),
          getMaxTotalPerKey(),
          getMinIdlePerKey(),
          getEvictionPolicyClassName(),
          getJmxNameBase(),
          getJmxNamePrefix());
    }
  }
}
