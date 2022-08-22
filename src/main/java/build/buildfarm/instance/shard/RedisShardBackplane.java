// Copyright 2017 The Bazel Authors. All rights reserved.
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

import java.io.IOException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.naming.ConfigurationException;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.longrunning.Operation;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import com.google.rpc.PreconditionFailure;
import com.google.rpc.Status;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecutionStage;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.buildfarm.backplane.Backplane;
import build.buildfarm.common.CasIndexResults;
import build.buildfarm.common.CasIndexSettings;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.DigestUtil.ActionKey;
import build.buildfarm.common.StringVisitor;
import build.buildfarm.common.Time;
import build.buildfarm.common.Watcher;
import build.buildfarm.common.WorkerIndexer;
import build.buildfarm.common.function.InterruptingRunnable;
import build.buildfarm.common.redis.RedisClient;
import build.buildfarm.instance.Instance;
import build.buildfarm.instance.shard.RedisShardSubscriber.TimedWatchFuture;
import build.buildfarm.operations.FindOperationsResults;
import build.buildfarm.operations.FindOperationsSettings;
import build.buildfarm.operations.finder.OperationsFinder;
import build.buildfarm.v1test.BackplaneStatus;
import build.buildfarm.v1test.CompletedOperationMetadata;
import build.buildfarm.v1test.DispatchedOperation;
import build.buildfarm.v1test.ExecuteEntry;
import build.buildfarm.v1test.ExecutingOperationMetadata;
import build.buildfarm.v1test.GetClientStartTime;
import build.buildfarm.v1test.GetClientStartTimeRequest;
import build.buildfarm.v1test.GetClientStartTimeResult;
import build.buildfarm.v1test.OperationChange;
import build.buildfarm.v1test.QueueEntry;
import build.buildfarm.v1test.QueuedOperationMetadata;
import build.buildfarm.v1test.RedisShardBackplaneConfig;
import build.buildfarm.v1test.ShardWorker;
import build.buildfarm.v1test.WorkerChange;

import static java.lang.String.format;

import static redis.clients.jedis.ScanParams.SCAN_POINTER_START;

public class RedisShardBackplane implements Backplane {
  private static final Logger logger = Logger.getLogger(RedisShardBackplane.class.getName());

  private static final JsonFormat.Parser operationParser =
      JsonFormat.parser()
          .usingTypeRegistry(
              JsonFormat.TypeRegistry.newBuilder()
                  .add(CompletedOperationMetadata.getDescriptor())
                  .add(ExecutingOperationMetadata.getDescriptor())
                  .add(ExecuteOperationMetadata.getDescriptor())
                  .add(QueuedOperationMetadata.getDescriptor())
                  .add(PreconditionFailure.getDescriptor())
                  .build())
          .ignoringUnknownFields();

  static final JsonFormat.Printer operationPrinter =
      JsonFormat.printer()
          .usingTypeRegistry(
              JsonFormat.TypeRegistry.newBuilder()
                  .add(CompletedOperationMetadata.getDescriptor())
                  .add(ExecutingOperationMetadata.getDescriptor())
                  .add(ExecuteOperationMetadata.getDescriptor())
                  .add(QueuedOperationMetadata.getDescriptor())
                  .add(PreconditionFailure.getDescriptor())
                  .build());

  private static class ActionAmounts {
    Integer build = 0;
    Integer test = 0;
    Integer unknown = 0;
  }

  private final RedisShardBackplaneConfig config;
  private final String source; // used in operation change publication
  private final Function<Operation, Operation> onPublish;
  private final Function<Operation, Operation> onComplete;
  private final Supplier<JedisCluster> jedisClusterFactory;

  private @Nullable InterruptingRunnable onUnsubscribe = null;
  private Thread subscriptionThread = null;
  private Thread failsafeOperationThread = null;
  private RedisShardSubscriber subscriber = null;
  private RedisShardSubscription operationSubscription = null;
  private ExecutorService subscriberService = null;
  private @Nullable RedisClient client = null;

  private final Set<String> workerSet = Collections.synchronizedSet(new HashSet<>());
  private long workerSetExpiresAt = 0;

  private DistributedState state = new DistributedState();

  public RedisShardBackplane(
      RedisShardBackplaneConfig config,
      String source,
      Function<Operation, Operation> onPublish,
      Function<Operation, Operation> onComplete)
      throws ConfigurationException {
    this(config, source, onPublish, onComplete, JedisClusterFactory.create(config));
  }

  public RedisShardBackplane(
      RedisShardBackplaneConfig config,
      String source,
      Function<Operation, Operation> onPublish,
      Function<Operation, Operation> onComplete,
      Supplier<JedisCluster> jedisClusterFactory) {
    this.config = config;
    this.source = source;
    this.onPublish = onPublish;
    this.onComplete = onComplete;
    this.jedisClusterFactory = jedisClusterFactory;
  }

  @SuppressWarnings("NullableProblems")
  @Override
  public void setOnUnsubscribe(InterruptingRunnable onUnsubscribe) {
    this.onUnsubscribe = onUnsubscribe;
  }

  abstract static class QueueEntryListVisitor extends StringVisitor {
    protected abstract void visit(QueueEntry queueEntry, String queueEntryJson);

    public void visit(String entry) {
      QueueEntry.Builder queueEntry = QueueEntry.newBuilder();
      try {
        JsonFormat.parser().merge(entry, queueEntry);
        visit(queueEntry.build(), entry);
      } catch (InvalidProtocolBufferException e) {
        logger.log(Level.SEVERE, "invalid QueueEntry json: " + entry, e);
      }
    }
  }

  abstract static class ExecuteEntryListVisitor extends StringVisitor {
    protected abstract void visit(ExecuteEntry executeEntry, String executeEntryJson);

    public void visit(String entry) {
      ExecuteEntry.Builder executeEntry = ExecuteEntry.newBuilder();
      try {
        JsonFormat.parser().merge(entry, executeEntry);
        visit(executeEntry.build(), entry);
      } catch (InvalidProtocolBufferException e) {
        logger.log(Level.FINE, "invalid ExecuteEntry json: " + entry, e);
      }
    }
  }

  private Instant convertToMilliInstant(String value, String key) {
    if (value != null) {
      try {
        return Instant.ofEpochMilli(Long.parseLong(value));
      } catch (NumberFormatException e) {
        logger.log(Level.SEVERE, format("invalid expiration %s for %s", value, key));
      }
    }
    return null;
  }

  private void scanProcessing(JedisCluster jedis, Consumer<String> onOperationName, Instant now) {
    state.prequeue.visitDequeue(
        jedis,
        new ExecuteEntryListVisitor() {
          @Override
          protected void visit(ExecuteEntry executeEntry, String executeEntryJson) {
            String operationName = executeEntry.getOperationName();
            String value = state.processingOperations.get(jedis, operationName);
            long defaultTimeout_ms = config.getProcessingTimeoutMillis();

            // get the operation's expiration
            Instant expiresAt = convertToMilliInstant(value, operationName);

            // if expiration is invalid, add a valid one.
            if (expiresAt == null) {
              expiresAt = now.plusMillis(defaultTimeout_ms);
              String keyValue = String.format("%d", expiresAt.toEpochMilli());
              long timeout_s = Time.millisecondsToSeconds(defaultTimeout_ms);
              state.processingOperations.insert(jedis, operationName, keyValue, timeout_s);
            }

            // handle expiration
            if (now.isBefore(expiresAt)) {
              onOperationName.accept(operationName);
            } else {
              if (state.prequeue.removeFromDequeue(jedis, executeEntryJson)) {
                state.processingOperations.remove(jedis, operationName);
              }
            }
          }
        });
  }

  private void scanDispatching(JedisCluster jedis, Consumer<String> onOperationName, Instant now) {
    state.operationQueue.visitDequeue(
        jedis,
        new QueueEntryListVisitor() {
          @Override
          protected void visit(QueueEntry queueEntry, String queueEntryJson) {
            String operationName = queueEntry.getExecuteEntry().getOperationName();
            String value = state.dispatchingOperations.get(jedis, operationName);
            long defaultTimeout_ms = config.getDispatchingTimeoutMillis();

            // get the operation's expiration
            Instant expiresAt = convertToMilliInstant(value, operationName);

            // if expiration is invalid, add a valid one.
            if (expiresAt == null) {
              expiresAt = now.plusMillis(defaultTimeout_ms);
              String keyValue = String.format("%d", expiresAt.toEpochMilli());
              long timeout_s = Time.millisecondsToSeconds(defaultTimeout_ms);
              state.dispatchingOperations.insert(jedis, operationName, keyValue, timeout_s);
            }

            // handle expiration
            if (now.isBefore(expiresAt)) {
              onOperationName.accept(operationName);
            } else {
              if (state.operationQueue.removeFromDequeue(jedis, queueEntryJson)) {
                state.dispatchingOperations.remove(jedis, operationName);
              }
            }
          }
        });
  }

  private void scanPrequeue(JedisCluster jedis, Consumer<String> onOperationName) {
    state.prequeue.visit(
        jedis,
        new ExecuteEntryListVisitor() {
          @Override
          protected void visit(ExecuteEntry executeEntry, String executeEntryJson) {
            onOperationName.accept(executeEntry.getOperationName());
          }
        });
  }

  private void scanQueue(JedisCluster jedis, Consumer<String> onOperationName) {
    state.operationQueue.visit(
        jedis,
        new QueueEntryListVisitor() {
          @Override
          protected void visit(QueueEntry queueEntry, String queueEntryJson) {
            onOperationName.accept(queueEntry.getExecuteEntry().getOperationName());
          }
        });
  }

  private void scanDispatched(JedisCluster jedis, Consumer<String> onOperationName) {
    for (String operationName : state.dispatchedOperations.keys(jedis)) {
      onOperationName.accept(operationName);
    }
  }

  private void updateWatchers(JedisCluster jedis) {
    Instant now = Instant.now();
    Instant expiresAt = nextExpiresAt(now);
    Set<String> expiringChannels = Sets.newHashSet(subscriber.expiredWatchedOperationChannels(now));
    Consumer<String> resetChannel =
        (operationName) -> {
          String channel = operationChannel(operationName);
          if (expiringChannels.remove(channel)) {
            subscriber.resetWatchers(channel, expiresAt);
          }
        };

    if (!expiringChannels.isEmpty()) {
      logger.log(
          Level.FINE,
          format("Scan %d watches, %s, expiresAt: %s", expiringChannels.size(), now, expiresAt));

      logger.log(Level.FINE, "Scan prequeue");
      // scan prequeue, pet watches
      scanPrequeue(jedis, resetChannel);
    }

    // scan processing, create ttl key if missing, remove dead entries, pet live watches
    scanProcessing(jedis, resetChannel, now);

    if (!expiringChannels.isEmpty()) {
      logger.log(Level.FINE, "Scan queue");
      // scan queue, pet watches
      scanQueue(jedis, resetChannel);
    }

    // scan dispatching, create ttl key if missing, remove dead entries, pet live watches
    scanDispatching(jedis, resetChannel, now);

    if (!expiringChannels.isEmpty()) {
      logger.log(Level.FINE, "Scan dispatched");
      // scan dispatched pet watches
      scanDispatched(jedis, resetChannel);
    }

    //
    // filter watches on expiration
    // delete the operation?
    // update expired watches with null operation
    for (String channel : expiringChannels) {
      Operation operation = parseOperationJson(getOperation(jedis, parseOperationChannel(channel)));
      if (operation == null || !operation.getDone()) {
        publishExpiration(jedis, channel, now);
      } else {
        subscriber.onOperation(channel, onPublish.apply(operation), expiresAt);
      }
    }
  }

  static String printOperationChange(OperationChange operationChange)
      throws InvalidProtocolBufferException {
    return operationPrinter.print(operationChange);
  }

  void publish(
      JedisCluster jedis,
      String channel,
      Instant effectiveAt,
      OperationChange.Builder operationChange) {
    try {
      String operationChangeJson =
          printOperationChange(
              operationChange.setEffectiveAt(toTimestamp(effectiveAt)).setSource(source).build());
      jedis.publish(channel, operationChangeJson);
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "error printing operation change", e);
      // very unlikely, printer would have to fail
    }
  }

  void publishReset(JedisCluster jedis, Operation operation) {
    Instant effectiveAt = Instant.now();
    Instant expiresAt = nextExpiresAt(effectiveAt);
    publish(
        jedis,
        operationChannel(operation.getName()),
        Instant.now(),
        OperationChange.newBuilder()
            .setReset(
                OperationChange.Reset.newBuilder()
                    .setExpiresAt(toTimestamp(expiresAt))
                    .setOperation(operation)
                    .build()));
  }

  static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  void publishExpiration(JedisCluster jedis, String channel, Instant effectiveAt) {
    publish(
        jedis,
        channel,
        effectiveAt,
        OperationChange.newBuilder()
            .setExpire(OperationChange.Expire.newBuilder().setForce(false).build()));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public void updateWatchedIfDone(JedisCluster jedis) {
    List<String> operationChannels = subscriber.watchedOperationChannels();
    if (operationChannels.isEmpty()) {
      return;
    }

    Instant now = Instant.now();
    List<Map.Entry<String, Response<String>>> operations = new ArrayList(operationChannels.size());
    JedisClusterPipeline p = jedis.pipelined();
    for (String operationName :
        operationChannels.stream()
            .map(RedisShardBackplane::parseOperationChannel)
            .collect(Collectors.toList())) {
      operations.add(
          new AbstractMap.SimpleEntry<>(operationName, p.get(operationKey(operationName))));
    }
    p.sync();

    for (Map.Entry<String, Response<String>> entry : operations) {
      String json = entry.getValue().get();
      Operation operation = json == null ? null : RedisShardBackplane.parseOperationJson(json);
      String operationName = entry.getKey();
      if (operation == null || operation.getDone()) {
        if (operation != null) {
          operation = onPublish.apply(operation);
        }
        subscriber.onOperation(operationChannel(operationName), operation, nextExpiresAt(now));
        logger.log(
            Level.FINE,
            format(
                "operation %s done due to %s",
                operationName, operation == null ? "null" : "completed"));
      }
    }
  }

  private Instant nextExpiresAt(Instant from) {
    return from.plusSeconds(10);
  }

  private void startSubscriptionThread() {
    ListMultimap<String, TimedWatchFuture> watchers =
        Multimaps.synchronizedListMultimap(
            MultimapBuilder.linkedHashKeys().arrayListValues().build());
    subscriberService = Executors.newFixedThreadPool(32);
    subscriber =
        new RedisShardSubscriber(watchers, workerSet, config.getWorkerChannel(), subscriberService);

    operationSubscription =
        new RedisShardSubscription(
            subscriber,
            /* onUnsubscribe=*/ () -> {
              subscriptionThread = null;
              if (onUnsubscribe != null) {
                onUnsubscribe.runInterruptibly();
              }
            },
            /* onReset=*/ this::updateWatchedIfDone,
            /* subscriptions=*/ subscriber::subscribedChannels,
            client);

    // use Executors...
    subscriptionThread = new Thread(operationSubscription, "Operation Subscription");

    subscriptionThread.start();
  }

  @SuppressWarnings("ConstantConditions")
  private void startFailsafeOperationThread() {
    failsafeOperationThread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  TimeUnit.SECONDS.sleep(10);
                  client.run(this::updateWatchers);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                } catch (Exception e) {
                  logger.log(Level.SEVERE, "error while updating watchers in failsafe", e);
                }
              }
            },
            "Failsafe Operation");

    failsafeOperationThread.start();
  }

  @Override
  public void start(String clientPublicName) throws IOException {
    // Construct a single redis client to be used throughout the entire backplane.
    // We wish to avoid various synchronous and error handling issues that could occur when using
    // multiple clients.
    client = new RedisClient(jedisClusterFactory.get());

    // Create containers that make up the backplane
    state = RedisDistributedStateCreator.create(client, config);

    if (config.getSubscribeToBackplane()) {
      startSubscriptionThread();
    }
    if (config.getRunFailsafeOperation()) {
      startFailsafeOperationThread();
    }

    // Record client start time
    client.call(
        jedis -> jedis.set("startTime/" + clientPublicName, Long.toString(new Date().getTime())));
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Override
  public synchronized void stop() throws InterruptedException {
    if (failsafeOperationThread != null) {
      failsafeOperationThread.interrupt();
      failsafeOperationThread.join();
      logger.log(Level.FINE, "failsafeOperationThread has been stopped");
    }
    if (operationSubscription != null) {
      operationSubscription.stop();
      if (subscriptionThread != null) {
        subscriptionThread.join();
      }
      logger.log(Level.FINE, "subscriptionThread has been stopped");
    }
    if (subscriberService != null) {
      subscriberService.shutdown();
      subscriberService.awaitTermination(10, TimeUnit.SECONDS);
      logger.log(Level.FINE, "subscriberService has been stopped");
    }
    if (client != null) {
      client.close();
      client = null;
      logger.log(Level.FINE, "client has been closed");
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public boolean isStopped() {
    return client.isClosed();
  }

  @Override
  public ListenableFuture<Void> watchOperation(String operationName, Watcher watcher) {
    TimedWatcher timedWatcher =
        new TimedWatcher(nextExpiresAt(Instant.now())) {
          @Override
          public void observe(Operation operation) {
            watcher.observe(operation);
          }
        };
    return subscriber.watch(operationChannel(operationName), timedWatcher);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void addWorker(ShardWorker shardWorker) throws IOException {
    String json = JsonFormat.printer().print(shardWorker);
    String workerChangeJson =
        JsonFormat.printer()
            .print(
                WorkerChange.newBuilder()
                    .setEffectiveAt(toTimestamp(Instant.now()))
                    .setName(shardWorker.getEndpoint())
                    .setAdd(WorkerChange.Add.getDefaultInstance())
                    .build());
    client.call(
        jedis -> {
          // could rework with an hget to publish prior, but this seems adequate, and
          // we are the only guaranteed source
          if (state.workers.insert(jedis, shardWorker.getEndpoint(), json)) {
            jedis.publish(config.getWorkerChannel(), workerChangeJson);
            return true;
          }
          return false;
        });
  }

  private boolean removeWorkerAndPublish(JedisCluster jedis, String name, String changeJson) {
    if (state.workers.remove(jedis, name)) {
      jedis.publish(config.getWorkerChannel(), changeJson);
      return true;
    }
    return false;
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public boolean removeWorker(String name, String reason) throws IOException {
    WorkerChange workerChange =
        WorkerChange.newBuilder()
            .setName(name)
            .setRemove(WorkerChange.Remove.newBuilder().setSource(source).setReason(reason).build())
            .build();
    String workerChangeJson = JsonFormat.printer().print(workerChange);
    return subscriber.removeWorker(name)
        && client.call(jedis -> removeWorkerAndPublish(jedis, name, workerChangeJson));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public CasIndexResults reindexCas() throws IOException {
    CasIndexSettings settings = new CasIndexSettings();
    settings.casQuery = config.getCasPrefix() + ":*";
    settings.scanAmount = 10000;
    return client.call(jedis -> WorkerIndexer.removeWorkerIndexesFromCas(jedis, settings));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public FindOperationsResults findOperations(Instance instance, String filterPredicate)
      throws IOException {
    FindOperationsSettings settings = new FindOperationsSettings();
    settings.filterPredicate = filterPredicate;
    settings.operationQuery = config.getOperationPrefix() + ":*";
    settings.scanAmount = 10000;
    return client.call(jedis -> OperationsFinder.findOperations(jedis, instance, settings));
  }

  @Override
  public void deregisterWorker(String workerName) throws IOException {
    removeWorker(workerName, "Requested shutdown");
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public synchronized Set<String> getWorkers() throws IOException {
    long now = System.currentTimeMillis();
    if (now < workerSetExpiresAt) {
      return workerSet;
    }

    synchronized (workerSet) {
      Set<String> newWorkerSet = client.call(jedis -> fetchAndExpireWorkers(jedis, now));
      workerSet.clear();
      workerSet.addAll(newWorkerSet);
    }

    // fetch every 3 seconds
    workerSetExpiresAt = now + 3000;
    return workerSet;
  }

  // When performing a graceful scale down of workers, the backplane can provide worker names to the
  // scale-down service. The algorithm in which the backplane chooses these workers can be made more
  // sophisticated in the future. But for now, we'll give back n random workers.
  public List<String> suggestedWorkersToScaleDown(int numWorkers) throws IOException {
    // get all workers
    List<String> allWorkers = new ArrayList<>(getWorkers());

    // ensure selection amount is in range [0 - size]
    numWorkers = Math.max(0, Math.min(numWorkers, allWorkers.size()));

    // select n workers
    return randomN(allWorkers, numWorkers);
  }

  public static <T> List<T> randomN(List<T> list, int n) {
    return Stream.generate(() -> list.remove((int) (list.size() * Math.random())))
        .limit(Math.min(list.size(), n))
        .collect(Collectors.toList());
  }

  private void removeInvalidWorkers(JedisCluster jedis, long testedAt, List<ShardWorker> workers) {
    if (!workers.isEmpty()) {
      for (ShardWorker worker : workers) {
        String name = worker.getEndpoint();
        String reason =
            format("registration expired at %d, tested at %d", worker.getExpireAt(), testedAt);
        WorkerChange workerChange =
            WorkerChange.newBuilder()
                .setEffectiveAt(toTimestamp(Instant.now()))
                .setName(name)
                .setRemove(
                    WorkerChange.Remove.newBuilder().setSource(source).setReason(reason).build())
                .build();
        try {
          String workerChangeJson = JsonFormat.printer().print(workerChange);
          removeWorkerAndPublish(jedis, name, workerChangeJson);
        } catch (InvalidProtocolBufferException e) {
          logger.log(Level.SEVERE, "error printing workerChange", e);
        }
      }
    }
  }

  private Set<String> fetchAndExpireWorkers(JedisCluster jedis, long now) {
    Set<String> returnWorkers = Sets.newConcurrentHashSet();
    ImmutableList.Builder<ShardWorker> invalidWorkers = ImmutableList.builder();
    for (Map.Entry<String, String> entry : state.workers.asMap(jedis).entrySet()) {
      String json = entry.getValue();
      String name = entry.getKey();
      try {
        if (json == null) {
          invalidWorkers.add(ShardWorker.newBuilder().setEndpoint(name).build());
        } else {
          ShardWorker.Builder builder = ShardWorker.newBuilder();
          JsonFormat.parser().merge(json, builder);
          ShardWorker worker = builder.build();
          if (worker.getExpireAt() <= now) {
            invalidWorkers.add(worker);
          } else {
            returnWorkers.add(worker.getEndpoint());
          }
        }
      } catch (InvalidProtocolBufferException e) {
        invalidWorkers.add(ShardWorker.newBuilder().setEndpoint(name).build());
      }
    }
    removeInvalidWorkers(jedis, now, invalidWorkers.build());
    return returnWorkers;
  }

  private static ActionResult parseActionResult(String json) {
    try {
      ActionResult.Builder builder = ActionResult.newBuilder();
      JsonFormat.parser().merge(json, builder);
      return builder.build();
    } catch (InvalidProtocolBufferException e) {
      return null;
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public ActionResult getActionResult(ActionKey actionKey) throws IOException {
    String json = client.call(jedis -> state.actionCache.get(jedis, asDigestStr(actionKey)));
    if (json == null) {
      return null;
    }

    ActionResult actionResult = parseActionResult(json);
    if (actionResult == null) {
      client.run(jedis -> removeActionResult(jedis, actionKey));
    }
    return actionResult;
  }

  // we do this by action hash only, so that we can use RequestMetadata to filter
  @SuppressWarnings("ConstantConditions")
  @Override
  public void blacklistAction(String actionId) throws IOException {
    client.run(
        jedis ->
            state.blockedActions.insert(jedis, actionId, "", config.getActionBlacklistExpire()));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void putActionResult(ActionKey actionKey, ActionResult actionResult) throws IOException {
    String json = JsonFormat.printer().print(actionResult);
    client.run(
        jedis ->
            state.actionCache.insert(
                jedis, asDigestStr(actionKey), json, config.getActionCacheExpire()));
  }

  private void removeActionResult(JedisCluster jedis, ActionKey actionKey) {
    state.actionCache.remove(jedis, asDigestStr(actionKey));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void removeActionResult(ActionKey actionKey) throws IOException {
    client.run(jedis -> removeActionResult(jedis, actionKey));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void removeActionResults(Iterable<ActionKey> actionKeys) throws IOException {
    // convert action keys to strings
    List<String> keyNames = new ArrayList<>();
    actionKeys.forEach(key -> keyNames.add(asDigestStr(key)));

    client.run(jedis -> state.actionCache.remove(jedis, keyNames));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public ActionCacheScanResult scanActionCache(String scanToken, int count) throws IOException {
    final String jedisScanToken = scanToken == null ? SCAN_POINTER_START : scanToken;

    ImmutableList.Builder<Map.Entry<ActionKey, String>> results = new ImmutableList.Builder<>();

    ScanParams scanParams =
        new ScanParams().match(config.getActionCachePrefix() + ":*").count(count);

    String token =
        client.call(
            jedis -> {
              ScanResult<String> scanResult = jedis.scan(jedisScanToken, scanParams);
              List<String> keyResults = scanResult.getResult();

              List<Response<String>> actionResults = new ArrayList<>(keyResults.size());
              JedisClusterPipeline p = jedis.pipelined();
              for (String key : keyResults) {
                actionResults.add(p.get(key));
              }
              p.sync();
              for (int i = 0; i < keyResults.size(); i++) {
                String json = actionResults.get(i).get();
                if (json == null) {
                  continue;
                }
                String key = keyResults.get(i);
                results.add(
                    new AbstractMap.SimpleEntry<>(
                        DigestUtil.asActionKey(DigestUtil.parseDigest(key.split(":")[1])), json));
              }
              String cursor = scanResult.getCursor();
              return cursor.equals(SCAN_POINTER_START) ? null : cursor;
            });
    return new ActionCacheScanResult(
        token,
        results.build().stream()
            .map(
                (entry) ->
                    new AbstractMap.SimpleEntry<>(
                        entry.getKey(), parseActionResult(entry.getValue())))
            .collect(Collectors.toList()));
  }

  @Override
  public void adjustBlobLocations(
      Digest blobDigest, Set<String> addWorkers, Set<String> removeWorkers) throws IOException {
    state.casWorkerMap.adjust(client, blobDigest, addWorkers, removeWorkers);
  }

  @Override
  public void addBlobLocation(Digest blobDigest, String workerName) throws IOException {
    state.casWorkerMap.add(client, blobDigest, workerName);
  }

  @Override
  public void addBlobsLocation(Iterable<Digest> blobDigests, String workerName) throws IOException {
    state.casWorkerMap.addAll(client, blobDigests, workerName);
  }

  @Override
  public void removeBlobLocation(Digest blobDigest, String workerName) throws IOException {
    state.casWorkerMap.remove(client, blobDigest, workerName);
  }

  @Override
  public void removeBlobsLocation(Iterable<Digest> blobDigests, String workerName)
      throws IOException {
    state.casWorkerMap.removeAll(client, blobDigests, workerName);
  }

  @Override
  public String getBlobLocation(Digest blobDigest) throws IOException {
    return state.casWorkerMap.getAny(client, blobDigest);
  }

  @Override
  public Set<String> getBlobLocationSet(Digest blobDigest) throws IOException {
    return state.casWorkerMap.get(client, blobDigest);
  }

  @Override
  public Map<Digest, Set<String>> getBlobDigestsWorkers(Iterable<Digest> blobDigests)
      throws IOException {
    return state.casWorkerMap.getMap(client, blobDigests);
  }

  public static WorkerChange parseWorkerChange(String workerChangeJson)
      throws InvalidProtocolBufferException {
    WorkerChange.Builder workerChange = WorkerChange.newBuilder();
    JsonFormat.parser().merge(workerChangeJson, workerChange);
    return workerChange.build();
  }

  public static OperationChange parseOperationChange(String operationChangeJson)
      throws InvalidProtocolBufferException {
    OperationChange.Builder operationChange = OperationChange.newBuilder();
    operationParser.merge(operationChangeJson, operationChange);
    return operationChange.build();
  }

  public static Operation parseOperationJson(String operationJson) {
    if (operationJson == null) {
      return null;
    }
    try {
      Operation.Builder operationBuilder = Operation.newBuilder();
      operationParser.merge(operationJson, operationBuilder);
      return operationBuilder.build();
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "error parsing operation from " + operationJson, e);
      return null;
    }
  }

  private String getOperation(JedisCluster jedis, String operationName) {
    return jedis.get(operationKey(operationName));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public Operation getOperation(String operationName) throws IOException {
    String json = client.call(jedis -> getOperation(jedis, operationName));
    return parseOperationJson(json);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public boolean putOperation(Operation operation, ExecutionStage.Value stage) throws IOException {
    boolean queue = stage == ExecutionStage.Value.QUEUED;
    boolean complete = !queue && operation.getDone();
    boolean publish = !queue && stage != ExecutionStage.Value.UNKNOWN;

    if (complete) {
      // for filtering anything that shouldn't be stored
      operation = onComplete.apply(operation);
    }

    String json;
    try {
      json = operationPrinter.print(operation);
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "error printing operation " + operation.getName(), e);
      return false;
    }

    Operation publishOperation;
    if (publish) {
      publishOperation = onPublish.apply(operation);
    } else {
      publishOperation = null;
    }

    String name = operation.getName();
    client.run(
        jedis -> {
          jedis.setex(operationKey(name), config.getOperationExpire(), json);
          if (publishOperation != null) {
            publishReset(jedis, publishOperation);
          }
          if (complete) {
            completeOperation(jedis, name);
          }
        });
    return true;
  }

  private void queue(
      JedisCluster jedis,
      String operationName,
      List<Platform.Property> provisions,
      String queueEntryJson,
      int priority) {
    if (state.dispatchedOperations.remove(jedis, operationName)) {
      logger.log(Level.WARNING, format("removed dispatched operation %s", operationName));
    }
    state.operationQueue.push(jedis, provisions, queueEntryJson, priority);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void queue(QueueEntry queueEntry, Operation operation) throws IOException {
    String operationName = operation.getName();
    String operationJson = operationPrinter.print(operation);
    String queueEntryJson = JsonFormat.printer().print(queueEntry);
    Operation publishOperation = onPublish.apply(operation);
    int priority = queueEntry.getExecuteEntry().getExecutionPolicy().getPriority();
    client.run(
        jedis -> {
          jedis.setex(operationKey(operationName), config.getOperationExpire(), operationJson);
          queue(
              jedis,
              operation.getName(),
              queueEntry.getPlatform().getPropertiesList(),
              queueEntryJson,
              priority);
          publishReset(jedis, publishOperation);
        });
  }

  @SuppressWarnings("ConstantConditions")
  public Map<String, Operation> getOperationsMap() throws IOException {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    client.run(
        jedis -> {
          for (Map.Entry<String, String> entry :
              state.dispatchedOperations.asMap(jedis).entrySet()) {
            builder.put(entry.getKey(), entry.getValue());
          }
        });
    return Maps.transformValues(builder.build(), RedisShardBackplane::parseOperationJson);
  }

  @Override
  public Iterable<String> getOperations() {
    throw new UnsupportedOperationException();
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public ImmutableList<DispatchedOperation> getDispatchedOperations() throws IOException {
    ImmutableList.Builder<DispatchedOperation> builder = new ImmutableList.Builder<>();
    Map<String, String> operations = client.call(jedis -> state.dispatchedOperations.asMap(jedis));

    ImmutableList.Builder<String> invalidOperationNames = new ImmutableList.Builder<>();
    boolean hasInvalid = false;
    // executor work queue?
    for (Map.Entry<String, String> entry : operations.entrySet()) {
      try {
        DispatchedOperation.Builder dispatchedOperationBuilder = DispatchedOperation.newBuilder();
        JsonFormat.parser().merge(entry.getValue(), dispatchedOperationBuilder);
        builder.add(dispatchedOperationBuilder.build());
      } catch (InvalidProtocolBufferException e) {
        logger.log(
            Level.SEVERE,
            "RedisShardBackplane::getDispatchedOperations: removing invalid operation "
                + entry.getKey(),
            e);
        /* guess we don't want to spin on this */
        invalidOperationNames.add(entry.getKey());
        hasInvalid = true;
      }
    }

    if (hasInvalid) {
      client.run(
          jedis -> {
            state.dispatchedOperations.remove(jedis, invalidOperationNames.build());
          });
    }
    return builder.build();
  }

  private ExecuteEntry deprequeueOperation(JedisCluster jedis) throws InterruptedException {
    String executeEntryJson = state.prequeue.dequeue(jedis);
    if (executeEntryJson == null) {
      return null;
    }

    ExecuteEntry.Builder executeEntryBuilder = ExecuteEntry.newBuilder();
    try {
      JsonFormat.parser().merge(executeEntryJson, executeEntryBuilder);
      ExecuteEntry executeEntry = executeEntryBuilder.build();
      String operationName = executeEntry.getOperationName();

      Operation operation = keepaliveOperation(operationName);
      // publish so that watchers reset their timeout
      publishReset(jedis, operation);

      // destroy the processing entry and ttl
      if (!state.prequeue.removeFromDequeue(jedis, executeEntryJson)) {
        logger.log(
            Level.SEVERE,
            format("could not remove %s from %s", operationName, state.prequeue.getDequeueName()));
        return null;
      }
      state.processingOperations.remove(jedis, operationName);
      return executeEntry;
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "error parsing execute entry", e);
      return null;
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public ExecuteEntry deprequeueOperation() throws IOException, InterruptedException {
    return client.blockingCall(this::deprequeueOperation);
  }

  private @Nullable QueueEntry dispatchOperation(
      JedisCluster jedis, List<Platform.Property> provisions) throws InterruptedException {
    String queueEntryJson = state.operationQueue.dequeue(jedis, provisions);
    if (queueEntryJson == null) {
      return null;
    }

    QueueEntry.Builder queueEntryBuilder = QueueEntry.newBuilder();
    try {
      JsonFormat.parser().merge(queueEntryJson, queueEntryBuilder);
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "error parsing queue entry", e);
      return null;
    }
    QueueEntry queueEntry = queueEntryBuilder.build();

    String operationName = queueEntry.getExecuteEntry().getOperationName();
    Operation operation = keepaliveOperation(operationName);
    publishReset(jedis, operation);

    long requeueAt = System.currentTimeMillis() + config.getDispatchingTimeoutMillis();
    DispatchedOperation o =
        DispatchedOperation.newBuilder().setQueueEntry(queueEntry).setRequeueAt(requeueAt).build();
    boolean success = false;
    try {
      String dispatchedOperationJson = JsonFormat.printer().print(o);

      /* if the operation is already in the dispatch list, fail the dispatch */
      success =
          state.dispatchedOperations.insertIfMissing(jedis, operationName, dispatchedOperationJson);
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "error printing dispatched operation", e);
      // very unlikely, printer would have to fail
    }

    if (success) {
      if (!state.operationQueue.removeFromDequeue(jedis, queueEntryJson)) {
        logger.log(
            Level.WARNING,
            format(
                "operation %s was missing in %s, may be orphaned",
                operationName, state.operationQueue.getDequeueName()));
      }
      state.dispatchingOperations.remove(jedis, operationName);

      // Return an entry so that if it needs re-queued, it will have the correct "requeue attempts".
      return queueEntryBuilder.setRequeueAttempts(queueEntry.getRequeueAttempts() + 1).build();
    }
    return null;
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public QueueEntry dispatchOperation(List<Platform.Property> provisions)
      throws IOException, InterruptedException {
    return client.blockingCall(jedis -> dispatchOperation(jedis, provisions));
  }

  String printPollOperation(QueueEntry queueEntry, long requeueAt)
      throws InvalidProtocolBufferException {
    DispatchedOperation o =
        DispatchedOperation.newBuilder().setQueueEntry(queueEntry).setRequeueAt(requeueAt).build();
    return JsonFormat.printer().print(o);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void rejectOperation(QueueEntry queueEntry) throws IOException {
    String operationName = queueEntry.getExecuteEntry().getOperationName();
    String queueEntryJson = JsonFormat.printer().print(queueEntry);
    String dispatchedEntryJson = printPollOperation(queueEntry, 0);
    client.run(
        jedis -> {
          if (isBlacklisted(jedis, queueEntry.getExecuteEntry().getRequestMetadata())) {
            pollOperation(
                jedis, operationName, dispatchedEntryJson); // complete our lease to error operation
          } else {
            Operation operation = parseOperationJson(getOperation(jedis, operationName));
            boolean requeue =
                operation != null && !operation.getDone(); // operation removed or completed somehow
            if (state.dispatchedOperations.remove(jedis, operationName) && requeue) {
              int priority = queueEntry.getExecuteEntry().getExecutionPolicy().getPriority();
              state.operationQueue.push(
                  jedis, queueEntry.getPlatform().getPropertiesList(), queueEntryJson, priority);
            }
          }
        });
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public boolean pollOperation(QueueEntry queueEntry, ExecutionStage.Value stage, long requeueAt)
      throws IOException {
    String operationName = queueEntry.getExecuteEntry().getOperationName();
    String json;
    try {
      json = printPollOperation(queueEntry, requeueAt);
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "error printing dispatched operation " + operationName, e);
      return false;
    }
    return client.call(jedis -> pollOperation(jedis, operationName, json));
  }

  boolean pollOperation(JedisCluster jedis, String operationName, String dispatchedOperationJson) {
    if (state.dispatchedOperations.exists(jedis, operationName)) {
      if (!state.dispatchedOperations.insert(jedis, operationName, dispatchedOperationJson)) {
        return true;
      }
      /* someone else beat us to the punch, delete our incorrectly added key */
      state.dispatchedOperations.remove(jedis, operationName);
    }
    return false;
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void prequeue(ExecuteEntry executeEntry, Operation operation) throws IOException {
    String operationName = operation.getName();
    String operationJson = operationPrinter.print(operation);
    String executeEntryJson = JsonFormat.printer().print(executeEntry);
    Operation publishOperation = onPublish.apply(operation);
    int priority = executeEntry.getExecutionPolicy().getPriority();
    client.run(
        jedis -> {
          jedis.setex(operationKey(operationName), config.getOperationExpire(), operationJson);
          state.prequeue.push(jedis, executeEntryJson, priority);
          publishReset(jedis, publishOperation);
        });
  }

  private Operation keepaliveOperation(String operationName) {
    return Operation.newBuilder().setName(operationName).build();
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void queueing(String operationName) throws IOException {
    Operation operation = keepaliveOperation(operationName);
    // publish so that watchers reset their timeout
    client.run(jedis -> publishReset(jedis, operation));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void requeueDispatchedOperation(QueueEntry queueEntry) throws IOException {
    String queueEntryJson = JsonFormat.printer().print(queueEntry);
    String operationName = queueEntry.getExecuteEntry().getOperationName();
    Operation publishOperation = keepaliveOperation(operationName);
    int priority = queueEntry.getExecuteEntry().getExecutionPolicy().getPriority();
    client.run(
        jedis -> {
          queue(
              jedis,
              operationName,
              queueEntry.getPlatform().getPropertiesList(),
              queueEntryJson,
              priority);
          publishReset(jedis, publishOperation);
        });
  }

  private void completeOperation(JedisCluster jedis, String operationName) {
    state.dispatchedOperations.remove(jedis, operationName);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void completeOperation(String operationName) throws IOException {
    client.run(jedis -> completeOperation(jedis, operationName));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void deleteOperation(String operationName) throws IOException {
    Operation o =
        Operation.newBuilder()
            .setName(operationName)
            .setDone(true)
            .setError(Status.newBuilder().setCode(Code.UNAVAILABLE.getNumber()).build())
            .build();

    client.run(
        jedis -> {
          completeOperation(jedis, operationName);
          // FIXME find a way to get rid of this thing from the queue by name
          // jedis.lrem(config.getQueuedOperationsListName(), 0, operationName);
          jedis.del(operationKey(operationName));

          publishReset(jedis, o);
        });
  }

  private String asDigestStr(ActionKey actionKey) {
    return DigestUtil.toString(actionKey.getDigest());
  }

  String operationKey(String operationName) {
    return config.getOperationPrefix() + ":" + operationName;
  }

  String operationChannel(String operationName) {
    return config.getOperationChannelPrefix() + ":" + operationName;
  }

  public static String parseOperationChannel(String channel) {
    return channel.split(":")[1];
  }

  @Override
  public Boolean propertiesEligibleForQueue(List<Platform.Property> provisions) {
    return state.operationQueue.isEligible(provisions);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public boolean isBlacklisted(RequestMetadata requestMetadata) throws IOException {
    if (requestMetadata.getToolInvocationId().isEmpty()
        && requestMetadata.getActionId().isEmpty()) {
      return false;
    }
    return client.call(jedis -> isBlacklisted(jedis, requestMetadata));
  }

  private boolean isBlacklisted(JedisCluster jedis, RequestMetadata requestMetadata) {
    boolean isActionBlocked =
        (!requestMetadata.getActionId().isEmpty()
            && state.blockedActions.exists(jedis, requestMetadata.getActionId()));
    boolean isInvocationBlocked =
        (!requestMetadata.getToolInvocationId().isEmpty()
            && state.blockedInvocations.exists(jedis, requestMetadata.getToolInvocationId()));
    return isActionBlocked || isInvocationBlocked;
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public boolean canQueue() throws IOException {
    return client.call(jedis -> state.operationQueue.canQueue(jedis));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public boolean canPrequeue() throws IOException {
    return client.call(jedis -> state.prequeue.canQueue(jedis));
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public BackplaneStatus backplaneStatus() throws IOException {
    BackplaneStatus.Builder builder = BackplaneStatus.newBuilder();
    builder.addAllActiveWorkers(client.call(jedis -> state.workers.keys(jedis)));
    builder.setDispatchedSize(client.call(jedis -> state.dispatchedOperations.size(jedis)));
    builder.setOperationQueue(state.operationQueue.status(client.call(jedis -> jedis)));
    builder.setPrequeue(state.prequeue.status(client.call(jedis -> jedis)));
    return builder.build();
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public GetClientStartTimeResult getClientStartTime(GetClientStartTimeRequest request)
      throws IOException {
    List<GetClientStartTime> startTimes = new ArrayList<>();
    for (String key : request.getHostNameList()) {
      try {
        startTimes.add(
            client.call(
                jedis ->
                    GetClientStartTime.newBuilder()
                        .setInstanceName(key)
                        .setClientStartTime(Timestamps.fromMillis(Long.parseLong(jedis.get(key))))
                        .build()));
      } catch (NumberFormatException nfe) {
        logger.warning("Could not obtain start time for " + key);
      }
    }
    return GetClientStartTimeResult.newBuilder().addAllClientStartTime(startTimes).build();
  }
}
