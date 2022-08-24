package build.buildfarm.common.gencache;

import build.buildfarm.common.StringVisitor;
import build.buildfarm.common.gencache.Gencache.RedisDriver;
import build.buildfarm.v1test.QueueStatus;

// T = Something like JedisCluster
public interface BalancedQueue {

  /**
   * @param val The value to push onto the queue.
   * @brief Push a value onto the queue.
   * @details Adds the value into one of the internal backend redis queues.
   */
  void push(RedisDriver jedis, String val);

  /**
   * @param val The value to push onto the queue.
   * @brief Push a value onto the queue.
   * @details Adds the value into one of the internal backend redis queues.
   */
  void push(RedisDriver jedis, String val, double priority);

  /**
   * @param val The value to remove.
   * @return Whether or not the value was removed.
   * @brief Remove element from dequeue.
   * @details Removes an element from the dequeue and specifies whether it was removed.
   * @note Suggested return identifier: wasRemoved.
   */
  boolean removeFromDequeue(RedisDriver jedis, String val);

  /**
   * @return The value of the transfered element. null if the thread was interrupted.
   * @brief Pop element into internal dequeue and return value.
   * @details This pops the element from one queue atomically into an internal list called the
   * dequeue. It will perform an exponential backoff. Null is returned if the overall backoff
   * times out.
   * @note Suggested return identifier: val.
   */
  String dequeue(RedisDriver jedis) throws InterruptedException;

  /**
   * @return The queue that the balanced queue intends to pop from next.
   * @brief Get the current pop queue.
   * @details Get the queue that the balanced queue intends to pop from next.
   * @note Suggested return identifier: currentPopQueue.
   */
  QueueInterface getCurrentPopQueue();

  /**
   * @return The index of the queue that the balanced queue intends to pop from next.
   * @brief Get the current pop queue index.
   * @details Get the index of the queue that the balanced queue intends to pop from next.
   * @note Suggested return identifier: currentPopQueueIndex.
   */
  int getCurrentPopQueueIndex();

  /**
   * @param index The index to the internal queue (must be in bounds).
   * @return The internal queue found at that index.
   * @brief Get queue at index.
   * @details Get the internal queue at the specified index.
   * @note Suggested return identifier: internalQueue.
   */
  QueueInterface getInternalQueue(int index);

  /**
   * @return The name of the queue.
   * @brief Get dequeue name.
   * @details Get the name of the internal dequeue used by the queue. since each internal queue has
   * their own dequeue, this name is generic without the hashtag.
   * @note Suggested return identifier: name.
   */
  String getDequeueName();

  /**
   * @return The base name of the queue.
   * @brief Get name.
   * @details Get the name of the queue. this is the redis key used as base name for internal
   * queues.
   * @note Suggested return identifier: name.
   */
  String getName();

  /**
   * @return The current length of the queue.
   * @brief Get size.
   * @details Checks the current length of the queue.
   * @note Suggested return identifier: length.
   */
  long size(RedisDriver jedis);

  /**
   * @return The current status of the queue.
   * @brief Get status information about the queue.
   * @details Helpful for understanding the current load on the queue and how elements are balanced.
   * @note Suggested return identifier: status.
   */
  QueueStatus status(RedisDriver jedis);

  /**
   * @param visitor A visitor for each visited element in the queue.
   * @brief Visit each element in the queue.
   * @details Enacts a visitor over each element in the queue.
   */
  void visit(RedisDriver jedis, StringVisitor visitor);

  /**
   * @param visitor A visitor for each visited element in the queue.
   * @brief Visit each element in the dequeue.
   * @details Enacts a visitor over each element in the dequeue.
   */
  void visitDequeue(RedisDriver jedis, StringVisitor visitor);

  /**
   * @return Whether or not the queues values are evenly distributed by internal queues.
   * @brief Check that the internal queues have evenly distributed the values.
   * @details We are checking that the size of all the internal queues are the same. This means, the
   * balanced queue will be evenly distributed on every n elements pushed, where n is the number
   * of internal queues.
   * @note Suggested return identifier: isEvenlyDistributed.
   */
  boolean isEvenlyDistributed(RedisDriver jedis);

  /**
   * @param jedis Jedis cluster client.
   * @return Whether are not a new element can be added to the queue based on its current size.
   * @brief Whether or not more elements can be added to the queue based on the queue's configured
   * max size.
   * @details Compares the size of the queue to configured max size. Queues may be configured to be
   * infinite in size.
   */
  boolean canQueue(RedisDriver jedis);
}
