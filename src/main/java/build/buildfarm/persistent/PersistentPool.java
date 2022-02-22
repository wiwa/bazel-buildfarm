package build.buildfarm.persistent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;

import org.apache.commons.pool2.impl.GenericKeyedObjectPool;

public class PersistentPool {

  /** Unless otherwise specified, the max number of workers per WorkerKey. */
  private static final int DEFAULT_MAX_WORKERS = 4;

  private final WorkerPoolConfig workerPoolConfig;


  /** Map of singleplex PersistentWorker pools, one per mnemonic. */
  private final ImmutableMap<String, SimpleWorkerPool> workerPools;


  public PersistentPool(WorkerPoolConfig workerPoolConfig) {
    this.workerPoolConfig = workerPoolConfig;


    Map<String, Integer> config = createConfigFromOptions(workerPoolConfig.getWorkerMaxInstances());

    workerPools =
        createWorkerPools(workerPoolConfig.getWorkerFactory(), config, DEFAULT_MAX_WORKERS);
  }

  public WorkerPoolConfig getWorkerPoolConfig() {
    return workerPoolConfig;
  }

  /**
   * Creates a configuration for a PersistentWorker pool from the options given. If the same mnemonic occurs
   * more than once in the options, the last value passed wins.
   */
  private static ImmutableMap<String, Integer> createConfigFromOptions(
      List<Entry<String, Integer>> options) {
    LinkedHashMap<String, Integer> newConfigBuilder = new LinkedHashMap<>();
    for (Entry<String, Integer> entry : options) {
      newConfigBuilder.put(entry.getKey(), entry.getValue());
    }

    if (!newConfigBuilder.containsKey("")) {
      // Empty string gives the number of workers for any type of PersistentWorker not explicitly specified.
      // If no value is given, use the default, 2.
      newConfigBuilder.put("", 2);
    }

    return ImmutableMap.copyOf(newConfigBuilder);
  }

  private static ImmutableMap<String, SimpleWorkerPool> createWorkerPools(
      WorkerFactory factory, Map<String, Integer> config, int defaultMaxWorkers) {
    ImmutableMap.Builder<String, SimpleWorkerPool> workerPoolsBuilder = ImmutableMap.builder();
    config.forEach(
        (key, value) -> workerPoolsBuilder.put(key, new SimpleWorkerPool(factory, value)));
    if (!config.containsKey("")) {
      workerPoolsBuilder.put("", new SimpleWorkerPool(factory, defaultMaxWorkers));
    }
    return workerPoolsBuilder.build();
  }

  private SimpleWorkerPool getPool(WorkerKey key) {
    return workerPools.getOrDefault(key.getMnemonic(), workerPools.get(""));
  }

  /**
   * Gets a worker. May block indefinitely if too many high-priority workers are in use and the
   * requested PersistentWorker is not high priority.
   *
   * @param key PersistentWorker key
   * @return a worker
   */
  public PersistentWorker borrowObject(WorkerKey key) throws IOException, InterruptedException {
    PersistentWorker result;
    try {
      result = getPool(key).borrowObject(key);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class, InterruptedException.class);
      throw new RuntimeException("unexpected", t);
    }

    return result;
  }

  public void returnObject(WorkerKey key, PersistentWorker obj) {
    getPool(key).returnObject(key, obj);
  }

  public void invalidateObject(WorkerKey key, PersistentWorker obj) throws IOException, InterruptedException {
    try {
      getPool(key).invalidateObject(key, obj);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class, InterruptedException.class);
      throw new RuntimeException("unexpected", t);
    }
  }

  /**
   * Closes all the PersistentWorker pools, destroying the workers in the process. This waits for any
   * currently-ongoing work to finish.
   */
  public void close() {
    workerPools.values().forEach(GenericKeyedObjectPool::close);
  }

  static class WorkerPoolConfig {
    private final WorkerFactory workerFactory;
    private final List<Entry<String, Integer>> workerMaxInstances;

    WorkerPoolConfig(
        WorkerFactory workerFactory,
        List<Entry<String, Integer>> workerMaxInstances
    ) {
      this.workerFactory = workerFactory;
      this.workerMaxInstances = workerMaxInstances;
    }

    public WorkerFactory getWorkerFactory() {
      return workerFactory;
    }

    public List<Entry<String, Integer>> getWorkerMaxInstances() {
      return workerMaxInstances;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof WorkerPoolConfig)) {
        return false;
      }
      WorkerPoolConfig that = (WorkerPoolConfig) o;
      return workerFactory.equals(that.workerFactory)
          && workerMaxInstances.equals(that.workerMaxInstances);
    }

    @Override
    public int hashCode() {
      return Objects.hash(workerFactory, workerMaxInstances);
    }
  }

}
