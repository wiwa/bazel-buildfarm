java_test(
  name = "tests",
  size = "small",
  srcs = glob(["**/*.java"]),
  test_class = "persistent.bazel.PersistentWorkerTest",
  resources = [
    "//persistentworkers/src/test/resources:test-files"
  ],
  deps = [
    "//persistentworkers/src/main/java:persistent-workers",
    "//persistentworkers/src/main/java/persistent/bazel:bazel-persistent-workers",
    "@maven//:com_google_truth_truth",
    "@maven//:org_mockito_mockito_core",
  ],
)
