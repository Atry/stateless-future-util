/*
 * stateless-future
 * Copyright 2014 深圳岂凡网络有限公司 (Shenzhen QiFun Network Corp., LTD)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qifun.statelessFuture

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.Exception.Catcher
import scala.util.control.TailCalls._
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.util.concurrent.CancellationException

private object CancelableFuture {

  private implicit class Scala210TailRec[A](underlying: TailRec[A]) {
    final def flatMap[B](f: A => TailRec[B]): TailRec[B] = {
      tailcall(f(underlying.result))
    }
  }

}

/**
 * A [[Future.Stateful]] that will be completed when another [[Future]] being completed.
 * @param stateReference The internal stateReference that should never be accessed by other modules.
 */
trait CancelableFuture[AwaitResult]
  extends Any with Future.Stateful[AwaitResult] {

  // 为了能在Scala 2.10中编译通过
  import CancelableFuture.Scala210TailRec

  private type CancelFunction = () => Unit

  private type State = Either[(CancelFunction, List[(AwaitResult => TailRec[Unit], Catcher[TailRec[Unit]])]), Try[AwaitResult]]

  // TODO: 把List和Tuple2合并成一个对象，以减少内存占用
  protected def stateReference: AtomicReference[State]

  final def cancel() {
    stateReference.get match {
      case oldState @ Left((cancelFunction, handlers)) => {
        val value = Failure(new CancellationException)
        if (stateReference.compareAndSet(oldState, Right(value))) {
          cancelFunction()
          tailcall(dispatch(handlers, value))
        } else {
          cancel()
        }
      }
      case _ => {
        // Ignore
      }
    }
  }

  private def dispatch(handlers: List[(AwaitResult => TailRec[Unit], Catcher[TailRec[Unit]])], value: Try[AwaitResult]): TailRec[Unit] = {
    handlers match {
      case Nil => done(())
      case (body, catcher) :: tail => {
        (value match {
          case Success(a) => {
            body(a)
          }
          case Failure(e) => {
            if (catcher.isDefinedAt(e)) {
              catcher(e)
            } else {
              throw e
            }
          }
        }).flatMap { _ =>
          dispatch(tail, value)
        }
      }
    }
  }

  override final def value = stateReference.get.right.toOption

  // @tailrec // Comment this because of https://issues.scala-lang.org/browse/SI-6574
  protected final def complete(value: Try[AwaitResult]): TailRec[Unit] = {
    stateReference.get match {
      case oldState @ Left((cancel, handlers)) => {
        if (stateReference.compareAndSet(oldState, Right(value))) {
          tailcall(dispatch(handlers, value))
        } else {
          complete(value)
        }
      }
      case Right(origin) => {
        throw new IllegalStateException("Cannot complete a CancelablePromise twice!")
      }
    }
  }

  /**
   * Starts a waiting operation that will be completed when `other` being completed.
   * @throws java.lang.IllegalStateException Passed to `catcher` when this [[CancelablePromise]] being completed more once.
   * @usecase def completeWith(other: Future[AwaitResult]): TailRec[Unit] = ???
   */
  protected final def completeWith[OriginalAwaitResult](other: Future[OriginalAwaitResult])(implicit view: OriginalAwaitResult => AwaitResult): TailRec[Unit] = {
    other.onComplete { b =>
      val value = Success(view(b))
      tailcall(complete(value))
    } {
      case e: Throwable => {
        val value = Failure(e)
        tailcall(complete(value))
      }
    }
  }

  // @tailrec // Comment this annotation because of https://issues.scala-lang.org/browse/SI-6574
  protected final def tryComplete(value: Try[AwaitResult]): TailRec[Unit] = {
    stateReference.get match {
      case oldState @ Left((cancel, handlers)) => {
        if (stateReference.compareAndSet(oldState, Right(value))) {
          tailcall(dispatch(handlers, value))
        } else {
          tryComplete(value)
        }
      }
      case Right(origin) => {
        done(())
      }
    }
  }

  /**
   * Starts a waiting operation that will be completed when `other` being completed.
   * Unlike [[completeWith]], no exception will be created when this [[CancelablePromise]] being completed more once.
   * @usecase def tryCompleteWith(other: Future[AwaitResult]): TailRec[Unit] = ???
   */
  protected final def tryCompleteWith[OriginalAwaitResult](other: Future[OriginalAwaitResult])(implicit view: OriginalAwaitResult => AwaitResult): TailRec[Unit] = {
    other.onComplete { b =>
      val value = Success(view(b))
      tailcall(tryComplete(value))
    } {
      case e: Throwable => {
        val value = Failure(e)
        tailcall(tryComplete(value))
      }
    }
  }

  // @tailrec // Comment this annotation because of https://issues.scala-lang.org/browse/SI-6574
  override final def onComplete(body: AwaitResult => TailRec[Unit])(implicit catcher: Catcher[TailRec[Unit]]): TailRec[Unit] = {
    stateReference.get match {
      case Right(value) => {
        value match {
          case Success(a) => {
            body(a)
          }
          case Failure(e) => {
            if (catcher.isDefinedAt(e)) {
              catcher(e)
            } else {
              throw e
            }
          }
        }
      }
      case oldState @ Left((cancel, tail)) => {
        if (stateReference.compareAndSet(oldState, Left((cancel, (body, catcher) :: tail)))) {
          done(())
        } else {
          onComplete(body)
        }
      }
    }
  }

}