// Copyright 2021 The Bazel Authors. All rights reserved.
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

package build.buildfarm.instance.shard;

import build.bazel.remote.execution.v2.Digest;
import build.buildfarm.common.redis.RedisClient;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface CasWorkerMap {
  /**
   * @brief Adjust blob mappings based on worker changes.
   * @details Adjustments are made based on added and removed workers. Expirations are refreshed.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigest The blob digest to adjust worker information from.
   * @param addWorkers Workers to add.
   * @param removeWorkers Workers to remove.
   */
  void adjust(
      RedisClient client, Digest blobDigest, Set<String> addWorkers, Set<String> removeWorkers)
      throws IOException;

  /**
   * @brief Update the blob entry for the worker.
   * @details This may add a new key if the blob did not previously exist, or it will adjust the
   *     worker values based on the worker name. The expiration time is always refreshed.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigest The blob digest to adjust worker information from.
   * @param workerName The worker to add for looking up the blob.
   */
  void add(RedisClient client, Digest blobDigest, String workerName) throws IOException;

  /**
   * @brief Update multiple blob entries for a worker.
   * @details This may add a new key if the blob did not previously exist, or it will adjust the
   *     worker values based on the worker name. The expiration time is always refreshed.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigests The blob digests to adjust worker information from.
   * @param workerName The worker to add for looking up the blobs.
   */
  void addAll(RedisClient client, Iterable<Digest> blobDigests, String workerName)
      throws IOException;

  /**
   * @brief Remove worker value from blob key.
   * @details If the blob is already missing, or the worker doesn't exist, this will have no effect.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigest The blob digest to remove the worker from.
   * @param workerName The worker name to remove.
   */
  void remove(RedisClient client, Digest blobDigest, String workerName) throws IOException;

  /**
   * @brief Remove worker value from all blob keys.
   * @details If the blob is already missing, or the worker doesn't exist, this will be no effect on
   *     the key.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigests The blob digests to remove the worker from.
   * @param workerName The worker name to remove.
   */
  void removeAll(RedisClient client, Iterable<Digest> blobDigests, String workerName)
      throws IOException;

  /**
   * @brief Get a random worker for where the blob resides.
   * @details Picking a worker may done differently in the future.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigest The blob digest to lookup a worker for.
   * @return A worker for where the blob is.
   * @note Suggested return identifier: workerName.
   */
  String getAny(RedisClient client, Digest blobDigest) throws IOException;

  /**
   * @brief Get all of the workers for where a blob resides.
   * @details Set is empty if the locaion of the blob is unknown.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigest The blob digest to lookup a worker for.
   * @return All the workers where the blob is expected to be.
   * @note Suggested return identifier: workerNames.
   */
  Set<String> get(RedisClient client, Digest blobDigest) throws IOException;

  /**
   * @brief Get insert time for the digest.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigest The blob digest to lookup for insert time.
   * @return insert time of the digest.
   */
  long insertTime(RedisClient client, Digest blobDigest) throws IOException;

  /**
   * @brief Get all of the key values as a map from the digests given.
   * @details If there are no workers for the digest, the key is left out of the returned map.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigests The blob digests to get the key/values for.
   * @return The key/value map for digests to workers.
   * @note Suggested return identifier: casWorkerMap.
   */
  Map<Digest, Set<String>> getMap(RedisClient client, Iterable<Digest> blobDigests)
      throws IOException;

  /**
   * @brief Get the size of the map.
   * @details Returns the number of key-value pairs in this multimap.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @return The size of the map.
   * @note Suggested return identifier: mapSize.
   */
  int size(RedisClient client) throws IOException;

  /**
   * @brief Set the expiry duration for the digests.
   * @param client Client used for interacting with redis when not using cacheMap.
   * @param blobDigests The blob digests to set new the expiry duration.
   */
  void setExpire(RedisClient client, Iterable<Digest> blobDigests) throws IOException;
}
