package persistent.common.processes;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import persistent.common.KeyedWorker;
import persistent.common.MapPool;
import persistent.common.Coordinator;

@RunWith(JUnit4.class)
public class CoordinatorTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {

    MapPool<String, KeyedWorker<String, Integer, String>> spool = new MapPool<>(
        key -> new KeyedWorker<String, Integer, String>() {
          @Override
          public String getKey() {
            return key;
          }

          @Override
          public String doWork(Integer request) {
            return request.toString();
          }
        },
        KeyedWorker::getKey
    );

    Coordinator<String, Integer, String, KeyedWorker<String, Integer, String>> pc =
        Coordinator.simple(spool);

    assertThat(pc.runRequest("asdf", 1))
        .isEqualTo("1");
  }
}
