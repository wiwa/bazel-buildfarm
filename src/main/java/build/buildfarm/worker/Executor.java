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

package build.buildfarm.worker;

import static build.buildfarm.v1test.ExecutionPolicy.PolicyCase.WRAPPER;
import static com.google.common.collect.Maps.uniqueIndex;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Command.EnvironmentVariable;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecutionStage;
import build.bazel.remote.execution.v2.Platform.Property;
import build.buildfarm.common.Write;
import build.buildfarm.common.Write.NullWrite;
import build.buildfarm.v1test.ExecutingOperationMetadata;
import build.buildfarm.v1test.ExecutionPolicy;
import build.buildfarm.v1test.ExecutionWrapper;
import build.buildfarm.worker.WorkerContext.IOResource;
import build.buildfarm.worker.resources.ResourceLimits;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.shell.Protos.ExecutionStatistics;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import io.grpc.Deadline;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

class Executor {
  private static final int INCOMPLETE_EXIT_CODE = -1;
  private static final Logger logger = Logger.getLogger(Executor.class.getName());

  private static final Object execLock = new Object();

  private final WorkerContext workerContext;
  private final OperationContext operationContext;
  private final ExecuteActionStage owner;
  private int exitCode = INCOMPLETE_EXIT_CODE;
  private boolean wasErrored = false;

  Executor(
      WorkerContext workerContext, OperationContext operationContext, ExecuteActionStage owner) {
    this.workerContext = workerContext;
    this.operationContext = operationContext;
    this.owner = owner;
  }

  // ensure that only one error put attempt occurs
  private void putError() throws InterruptedException {
    if (!wasErrored) {
      wasErrored = true;
      owner.error().put(operationContext);
    }
  }

  private long runInterruptible(Stopwatch stopwatch, ResourceLimits limits)
      throws InterruptedException {
    long startedAt = System.currentTimeMillis();

    ExecuteOperationMetadata metadata;
    try {
      metadata = operationContext.operation.getMetadata().unpack(ExecuteOperationMetadata.class);
    } catch (InvalidProtocolBufferException e) {
      logger.log(Level.SEVERE, "invalid execute operation metadata", e);
      return 0;
    }
    ExecuteOperationMetadata executingMetadata =
        metadata.toBuilder().setStage(ExecutionStage.Value.EXECUTING).build();

    Iterable<ExecutionPolicy> policies = new ArrayList<>();
    if (limits.useExecutionPolicies) {
      policies =
          ExecutionPolicies.forPlatform(
              operationContext.command.getPlatform(), workerContext::getExecutionPolicies);
    }

    Operation operation =
        operationContext
            .operation
            .toBuilder()
            .setMetadata(
                Any.pack(
                    ExecutingOperationMetadata.newBuilder()
                        .setStartedAt(startedAt)
                        .setExecutingOn(workerContext.getName())
                        .setExecuteOperationMetadata(executingMetadata)
                        .setRequestMetadata(
                            operationContext.queueEntry.getExecuteEntry().getRequestMetadata())
                        .build()))
            .build();

    boolean operationUpdateSuccess = false;
    try {
      operationUpdateSuccess = workerContext.putOperation(operation);
    } catch (IOException e) {
      logger.log(
          Level.SEVERE, format("error putting operation %s as EXECUTING", operation.getName()), e);
    }

    if (!operationUpdateSuccess) {
      logger.log(
          Level.WARNING,
          String.format(
              "Executor::run(%s): could not transition to EXECUTING", operation.getName()));
      putError();
      return 0;
    }

    Duration timeout;
    boolean isDefaultTimeout;
    if (operationContext.action.hasTimeout()) {
      timeout = operationContext.action.getTimeout();
      isDefaultTimeout = false;
    } else {
      timeout = null;
      isDefaultTimeout = true;
    }

    System.out.println("runInterruptible's command:");
    System.out.println(operationContext.command);
    System.out.println("runInterruptible's queueEntry:");
    System.out.println(operationContext.queueEntry);

    if (timeout == null && workerContext.hasDefaultActionTimeout()) {
      timeout = workerContext.getDefaultActionTimeout();
    }

    Deadline pollDeadline;
    if (timeout == null) {
      pollDeadline = Deadline.after(10, DAYS);
    } else {
      pollDeadline =
          Deadline.after(
              // 10s of padding for the timeout in question, so that we can guarantee cleanup
              (timeout.getSeconds() + 10) * 1000000 + timeout.getNanos() / 1000, MICROSECONDS);
    }

    workerContext.resumePoller(
        operationContext.poller,
        "Executor",
        operationContext.queueEntry,
        ExecutionStage.Value.EXECUTING,
        Thread.currentThread()::interrupt,
        pollDeadline);

    try {
      return executePolled(operation, limits, policies, timeout, isDefaultTimeout, stopwatch);
    } finally {
      operationContext.poller.pause();
    }
  }

  private long executePolled(
      Operation operation,
      ResourceLimits limits,
      Iterable<ExecutionPolicy> policies,
      Duration timeout,
      boolean isDefaultTimeout,
      Stopwatch stopwatch)
      throws InterruptedException {
    /* execute command */
    logger.log(Level.FINE, "Executor: Operation " + operation.getName() + " Executing command");

    ActionResult.Builder resultBuilder = operationContext.executeResponse.getResultBuilder();
    resultBuilder
        .getExecutionMetadataBuilder()
        .setExecutionStartTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));

    Command command = operationContext.command;
    Path workingDirectory = operationContext.execDir;
    if (!command.getWorkingDirectory().isEmpty()) {
      workingDirectory = workingDirectory.resolve(command.getWorkingDirectory());
    }

    String operationName = operation.getName();

    ImmutableList.Builder<String> arguments = ImmutableList.builder();
    Code statusCode;
    try (IOResource resource =
        workerContext.limitExecution(
            operationName, arguments, operationContext.command, workingDirectory)) {
      for (ExecutionPolicy policy : policies) {
        if (policy.getPolicyCase() == WRAPPER) {
          arguments.addAll(transformWrapper(policy.getWrapper()));
        }
      }

      if (System.getProperty("os.name").contains("Win")) {
        // Make sure that the executable path is absolute, otherwise processbuilder fails on windows
        Iterator<String> argumentItr = command.getArgumentsList().iterator();
        if (argumentItr.hasNext()) {
          String exe = argumentItr.next(); // Get first element, this is the executable
          arguments.add(workingDirectory.resolve(exe).toAbsolutePath().normalize().toString());
          argumentItr.forEachRemaining(arguments::add);
        }
      } else {
        arguments.addAll(command.getArgumentsList());
      }

      statusCode =
          executeCommand(
              operationName,
              workingDirectory,
              arguments.build(),
              command.getEnvironmentVariablesList(),
              limits,
              timeout,
              isDefaultTimeout,
              // executingMetadata.getStdoutStreamName(),
              // executingMetadata.getStderrStreamName(),
              resultBuilder);

      // From Bazel Test Encyclopedia:
      // If the main process of a test exits, but some of its children are still running,
      // the test runner should consider the run complete and count it as a success or failure
      // based on the exit code observed from the main process. The test runner may kill any stray
      // processes. Tests should not leak processes in this fashion.
      // Based on configuration, we will decide whether remaining resources should be an error.
      if (workerContext.shouldErrorOperationOnRemainingResources()
          && resource.isReferenced()
          && statusCode == Code.OK) {
        // there should no longer be any references to the resource. Any references will be
        // killed upon close, but we must error the operation due to improper execution
        // per the gRPC spec: 'The operation was attempted past the valid range.' Seems
        // appropriate
        statusCode = Code.OUT_OF_RANGE;
        operationContext
            .executeResponse
            .getStatusBuilder()
            .setMessage("command resources were referenced after execution completed");
      }
    } catch (IOException e) {
      logger.log(Level.SEVERE, format("error executing operation %s", operationName), e);
      operationContext.poller.pause();
      putError();
      return 0;
    }

    // switch poller to disable deadline
    operationContext.poller.pause();
    workerContext.resumePoller(
        operationContext.poller,
        "Executor(claim)",
        operationContext.queueEntry,
        ExecutionStage.Value.EXECUTING,
        () -> {},
        Deadline.after(10, DAYS));

    resultBuilder
        .getExecutionMetadataBuilder()
        .setExecutionCompletedTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));
    long executeUSecs = stopwatch.elapsed(MICROSECONDS);

    logger.log(
        Level.INFO,
        String.format(
            "Executor::executeCommand(%s): Completed command: exit code %d",
            operationName, resultBuilder.getExitCode()));

    System.out.println("my thingy!");

    operationContext.executeResponse.getStatusBuilder().setCode(statusCode.getNumber());
    OperationContext reportOperationContext =
        operationContext.toBuilder().setOperation(operation).build();
    boolean claimed = owner.output().claim(reportOperationContext);
    operationContext.poller.pause();
    if (claimed) {
      try {
        owner.output().put(reportOperationContext);
      } catch (InterruptedException e) {
        owner.output().release();
        throw e;
      }
    } else {
      logger.log(Level.FINE, "Executor: Operation " + operationName + " Failed to claim output");
      boolean wasInterrupted = Thread.interrupted();
      try {
        putError();
      } finally {
        if (wasInterrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    return stopwatch.elapsed(MICROSECONDS) - executeUSecs;
  }

  public void run(ResourceLimits limits) {
    long stallUSecs = 0;
    Stopwatch stopwatch = Stopwatch.createStarted();
    String operationName = operationContext.operation.getName();
    try {
      stallUSecs = runInterruptible(stopwatch, limits);
    } catch (InterruptedException e) {
      /* we can be interrupted when the poller fails */
      try {
        putError();
      } catch (InterruptedException errorEx) {
        logger.log(Level.SEVERE, format("interrupted while erroring %s", operationName), errorEx);
      } finally {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      // clear interrupt flag for error put
      boolean wasInterrupted = Thread.interrupted();
      logger.log(Level.SEVERE, format("errored during execution of %s", operationName), e);
      try {
        putError();
      } catch (InterruptedException errorEx) {
        logger.log(
            Level.SEVERE,
            format("interrupted while erroring %s after error", operationName),
            errorEx);
      } catch (Exception errorEx) {
        logger.log(
            Level.SEVERE, format("errored while erroring %s after error", operationName), errorEx);
      }
      if (wasInterrupted) {
        Thread.currentThread().interrupt();
      }
      throw e;
    } finally {
      boolean wasInterrupted = Thread.interrupted();
      try {
        owner.releaseExecutor(
            operationName,
            limits.cpu.claimed,
            stopwatch.elapsed(MICROSECONDS),
            stallUSecs,
            exitCode);
      } finally {
        if (wasInterrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private Iterable<String> transformWrapper(ExecutionWrapper wrapper) {
    ImmutableList.Builder<String> arguments = ImmutableList.builder();

    Map<String, Property> properties =
        uniqueIndex(operationContext.command.getPlatform().getPropertiesList(), Property::getName);

    arguments.add(wrapper.getPath());
    for (String argument : wrapper.getArgumentsList()) {
      // If the argument is of the form <propertyName>, substitute the value of
      // the property from the platform specification.
      if (!argument.equals("<>")
          && argument.charAt(0) == '<'
          && argument.charAt(argument.length() - 1) == '>') {
        // substitute with matching platform property content
        // if this property is not present, the wrapper is ignored
        String propertyName = argument.substring(1, argument.length() - 1);
        Property property = properties.get(propertyName);
        if (property == null) {
          return ImmutableList.of();
        }
        arguments.add(property.getValue());
      } else {
        // If the argument isn't of the form <propertyName>, add the argument directly:
        arguments.add(argument);
      }
    }
    return arguments.build();
  }

  @SuppressWarnings("ConstantConditions")
  private Code executeCommand(
      String operationName,
      Path execDir,
      List<String> arguments,
      List<EnvironmentVariable> environmentVariables,
      ResourceLimits limits,
      Duration timeout,
      boolean isDefaultTimeout,
      ActionResult.Builder resultBuilder)
      throws IOException, InterruptedException {
    ProcessBuilder processBuilder =
        new ProcessBuilder(arguments).directory(execDir.toAbsolutePath().toFile());

    Map<String, String> environment = processBuilder.environment();
    environment.clear();
    for (EnvironmentVariable environmentVariable : environmentVariables) {
      environment.put(environmentVariable.getName(), environmentVariable.getValue());
    }
    for (Map.Entry<String, String> environmentVariable :
        limits.extraEnvironmentVariables.entrySet()) {
      environment.put(environmentVariable.getKey(), environmentVariable.getValue());
    }

    final Write stdoutWrite;
    final Write stderrWrite;

    if ("" != null && !"".isEmpty() && workerContext.getStreamStdout()) {
      stdoutWrite = workerContext.getOperationStreamWrite("");
    } else {
      stdoutWrite = new NullWrite();
    }
    if ("" != null && !"".isEmpty() && workerContext.getStreamStderr()) {
      stderrWrite = workerContext.getOperationStreamWrite("");
    } else {
      stderrWrite = new NullWrite();
    }

    // allow debugging before an execution
    if (limits.debugBeforeExecution) {
      return ExecutionDebugger.performBeforeExecutionDebug(processBuilder, limits, resultBuilder);
    }

    long startNanoTime = System.nanoTime();
    Process process;
    try {
      synchronized (execLock) {
        process = processBuilder.start();
      }
      process.getOutputStream().close();
    } catch (IOException e) {
      logger.log(Level.SEVERE, format("error starting process for %s", operationName), e);
      // again, should we do something else here??
      resultBuilder.setExitCode(INCOMPLETE_EXIT_CODE);
      // The openjdk IOException for an exec failure here includes the working
      // directory of the execution. Drop it and reconstruct without it if we
      // can get the cause.
      Throwable t = e.getCause();
      String message;
      if (t != null) {
        message =
            "Cannot run program \"" + processBuilder.command().get(0) + "\": " + t.getMessage();
      } else {
        message = e.getMessage();
      }
      resultBuilder.setStderrRaw(ByteString.copyFromUtf8(message));
      return Code.INVALID_ARGUMENT;
    }

    stdoutWrite.reset();
    stderrWrite.reset();
    ByteStringWriteReader stdoutReader =
        new ByteStringWriteReader(
            process.getInputStream(), stdoutWrite, (int) workerContext.getStandardOutputLimit());
    ByteStringWriteReader stderrReader =
        new ByteStringWriteReader(
            process.getErrorStream(), stderrWrite, (int) workerContext.getStandardErrorLimit());

    Thread stdoutReaderThread = new Thread(stdoutReader);
    Thread stderrReaderThread = new Thread(stderrReader);
    stdoutReaderThread.start();
    stderrReaderThread.start();

    Code statusCode = Code.OK;
    boolean processCompleted = false;
    try {
      if (timeout == null) {
        exitCode = process.waitFor();
        processCompleted = true;
      } else {
        long timeoutNanos = timeout.getSeconds() * 1000000000L + timeout.getNanos();
        long remainingNanoTime = timeoutNanos - (System.nanoTime() - startNanoTime);
        if (process.waitFor(remainingNanoTime, TimeUnit.NANOSECONDS)) {
          exitCode = process.exitValue();
          processCompleted = true;
        } else {
          logger.log(
              Level.INFO,
              format(
                  "process timed out for %s after %ds with %s timeout",
                  operationName, timeout.getSeconds(), isDefaultTimeout ? "default" : "action"));
          statusCode = Code.DEADLINE_EXCEEDED;
        }
      }
    } finally {
      if (!processCompleted) {
        process.destroy();
        int waitMillis = 1000;
        while (!process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
          logger.log(
              Level.INFO,
              format("process did not respond to termination for %s, killing it", operationName));
          process.destroyForcibly();
          waitMillis = 100;
        }
      }
    }
    stdoutReaderThread.join();
    stderrReaderThread.join();

    try {
      resultBuilder
          .setExitCode(exitCode)
          .setStdoutRaw(stdoutReader.getData())
          .setStderrRaw(stderrReader.getData());
    } catch (IOException e) {
      if (statusCode != Code.DEADLINE_EXCEEDED) {
        throw e;
      }
      logger.log(
          Level.INFO,
          format("error getting process outputs for %s after timeout", operationName),
          e);
    }

    // allow debugging after an execution
    if (limits.debugAfterExecution) {
      // Obtain execution statistics recorded while the action executed.
      // Currently we can only source this data when using the sandbox.
      ExecutionStatistics executionStatistics = ExecutionStatistics.newBuilder().build();
      if (limits.useLinuxSandbox) {
        executionStatistics =
            ExecutionStatistics.newBuilder()
                .mergeFrom(
                    new FileInputStream(execDir.resolve("action_execution_statistics").toString()))
                .build();
      }

      return ExecutionDebugger.performAfterExecutionDebug(
          processBuilder, exitCode, limits, executionStatistics, resultBuilder);
    }

    return statusCode;
  }
}
