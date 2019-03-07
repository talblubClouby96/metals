package scala.meta.internal.pc

import java.util.Collections
import scala.annotation.tailrec

object CompilerThrowable {
  def trimStackTrace(e: Throwable): Unit = {
    val isVisited = Collections.newSetFromMap(
      new java.util.IdentityHashMap[Throwable, java.lang.Boolean]
    )
    @tailrec def loop(ex: Throwable): Unit = {
      isVisited.add(ex)
      val stacktrace =
        ex.getStackTrace.takeWhile(!_.getClassName.contains("ScalaPC"))
      ex.setStackTrace(stacktrace)
      // avoid infinite loop when traversing exceptions cyclic dependencies between causes.
      if (e.getCause != null && !isVisited.contains(e.getCause)) {
        loop(e.getCause)
      }
    }
    loop(e)
  }
}
