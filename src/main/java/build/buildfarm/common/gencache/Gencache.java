package build.buildfarm.common.gencache;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.SetMultimap;

import org.apache.commons.pool2.impl.GenericObjectPool;

import build.buildfarm.common.redis.BalancedRedisQueue;

public interface Gencache {

  abstract class Pool<T> implements Closeable {

    protected GenericObjectPool<T> internalPool;

    public abstract T getResource();
  }

  interface JedisCluster {

    Map<String, Pool<Jedis>> getClusterNodes();

    Set<String> hkeys(final String key);

    Set<String> smembers(final String key);

    Long del(final String key);

    Long sadd(final String key, final String... member);

//    import redis.clients.jedis.JedisCluster
  }
  interface Jedis extends Closeable {
    void close();

    Client getClient();

    ScanResult<String> scan(String cursor, ScanParams params);

  }

  interface Client {

    String getHost();

  }

  interface ScanParams {

    ScanParams match(String query);

    ScanParams count(int amount);
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
}
