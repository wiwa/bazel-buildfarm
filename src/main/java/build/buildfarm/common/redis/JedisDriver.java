package build.buildfarm.common.redis;

import java.util.List;
import java.util.Map;
import java.util.Set;

import build.buildfarm.common.gencache.Gencache;
import build.buildfarm.common.gencache.Gencache.RedisDriver;
import build.buildfarm.common.gencache.Gencache.RedisDriverPipeline;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisClusterPipeline;

public class JedisDriver implements RedisDriver {

  public class JedisDriverPipeline implements RedisDriverPipeline {

    private final JedisClusterPipeline pipeline;

    public JedisDriverPipeline(JedisClusterPipeline pipeline) {
      this.pipeline = pipeline;
    }

    @Override
    public Long del(String key) {
//      return pipeline.del(key);
      // TODO later
    }

    @Override
    public Long hdel(String key, String... field) {
      // TODO later
    }

    @Override
    public void sync() {
      // TODO later
    }
  }

  private final JedisCluster jedis;

  public JedisDriver(JedisCluster jedis) {
    this.jedis = jedis;
  }


  @Override
  public Map<String, Gencache.Pool<Gencache.RedisClient>> getClusterNodes() {
    // TODO what do we actually need from the nodes? We don't need the pools.
  }

  @Override
  public String get(String key) {
    return jedis.get(key);
  }

  @Override
  public String setex(String key, int seconds, String value) {
    return jedis.setex(key, seconds, value);
  }

  @Override
  public Long del(String key) {
    return jedis.del(key);
  }

  @Override
  public Boolean exists(String key) {
    return jedis.exists(key);
  }

  @Override
  public Set<String> smembers(String key) {
    return jedis.smembers(key);
  }

  @Override
  public Long sadd(String key, String... member) {
    return jedis.sadd(key, member);
  }

  @Override
  public Long llen(String key) {
    return jedis.llen(key);
  }

  @Override
  public Long lpush(String key, String... string) {
    return jedis.lpush(key, string);
  }

  @Override
  public List<String> lrange(String key, long start, long stop) {
    return jedis.lrange(key, start, stop);
  }

  @Override
  public Long lrem(String key, long count, String value) {
    return jedis.lrem(key, count, value);
  }

  @Override
  public String brpoplpush(String source, String destination, int timeout) throws InterruptedException {
    return jedis.brpoplpush(source, destination, timeout);
  }

  @Override
  public String rpoplpush(String srckey, String dstkey) {
    return jedis.rpoplpush(srckey, dstkey);
  }

  @Override
  public Set<String> hkeys(String key) {
    return jedis.hkeys(key);
  }

  @Override
  public Map<String, String> hgetAll(String key) {
    return jedis.hgetAll(key);
  }

  @Override
  public Long hset(String key, String field, String value) {
    return jedis.hset(key, field, value);
  }

  @Override
  public Long hsetnx(String key, String field, String value) {
    return jedis.hsetnx(key, field, value);
  }

  @Override
  public Boolean hexists(String key, String field) {
    return jedis.hexists(key, field);
  }

  @Override
  public Long hdel(String key, String... field) {
    return jedis.hdel(key, field);
  }

  @Override
  public Long hlen(String key) {
    return jedis.hlen(key);
  }

  @Override
  public Long zadd(String key, double score, String member) {
    return jedis.zadd(key, score, member);
  }

  @Override
  public Long zrem(String key, String... members) {
    return jedis.zrem(key, members);
  }

  @Override
  public Long zcard(String key) {
    return jedis.zcard(key);
  }

  @Override
  public Set<String> zrange(String key, long start, long stop) {
    return jedis.zrange(key, start, stop);
  }

  @Override
  public Object eval(String script, List<String> keys, List<String> args) {
    return jedis.eval(script, keys, args);
  }

  @Override
  public RedisDriverPipeline pipelined() {
    return new JedisDriverPipeline(jedis.pipelined());
  }
}
