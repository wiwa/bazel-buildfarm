java_test(
  name = "tests",
  size = "small",
  srcs = glob(["**/*.java"]),
  test_class = "adder.AdderTest",
  deps = [
    "//persistentworkers/examples/src/main/java:adder-lib",
    "//persistentworkers/src/main/java:persistent-workers",
    "//persistentworkers/src/main/java/persistent/bazel:bazel-persistent-workers",
    "//persistentworkers/src/test/java/persistent/bazel/testutil:testutil",
    "//third_party/bazel/src/main/protobuf:worker_protocol_java_proto",
    "@maven//:com_google_truth_truth",
    "@maven//:org_mockito_mockito_core",
  ],
)
