package build.buildfarm.common.gencache;

public class Timestamp {
  public Long getMillis() {
    return System.currentTimeMillis();
  };

  public Long getNanos() {
    return System.nanoTime();
  };
}
