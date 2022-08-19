package build.buildfarm.backplane.ops;

import java.io.IOException;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.longrunning.Operation;

import build.bazel.remote.execution.v2.ExecutionStage;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.buildfarm.common.Watcher;
import build.buildfarm.instance.Instance;
import build.buildfarm.operations.FindOperationsResults;
import build.buildfarm.v1test.DispatchedOperation;
import build.buildfarm.v1test.ExecuteEntry;
import build.buildfarm.v1test.QueueEntry;

public interface BackplaneOperations {

  FindOperationsResults findOperations(Instance instance, String filterPredicate)
      throws IOException;

  /**
   * Operations are stored in a hash map where the key is the name of the operation and the value is
   * the actual Operation object.
   *
   * <p>Retrieves and returns an operation from the hash map.
   */
  Operation getOperation(String operationName) throws IOException;

  /**
   * Operations are stored in a hash map where the key is the name of the operation and the value is
   * the actual Operation object.
   *
   * <p>Stores an operation in the hash map.
   */
  boolean putOperation(Operation operation, ExecutionStage.Value stage) throws IOException;

  ExecuteEntry deprequeueOperation() throws IOException, InterruptedException;

  /**
   * The state of operations is tracked in a series of lists representing the order in which the
   * work is to be processed (queued, dispatched, and completed).
   *
   * <p>Moves an operation from the list of queued operations to the list of dispatched operations.
   */
  QueueEntry dispatchOperation(List<Platform.Property> provisions)
      throws IOException, InterruptedException;

  /**
   * Pushes an operation onto the head of the list of queued operations after a rejection which does
   * not require revalidation
   */
  void rejectOperation(QueueEntry queueEntry) throws IOException;

  /**
   * Updates the backplane to indicate that the operation is being queued and should not be
   * considered immediately lost
   */
  void queueing(String operationName) throws IOException;

  /**
   * The state of operations is tracked in a series of lists representing the order in which the
   * work is to be processed (queued, dispatched, and completed).
   *
   * <p>Updates a dispatchedOperation requeue_at and returns whether the operation is still valid.
   */
  boolean pollOperation(QueueEntry queueEntry, ExecutionStage.Value stage, long requeueAt)
      throws IOException;

  /** Complete an operation */
  void completeOperation(String operationName) throws IOException;

  /** Delete an operation */
  void deleteOperation(String operationName) throws IOException;

  /** Register a watcher for an operation */
  ListenableFuture<Void> watchOperation(String operationName, Watcher watcher);

  /** Get all dispatched operations */
  ImmutableList<DispatchedOperation> getDispatchedOperations() throws IOException;

  /** Get all operations */
  Iterable<String> getOperations();

  /** Requeue a dispatched operation */
  void requeueDispatchedOperation(QueueEntry queueEntry) throws IOException;

  void prequeue(ExecuteEntry executeEntry, Operation operation) throws IOException;

  void queue(QueueEntry queueEntry, Operation operation) throws IOException;

  /** Test for whether a request is blacklisted */
  boolean isBlacklisted(RequestMetadata requestMetadata) throws IOException;

  /** Test for whether an operation may be queued */
  boolean canQueue() throws IOException;

  /** Test for whether an operation may be prequeued */
  boolean canPrequeue() throws IOException;

  Boolean propertiesEligibleForQueue(List<Platform.Property> provisions);

}
