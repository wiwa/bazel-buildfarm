// Copyright 2020 The Bazel Authors. All rights reserved.
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

import static com.google.common.util.concurrent.Futures.catching;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static net.javacrumbs.futureconverter.java8guava.FutureConverter.toCompletableFuture;
import static net.javacrumbs.futureconverter.java8guava.FutureConverter.toListenableFuture;

import build.bazel.remote.execution.v2.ActionResult;
import build.buildfarm.backplane.Backplane;
import build.buildfarm.common.DigestUtil.ActionKey;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import io.grpc.Status;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

class ShardActionCache implements ReadThroughActionCache {
  private final Backplane backplane;
  private final AsyncLoadingCache<ActionKey, ActionResult> actionResultCache;

  ShardActionCache(int maxLocalCacheSize, Backplane backplane, ListeningExecutorService service) {
    this.backplane = backplane;

    AsyncCacheLoader<ActionKey, ActionResult> loader =
        (actionKey, executor) ->
            toCompletableFuture(
                catching(
                    service.submit(() -> backplane.getActionResult(actionKey)),
                    IOException.class,
                    e -> {
                      throw Status.fromThrowable(e).asRuntimeException();
                    },
                    executor));

    actionResultCache = Caffeine.newBuilder().maximumSize(maxLocalCacheSize).buildAsync(loader);
  }

  @Override
  public ListenableFuture<ActionResult> get(ActionKey actionKey) {
    return catching(
        toListenableFuture(actionResultCache.get(actionKey)),
        InvalidCacheLoadException.class,
        e -> null,
        directExecutor());
  }

  @Override
  public void put(ActionKey actionKey, ActionResult actionResult) {
    try {
      backplane.putActionResult(actionKey, actionResult);
    } catch (IOException e) {
      // this should be a non-grpc runtime exception
      throw Status.fromThrowable(e).asRuntimeException();
    }
    readThrough(actionKey, actionResult);
  }

  @Override
  public void invalidate(ActionKey actionKey) {
    actionResultCache.synchronous().invalidate(actionKey);
  }

  @Override
  public void readThrough(ActionKey actionKey, ActionResult actionResult) {
    actionResultCache.put(actionKey, CompletableFuture.completedFuture(actionResult));
  }
}
