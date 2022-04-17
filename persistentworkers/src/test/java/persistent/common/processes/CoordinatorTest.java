package persistent.common.processes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import persistent.common.Coordinator;
import persistent.common.Coordinator.SimpleCoordinator;
import persistent.common.MapPool;
import persistent.common.Worker;
import persistent.common.CtxAround.Id;

import static com.google.common.truth.Truth.assertThat;

@RunWith(JUnit4.class)
public class CoordinatorTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {

    MapPool<String, Worker<Integer, String>> spool = new MapPool<>(key -> String::valueOf);

    SimpleCoordinator<String, Integer, String, Worker<Integer, String>> pc =
        Coordinator.simple(spool);

    assertThat(pc.runRequest("asdf", Id.of(1)))
        .isEqualTo("1");
  }
}
