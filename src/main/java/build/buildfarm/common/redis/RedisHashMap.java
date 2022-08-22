// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.common.redis;

import java.util.Map;
import java.util.Set;

/**
 * @class RedisHashMap
 * @brief A redis hashmap.
 * @details A redis hashmap is an implementation of a map data structure which internally uses redis
 *     to store and distribute the data. Its important to know that the lifetime of the map persists
 *     before and after the map data structure is created (since it exists in redis). Therefore, two
 *     redis maps with the same name, would in fact be the same underlying redis map.
 */
public class RedisHashMap {
  /**
   * @field name
   * @brief The unique name of the map.
   * @details The name is used by the redis cluster client to access the map data. If two maps had
   *     the same name, they would be instances of the same underlying redis map.
   */
  private final String name;

  /**
   * @brief Constructor.
   * @details Construct a named redis map with an established redis cluster.
   * @param name The global name of the map.
   */
  public RedisHashMap(String name) {
    this.name = name;
  }

  /**
   * @brief Set key to hold the string value. No TTL is available since implementation is redis
   *     hset.
   * @details If the key already exists, then the value is replaced.
   * @param jedis Jedis cluster client.
   * @param key The name of the key.
   * @param value The value for the key.
   * @return Whether a new key was inserted. If a key is overwritten with a new value, this would be
   *     false.
   */
  public boolean insert(JedisCluster jedis, String key, String value) {
    return jedis.hset(name, key, value) == 1;
  }

  /**
   * @brief Add key/value only if key doesn't exist.
   * @details If the key already exists, this operation has no effect.
   * @param jedis Jedis cluster client.
   * @param key The name of the key.
   * @param value The value for the key.
   * @return Whether a new key was inserted. If a key already exists, this would be false.
   */
  public boolean insertIfMissing(JedisCluster jedis, String key, String value) {
    return jedis.hsetnx(name, key, value) == 1;
  }

  /**
   * @brief Checks whether key exists
   * @details True if key exists. False if it does not.
   * @param jedis Jedis cluster client.
   * @param key The name of the key.
   * @return Whether the key exists or not in the map.
   */
  public boolean exists(JedisCluster jedis, String key) {
    return jedis.hexists(name, key);
  }

  /**
   * @brief Remove a key from the map.
   * @details Deletes the key/value pair.
   * @param jedis Jedis cluster client.
   * @param key The name of the key.
   * @return Whether the key was removed.
   */
  public boolean remove(JedisCluster jedis, String key) {
    return jedis.hdel(name, key) == 1;
  }

  /**
   * @brief Remove all given keys from the map.
   * @details Deletes the key/value pairs.
   * @param jedis Jedis cluster client.
   * @param key The names of the keys.
   */
  public void remove(JedisCluster jedis, Iterable<String> keys) {
    JedisClusterPipeline p = jedis.pipelined();
    for (String key : keys) {
      p.hdel(name, key);
    }
    p.sync();
  }

  /**
   * @brief Get the size of the map.
   * @details O(1).
   * @return The size of the map.
   * @note Suggested return identifier: size.
   */
  public long size(JedisCluster jedis) {
    return jedis.hlen(name);
  }

  /**
   * @brief Get all of the keys from the hashmap.
   * @details No order guarantee
   * @param jedis Jedis cluster client.
   * @return The redis hashmap keys represented as a set.
   */
  public Set<String> keys(JedisCluster jedis) {
    return jedis.hkeys(name);
  }

  /**
   * @brief Convert the redis hashmap to a java map.
   * @details This would not be efficient if the map is large.
   * @param jedis Jedis cluster client.
   * @return The redis hashmap represented as a java map.
   */
  public Map<String, String> asMap(JedisCluster jedis) {
    return jedis.hgetAll(name);
  }
}
