// Copyright 2019 The Bazel Authors. All rights reserved.
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

package build.buildfarm.common.io;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Directories {
  private static final Logger logger = Logger.getLogger(Directories.class.getName());

  private static final Set<PosixFilePermission> writablePerms =
      PosixFilePermissions.fromString("rwxr-xr-x");
  private static final Set<PosixFilePermission> nonWritablePerms =
      PosixFilePermissions.fromString("r-xr-xr-x");

  private Directories() {}

  private static void makeWritable(Path dir, boolean writable) throws IOException {
    FileStore fileStore = Files.getFileStore(dir);
    if (fileStore.supportsFileAttributeView("posix")) {
      if (writable) {
        Files.setPosixFilePermissions(dir, writablePerms);
      } else {
        Files.setPosixFilePermissions(dir, nonWritablePerms);
      }
    } else if (fileStore.supportsFileAttributeView("acl")) {
      // windows, we hope
      UserPrincipal authenticatedUsers =
          dir.getFileSystem()
              .getUserPrincipalLookupService()
              .lookupPrincipalByName("Authenticated Users");
      AclEntry entry =
          AclEntry.newBuilder()
              .setType(writable ? AclEntryType.ALLOW : AclEntryType.DENY)
              .setPrincipal(authenticatedUsers)
              .setPermissions(
                  AclEntryPermission.DELETE,
                  AclEntryPermission.DELETE_CHILD,
                  AclEntryPermission.ADD_FILE,
                  AclEntryPermission.ADD_SUBDIRECTORY)
              .build();

      AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
      List<AclEntry> acl = view.getAcl();
      acl.add(0, entry);
      view.setAcl(acl);
    } else {
      throw new UnsupportedOperationException("no recognized attribute view");
    }
  }

  public static ListenableFuture<Void> remove(Path path, ExecutorService service) {
    String suffix = UUID.randomUUID().toString();
    Path filename = path.getFileName();
    String tmpFilename = filename + ".tmp." + suffix;
    Path tmpPath = path.resolveSibling(tmpFilename);
    try {
      // MacOS does not permit renames unless the directory is permissioned appropriately
      makeWritable(path, true);
      // rename must be synchronous to call
      Files.move(path, tmpPath);
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }
    return listeningDecorator(service)
        .submit(
            () -> {
              try {
                remove(tmpPath);
              } catch (IOException e) {
                logger.log(Level.SEVERE, "error removing directory " + tmpPath, e);
              }
            },
            null);
  }

  public static void remove(Path directory) throws IOException {
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            makeWritable(dir, true);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) {
              throw e;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  @FunctionalInterface
  public interface DirectoryConsumer {
    void accept(Path dir) throws IOException;
  }

  private static void forAllPostDirs(Path directory, DirectoryConsumer onPostVisit)
      throws IOException {
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) {
              throw e;
            }
            onPostVisit.accept(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  public static void disableAllWriteAccess(Path directory) throws IOException {
    forAllPostDirs(directory, dir -> makeWritable(dir, false));
  }

  public static void enableAllWriteAccess(Path directory) throws IOException {
    forAllPostDirs(directory, dir -> makeWritable(dir, true));
  }

  public static void setAllOwner(Path directory, UserPrincipal owner) throws IOException {
    forAllPostDirs(directory, dir -> Files.setOwner(dir, owner));
  }
}
