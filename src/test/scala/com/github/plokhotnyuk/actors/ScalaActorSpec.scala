package com.github.plokhotnyuk.actors

import java.util.concurrent.CountDownLatch
import scala.actors.{SchedulerAdapter, Actor}
import com.github.plokhotnyuk.actors.BenchmarkSpec._

class ScalaActorSpec extends BenchmarkSpec {
  val customScheduler = new SchedulerAdapter {
    val executorService = createExecutorService()

    def execute(f: => Unit): Unit =
      executorService.execute(new Runnable {
        def run(): Unit = f
      })

    override def executeFromActor(task: Runnable): Unit = executorService.execute(task)

    override def execute(task: Runnable): Unit = executorService.execute(task)

    override def shutdown(): Unit = fullShutdown(executorService)

    override def isActive: Boolean = !executorService.isShutdown
  }

  "Single-producer sending" in {
    val n = 800000
    val l = new CountDownLatch(1)
    val a = countActor(l, n)
    timed(n) {
      sendMessages(a, n)
      l.await()
    }
  }

  "Multi-producer sending" in {
    val n = roundToParallelism(800000)
    val l = new CountDownLatch(1)
    val a = countActor(l, n)
    timed(n) {
      for (j <- 1 to parallelism) fork {
        sendMessages(a, n / parallelism)
      }
      l.await()
    }
  }

  "Max throughput" in {
    val n = roundToParallelism(2000000)
    val l = new CountDownLatch(parallelism)
    val as = for (j <- 1 to parallelism) yield countActor(l, n / parallelism)
    timed(n) {
      for (a <- as) fork {
        sendMessages(a, n / parallelism)
      }
      l.await()
    }
  }

  "Ping latency" in {
    ping(400000, 1)
  }

  "Ping throughput 10K" in {
    ping(1200000, 10000)
  }

  "Initiation" in {
    footprintedAndTimedCollect(500000)(() => new Actor {
      def act(): Unit = loop {
        react {
          case _ =>
        }
      }

      override def scheduler = customScheduler
    }.start())
  }

  "Enqueueing" in {
    val n = 1000000
    val l1 = new CountDownLatch(1)
    val l2 = new CountDownLatch(1)
    val a = blockableCountActor(l1, l2, n)
    footprintedAndTimed(n) {
      sendMessages(a, n)
    }
    l1.countDown()
    l2.await()
  }

  "Dequeueing" in {
    val n = 1000000
    val l1 = new CountDownLatch(1)
    val l2 = new CountDownLatch(1)
    val a = blockableCountActor(l1, l2, n)
    sendMessages(a, n)
    timed(n) {
      l1.countDown()
      l2.await()
    }
  }

  def shutdown(): Unit = customScheduler.shutdown()

  private def ping(n: Int, p: Int): Unit = {
    val l = new CountDownLatch(p * 2)
    val as = (1 to p).map(_ => (replayAndCountActor(l, n / p / 2), replayAndCountActor(l, n / p / 2)))
    timed(n, printAvgLatency = p == 1) {
      as.foreach {
        case (a1, a2) => a1.send(Message(), a2)
      }
      l.await()
    }
  }

  private def replayAndCountActor(l: CountDownLatch, n: Int): Actor =
    new Actor {
      private var i = n

      def act(): Unit =
        loop(react {
          case m =>
            if (i > 0) sender ! m
            i -= 1
            if (i == 0) {
              l.countDown()
              exit()
            }
        })

      override def scheduler = customScheduler
    }.start()

  private def blockableCountActor(l1: CountDownLatch, l2: CountDownLatch, n: Int): Actor =
    new Actor {
      private var blocked = true
      private var i = n - 1

      def act(): Unit =
        loop(react {
          case _ =>
            if (blocked) {
              l1.await()
              blocked = false
            } else {
              i -= 1
              if (i == 0) {
                l2.countDown()
                exit()
              }
            }
        })

      override def scheduler = customScheduler
    }.start()

  private def countActor(l: CountDownLatch, n: Int): Actor =
    new Actor {
      private var i = n

      def act(): Unit =
        loop(react {
          case _ =>
            i -= 1
            if (i == 0) {
              l.countDown()
              exit()
            }
        })

      override def scheduler = customScheduler
    }.start()

  private def sendMessages(a: Actor, n: Int): Unit = {
    val m = Message()
    var i = n
    while (i > 0) {
      a ! m
      i -= 1
    }
  }
}
