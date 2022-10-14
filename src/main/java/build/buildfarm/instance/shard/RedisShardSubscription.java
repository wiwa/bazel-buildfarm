// Copyright 2018 The Bazel Authors. All rights reserved.
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

import build.buildfarm.common.function.InterruptingRunnable;
import build.buildfarm.common.redis.RedisClient;
import io.grpc.Status;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPubSub;

class RedisShardSubscription implements Runnable {
  private static final Logger logger = Logger.getLogger(RedisShardSubscription.class.getName());

  private final JedisPubSub subscriber;
  private final InterruptingRunnable onUnsubscribe;
  private final Consumer<JedisCluster> onReset;
  private final Supplier<List<String>> subscriptions;
  private final RedisClient client;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  RedisShardSubscription(
      JedisPubSub subscriber,
      InterruptingRunnable onUnsubscribe,
      Consumer<JedisCluster> onReset,
      Supplier<List<String>> subscriptions,
      RedisClient client) {
    this.subscriber = subscriber;
    this.onUnsubscribe = onUnsubscribe;
    this.onReset = onReset;
    this.subscriptions = subscriptions;
    this.client = client;
  }

  public JedisPubSub getSubscriber() {
    return subscriber;
  }

  private void subscribe(JedisCluster jedis, boolean isReset) {
    if (isReset) {
      onReset.accept(jedis);
    }
    jedis.subscribe(subscriber, subscriptions.get().toArray(new String[0]));
  }

  private void iterate(boolean isReset) throws IOException {
    try {
      client.run(jedis -> subscribe(jedis, isReset));
    } catch (IOException e) {
      Status status = Status.fromThrowable(e);
      switch (status.getCode()) {
        case DEADLINE_EXCEEDED:
        case UNAVAILABLE:
          logger.log(Level.WARNING, "failed to subscribe", e);
          /* ignore */
          break;
        default:
          throw e;
      }
    }
  }

  private void mainLoop() throws IOException {
    boolean first = true;
    while (!stopped.get()) {
      if (!first) {
        logger.log(Level.SEVERE, "unexpected subscribe return, reconnecting...");
      }
      iterate(!first);
      first = false;
    }
  }

  public void stop() {
    if (stopped.compareAndSet(false, true)) {
      subscriber.unsubscribe();
    }
  }

  @Override
  public void run() {
    try {
      mainLoop();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "RedisShardSubscription: Calling onUnsubscribe...", e);
      try {
        onUnsubscribe.runInterruptibly();
      } catch (InterruptedException intEx) {
        Thread.currentThread().interrupt();
      }
    } finally {
      stopped.set(true);
    }
  }
}
