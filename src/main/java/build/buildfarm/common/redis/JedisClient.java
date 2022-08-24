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

package build.buildfarm.common.redis;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import build.buildfarm.common.gencache.RedisClient;
import io.grpc.Status;
import io.grpc.Status.Code;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisNoReachableClusterNodeException;

public class JedisClient extends RedisClient {

  public JedisClient(JedisDriver redis) {
    super(redis);
  }

  private static class JedisMisconfigurationException extends JedisDataException {
    public JedisMisconfigurationException(final String message) {
      super(message);
    }

    public JedisMisconfigurationException(final Throwable cause) {
      super(cause);
    }

    public JedisMisconfigurationException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  @Override
  public <T> T call(RedisContext<T> withRedis) throws IOException {
    throwIfClosed();
    try {
      try {
        return withRedis.run(redis);
      } catch (JedisDataException e) {
        if (e.getMessage().startsWith(MISCONF_RESPONSE)) {
          throw new JedisMisconfigurationException(e.getMessage());
        }
        throw e;
      }
    } catch (JedisMisconfigurationException | JedisNoReachableClusterNodeException e) {
      // In regards to a Jedis misconfiguration,
      // the backplane is configured not to accept writes currently
      // as a result of an error. The error is meant to indicate
      // that substantial resources were unavailable.
      // we must throw an IOException which indicates as much
      // this looks simply to me like a good opportunity to use UNAVAILABLE
      // we are technically not at RESOURCE_EXHAUSTED, this is a
      // persistent state which can exist long past the error
      throw new IOException(Status.UNAVAILABLE.withCause(e).asRuntimeException());
    } catch (JedisConnectionException e) {
      if ((e.getMessage() != null && e.getMessage().equals("Unexpected end of stream."))
          || e.getCause() instanceof ConnectException) {
        throw new IOException(Status.UNAVAILABLE.withCause(e).asRuntimeException());
      }
      Throwable cause = e;
      Status status = Status.UNKNOWN;
      while (status.getCode() == Code.UNKNOWN && cause != null) {
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        if ((cause instanceof SocketException && cause.getMessage().equals("Connection reset"))
            || cause instanceof ConnectException
            || message.equals("Unexpected end of stream.")) {
          status = Status.UNAVAILABLE;
        } else if (cause instanceof SocketTimeoutException) {
          status = Status.DEADLINE_EXCEEDED;
        } else if (cause instanceof IOException) {
          throw (IOException) cause;
        } else {
          cause = cause.getCause();
        }
      }
      throw new IOException(status.withCause(cause == null ? e : cause).asRuntimeException());
    }
  }
}
