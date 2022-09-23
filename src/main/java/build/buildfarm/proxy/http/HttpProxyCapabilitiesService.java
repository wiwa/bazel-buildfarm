package build.buildfarm.proxy.http;

import build.bazel.remote.execution.v2.CapabilitiesGrpc;
import build.bazel.remote.execution.v2.GetCapabilitiesRequest;
import build.bazel.remote.execution.v2.ServerCapabilities;
import io.grpc.stub.StreamObserver;

public class HttpProxyCapabilitiesService extends CapabilitiesGrpc.CapabilitiesImplBase {

  public HttpProxyCapabilitiesService() {}

  @Override
  public void getCapabilities(
      GetCapabilitiesRequest request, StreamObserver<ServerCapabilities> responseObserver) {
    responseObserver.onNext(
        HttpProxy.capabilities
    );
    responseObserver.onCompleted();
  }
}
