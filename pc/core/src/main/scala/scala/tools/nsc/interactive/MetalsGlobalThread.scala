package scala.tools.nsc.interactive

import scala.meta.internal.pc.Cancellation

/**
 * Adapation of PresentationCompilerThread from scala/scala.
 *
 * Changes are marked with "+- scalac deviation" comments.
 */
final class MetalsGlobalThread(var compiler: Global, name: String = "")
    extends Thread("Scala Presentation Compiler [" + name + "]") {

  /** The presentation compiler loop.
   */
  override def run() {
    compiler.debugLog("starting new runner thread")
    while (compiler ne null) try {
      compiler.checkNoResponsesOutstanding()
      compiler.log.logreplay("wait for more work", {
        compiler.scheduler.waitForMoreWork(); true
      })
      compiler.pollForWork(compiler.NoPosition)
      while (compiler.isOutOfDate) {
        try {
          compiler.backgroundCompile()
        } catch {
          case ex: FreshRunReq =>
            compiler.debugLog("fresh run req caught, starting new pass")
        }
        compiler.log.flush()
      }
    } catch {
      case ex @ ShutdownReq =>
        compiler.debugLog("exiting presentation compiler")
        compiler.log.close()

        // make sure we don't keep around stale instances
        compiler = null
      case ex: Throwable =>
        compiler.log.flush()

        ex match {
          // + scalac deviation
          case Cancellation() =>
            Thread.interrupted()
          // - scalac deviation
          case ex: FreshRunReq =>
            compiler.debugLog(
              "fresh run req caught outside presentation compiler loop; ignored"
            ) // This shouldn't be reported
          case _: Global#ValidateException => // This will have been reported elsewhere
            compiler.debugLog(
              "validate exception caught outside presentation compiler loop; ignored"
            )
          case _ =>
            ex.printStackTrace(); compiler.informIDE("Fatal Error: " + ex)
        }
    }
  }
}
