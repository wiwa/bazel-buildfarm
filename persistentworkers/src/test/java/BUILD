java_test(
  name = "tests",
  size = "small",
  srcs = glob(["**/*.java"]),
  test_class = "persistent.PersistentCoordinatorTest",
  deps = [
    "//persistentworkers/src/main/java:persistent-workers",
    "//third_party/bazel/src/main/protobuf:worker_protocol_java_proto",
    "@maven//:com_google_truth_truth",
    "@maven//:org_mockito_mockito_core",
  ],
)
