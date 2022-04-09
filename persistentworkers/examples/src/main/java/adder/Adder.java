package adder;

import java.util.List;

import persistent.bazel.WorkRequestHandler;

public class Adder {

  public static void main(String[] args) {
    if (args.length == 1 && args[0].equals("--persistent_worker")) {
      WorkRequestHandler handler = initialize();
      int exitCode = handler.processForever(System.in, System.out, System.err);
      System.exit(exitCode);
    }
  }

  private static WorkRequestHandler initialize() {
    return new WorkRequestHandler((actionArgs, pw) -> {
      int res = work(actionArgs);
      pw.write(res);
      return 0;
    });
  }

  private static int work(List<String> args) {
    int a = Integer.parseInt(args.get(0));
    int b = Integer.parseInt(args.get(1));
    return compute(a, b);
  }

  private static int compute(int a, int b) {
    return a + b;
  }
}
