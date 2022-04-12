package persistent.common.processes;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import persistent.common.KeyedWorker;
import persistent.common.MapPool;
import persistent.common.PersistentCoordinator;

@RunWith(JUnit4.class)
public class PersistentCoordinatorTest {

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

    PersistentCoordinator<String, Integer, String> pc = new PersistentCoordinator<>(spool);

    assertThat(pc.runRequest("asdf", 1))
        .isEqualTo("1");
  }
}
