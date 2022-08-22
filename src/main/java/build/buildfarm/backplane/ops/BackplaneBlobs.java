package build.buildfarm.backplane.ops;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import build.bazel.remote.execution.v2.Digest;

public interface BackplaneBlobs {

  /**
   * The CAS is represented as a map where the key is the digest of the blob that is being stored
   * and the value is a set of the names of the workers where that blob is stored.
   *
   * <p>Adds the name of a worker to the set of workers that store a blob.
   */
  void addBlobLocation(Digest blobDigest, String workerName) throws IOException;

  /**
   * Remove or add workers to a blob's location set as requested
   */
  void adjustBlobLocations(Digest blobDigest, Set<String> addWorkers, Set<String> removeWorkers)
      throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob that is being stored
   * and the value is a set of the names of the workers where that blob is stored.
   *
   * <p>Adds the name of a worker to the set of workers that store multiple blobs.
   */
  void addBlobsLocation(Iterable<Digest> blobDigest, String workerName) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob that is being stored
   * and the value is a set of the names of the workers where that blob is stored.
   *
   * <p>Removes the name of a worker from the set of workers that store a blob.
   */
  void removeBlobLocation(Digest blobDigest, String workerName) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob that is being stored
   * and the value is a set of the names of the workers where that blob is stored.
   *
   * <p>Removes the name of a worker from the set of workers that store multiple blobs.
   */
  void removeBlobsLocation(Iterable<Digest> blobDigests, String workerName) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob that is being stored
   * and the value is a set of the names of the workers where that blob is stored.
   *
   * <p>Returns a random worker from the set of workers that store a blob.
   */
  String getBlobLocation(Digest blobDigest) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob that is being stored
   * and the value is a set of the names of the workers where that blob is stored.
   *
   * <p>Returns the set of the names of all workers that store a blob.
   */
  Set<String> getBlobLocationSet(Digest blobDigest) throws IOException;

  Map<Digest, Set<String>> getBlobDigestsWorkers(Iterable<Digest> blobDigests) throws IOException;
}
