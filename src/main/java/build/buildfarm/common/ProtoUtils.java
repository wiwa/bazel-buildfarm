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

package build.buildfarm.common;

import build.bazel.remote.execution.v2.Digest;
import build.buildfarm.v1test.QueueEntry;
import build.buildfarm.v1test.QueuedOperation;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @class ProtoUtils
 * @brief Utilities related to parsing proto data.
 * @details Performs validation and error reporting of proto data.
 */
public class ProtoUtils {
  private static final Logger logger = Logger.getLogger(ProtoUtils.class.getName());

  public static QueuedOperation parseQueuedOperation(
      ByteString queuedOperationBlob, QueueEntry queueEntry) {
    Digest queuedOperationDigest = queueEntry.getQueuedOperationDigest();
    String operationName = queueEntry.getExecuteEntry().getOperationName();

    if (queuedOperationBlob == null) {
      logger.log(
          Level.WARNING,
          String.format(
              "missing queued operation: %s(%s)",
              operationName, DigestUtil.toString(queuedOperationDigest)));
      return null;
    }
    try {
      return QueuedOperation.parseFrom(queuedOperationBlob);
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          Level.WARNING,
          String.format(
              "invalid queued operation: %s(%s).  Cannot parse operation blob: %s",
              operationName, DigestUtil.toString(queuedOperationDigest), e));
      return null;
    }
  }
}
