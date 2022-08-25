package build.buildfarm.common.gencache;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.SetMultimap;

import org.apache.commons.pool2.impl.GenericObjectPool;

import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

public interface Gencache {

  abstract class Pool<T> implements Closeable {

    protected GenericObjectPool<T> internalPool;

    public abstract T getResource();
  }

  // TODO we need to be careful about performance
  interface RedisDriver {

    Map<String, Pool<RedisClient>> getClusterNodes();

    ScanResult<String> scan(String var1, ScanParams var2);

    String get(final String key);

    String set(final String key, final String value);

    String setex(final String key, final int seconds, final String value);

    Long del(final String key);

    Boolean exists(final String key);

    Set<String> smembers(final String key);
    Long sadd(final String key, final String... member);

    Long llen(final String key);

    Long lpush(final String key, final String... string);

    List<String> lrange(final String key, final long start, final long stop);

    Long lrem(final String key, final long count, final String value);

    String brpoplpush(final String source, final String destination, final int timeout) throws InterruptedException;

    String rpoplpush(final String srckey, final String dstkey);

    Set<String> hkeys(final String key);

    Map<String, String> hgetAll(final String key);

    Long hset(final String key, final String field, final String value);

    Long hsetnx(final String key, final String field, final String value);

    Boolean hexists(final String key, final String field);

    Long hdel(final String key, final String... field);

    Long hlen(final String key);

    Long zadd(final String key, final double score, final String member);

    Long zrem(final String key, final String... members);

    Long zcard(final String key);

    Set<String> zrange(final String key, final long start, final long stop);

    Object eval(final String script, final List<String> keys, final List<String> args);

    Long publish(final String channel, final String message);

    RedisDriverPipeline pipelined();
  }
  interface RedisDriverPipeline {
    Long del(final String key);

    Long hdel(final String key, final String... field);

    void sync();
  }
  interface RedisClient extends Closeable {

    void close();

    Client getClient();

    ScanResult<String> scan(String cursor, ScanParams params);

  }

  interface Client {

    String getHost();

  }

  abstract class ScanParams {

    public static final String SCAN_POINTER_START = String.valueOf(0);

    public abstract ScanParams match(String query);

    public abstract ScanParams count(int amount);
  }

  interface ScanResult<T> {
    List<T> getResult();

    String getCursor();
  }

  interface ProvisionedRedisQueue {

    BalancedQueue queue();

    boolean isEligible(SetMultimap<String, String> properties);

    String explainEligibility(SetMultimap<String, String> properties);

  }

  interface NodeHashes {

    List<String> getEvenlyDistributedHashesWithPrefix(RedisDriver redis, String prefix);
  }

  interface QueueFactory {

    QueueInterface getQueue(String queueType, String name);
  }
}
