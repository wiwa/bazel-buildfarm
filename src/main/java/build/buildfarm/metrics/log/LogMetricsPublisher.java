// Copyright 2020 The Bazel Authors. All rights reserved.
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

package build.buildfarm.metrics.log;

import build.bazel.remote.execution.v2.RequestMetadata;
import build.buildfarm.metrics.AbstractMetricsPublisher;
import build.buildfarm.v1test.MetricsConfig;
import com.google.longrunning.Operation;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogMetricsPublisher extends AbstractMetricsPublisher {
  private static final Logger logger = Logger.getLogger(LogMetricsPublisher.class.getName());

  private static Level logLevel;

  public LogMetricsPublisher(MetricsConfig metricsConfig) {
    super(metricsConfig.getClusterId());
    if (!metricsConfig.getLogMetricsConfig().getLogLevel().isEmpty()) {
      logLevel = Level.parse(metricsConfig.getLogMetricsConfig().getLogLevel());
    } else {
      logLevel = Level.FINEST;
    }
  }

  public LogMetricsPublisher() {
    super();
  }

  @Override
  public void publishRequestMetadata(Operation operation, RequestMetadata requestMetadata) {
    try {
      logger.log(
          logLevel,
          formatRequestMetadataToJson(populateRequestMetadata(operation, requestMetadata)));
    } catch (Exception e) {
      logger.log(
          Level.WARNING,
          String.format("Could not publish request metadata to LOG for %s.", operation.getName()),
          e);
    }
  }

  @Override
  public void publishMetric(String metricName, Object metricValue) {
    logger.log(Level.INFO, String.format("%s: %s", metricName, metricValue.toString()));
  }
}
