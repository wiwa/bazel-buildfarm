package build.buildfarm.backplane.ops;

import java.io.IOException;
import java.util.Set;

import build.buildfarm.v1test.ShardWorker;

public interface BackplaneNodes {

  /** Adds a worker to the set of active workers. */
  void addWorker(ShardWorker shardWorker) throws IOException;

  /**
   * Removes a worker's name from the set of active workers.
   *
   * <p>Return true if the worker was removed, and false if it was not a member of the set.
   */
  boolean removeWorker(String workerName, String reason) throws IOException;

  void deregisterWorker(String hostName) throws IOException;

  /** Returns a set of the names of all active workers. */
  Set<String> getWorkers() throws IOException;
}
