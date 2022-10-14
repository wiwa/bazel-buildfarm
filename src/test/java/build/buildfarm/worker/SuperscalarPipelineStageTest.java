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

package build.buildfarm.worker;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.junit.Assert.fail;

import com.google.longrunning.Operation;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SuperscalarPipelineStageTest {
  private static final Logger logger = Logger.getLogger(PipelineStageTest.class.getName());

  static class AbstractSuperscalarPipelineStage extends SuperscalarPipelineStage {
    public AbstractSuperscalarPipelineStage(String name, PipelineStage output, int width) {
      this(name, null, output, null, width);
    }

    public AbstractSuperscalarPipelineStage(
        String name,
        WorkerContext workerContext,
        PipelineStage output,
        PipelineStage error,
        int width) {
      super(name, workerContext, output, error, width);
    }

    @Override
    public Logger getLogger() {
      return logger;
    }

    @Override
    public void put(OperationContext operationContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    OperationContext take() throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void interruptAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected int claimsRequired(OperationContext operationContext) {
      throw new UnsupportedOperationException();
    }

    boolean isFull() {
      return claims.size() == width;
    }
  }

  @Test
  public void interruptedClaimReleasesPartial() throws InterruptedException {
    AbstractSuperscalarPipelineStage stage =
        new AbstractSuperscalarPipelineStage("too-narrow", /* output=*/ null, /* width=*/ 3) {
          @Override
          protected int claimsRequired(OperationContext operationContext) {
            return 5;
          }
        };

    final Thread target = Thread.currentThread();

    Thread interruptor =
        new Thread(
            () -> {
              while (!stage.isFull()) {
                try {
                  MICROSECONDS.sleep(1);
                } catch (InterruptedException e) {
                  // ignore
                }
              }
              target.interrupt();
            });
    interruptor.start();
    // start a thread, when the stage is exhausted, interrupt this one

    try {
      stage.claim(/* operationContext=*/ null);
      fail("should not get here");
    } catch (InterruptedException e) {
      // ignore
    } finally {
      interruptor.join();
      assertThat(stage.isClaimed()).isFalse();
    }
  }

  @Test
  public void takeReleasesQueueClaims() throws InterruptedException {
    OperationContext context =
        OperationContext.newBuilder()
            .setOperation(Operation.newBuilder().setName("operation-in-queue").build())
            .build();
    BlockingQueue<OperationContext> queue = new ArrayBlockingQueue<>(1);
    PipelineStage output = new PipelineStageTest.StubPipelineStage("unclosed-sink");
    PipelineStage stage =
        new AbstractSuperscalarPipelineStage("queue-claimed", output, /* width=*/ 3) {
          @Override
          protected int claimsRequired(OperationContext operationContext) {
            return 2;
          }

          @Override
          public OperationContext take() throws InterruptedException {
            return takeOrDrain(queue);
          }
        };
    stage.claim(context);
    queue.put(context);

    stage.close();
    try {
      stage.take();
      fail("should not get here");
    } catch (InterruptedException e) {
      // ignore
    }
  }
}
