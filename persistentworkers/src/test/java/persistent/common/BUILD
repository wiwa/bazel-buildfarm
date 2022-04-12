java_test(
  name = "PersistentCoordinatorTest",
  size = "small",
  srcs = glob(["**/*.java"]),
  deps = [
    "//persistentworkers/src/main/java/persistent/common:persistent-common",
    "//persistentworkers/src/test/java/persistent/testutil:testutil",
    "//third_party/bazel/src/main/protobuf:worker_protocol_java_proto",
    "@maven//:com_google_truth_truth",
    "@maven//:commons_io_commons_io",
    "@maven//:org_mockito_mockito_core",
  ],
)
