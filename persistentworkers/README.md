Persistent Workers, mostly ripped from Bazel
===
___

General Idea:
---

```
(Executor) --> Coordinator --> Pool { WorkerKey: Worker }  

Worker { ProcessWrapper, ProtoRW } <--> { ProtoRW, WorkRequestHandler } SomeActionHandler
```
