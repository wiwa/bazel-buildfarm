package persistent;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PersistentCoordinatorTest {

  @SuppressWarnings("CheckReturnValue")
  @Test
  public void simpleTestWorks() throws Exception {

    MapPool<String, PersistentWorker<String, Integer, String>> spool = new MapPool<>(
        key -> new PersistentWorker<String, Integer, String>() {
          @Override
          public String getKey() {
            return key;
          }

          @Override
          public String doWork(Integer request) {
            return request.toString();
          }
        },
        PersistentWorker::getKey
    );

    PersistentCoordinator<String, Integer, String> pc = new PersistentCoordinator<>(spool);

    assertThat(pc.runRequest("asdf", 1))
        .isEqualTo("1");
  }
}
