package build.buildfarm.common.gencache;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.SetMultimap;

import org.apache.commons.pool2.impl.GenericObjectPool;

public interface Gencache {

  abstract class Pool<T> implements Closeable {

    protected GenericObjectPool<T> internalPool;

    public abstract T getResource();
  }

  interface JedisCluster {

    Map<String, Pool<Jedis>> getClusterNodes();

    String get(final String key);

    String setex(final String key, final int seconds, final String value);

    Long del(final String key);

    Boolean exists(final String key);

    Set<String> smembers(final String key);
    Long sadd(final String key, final String... member);

    Set<String> hkeys(final String key);

    Map<String, String> hgetAll(final String key);

    Long hset(final String key, final String field, final String value);

    Long hsetnx(final String key, final String field, final String value);

    Boolean hexists(final String key, final String field);

    Long hdel(final String key, final String... field);

    JedisClusterPipeline pipelined();

    Long hlen(final String key);
//    import redis.clients.jedis.JedisCluster
  }
  interface JedisClusterPipeline {
    Long del(final String key);

    Long hdel(final String key, final String... field);

    void sync();
  }
  interface Jedis extends Closeable {
    void close();

    Client getClient();

    ScanResult<String> scan(String cursor, ScanParams params);

  }

  interface Client {

    String getHost();

  }

  public abstract class ScanParams {

    public static final String SCAN_POINTER_START = String.valueOf(0);

    public abstract ScanParams match(String query);

    public abstract ScanParams count(int amount);
  }

  interface ScanResult<T> {
    List<T> getResult();

    String getCursor();
  }

  interface ProvisionedRedisQueue {

    BalancedQueue<JedisCluster> queue();

    boolean isEligible(SetMultimap<String, String> properties);

    String explainEligibility(SetMultimap<String, String> properties);

  }

  interface NodeHashes<T> {

    List<String> getEvenlyDistributedHashesWithPrefix(T jedis, String prefix);
  }

  interface QueueFactory {

    QueueInterface getQueue(String queueType, String name);
  }
}
