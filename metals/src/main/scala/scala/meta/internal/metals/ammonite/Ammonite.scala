package scala.meta.internal.metals.ammonite

import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

import ammrunner.AmmoniteFetcher
import ammrunner.AmmoniteFetcherException
import ammrunner.{Command => AmmCommand}
import ammrunner.{Versions => AmmVersions}
import ammrunner.VersionsOption
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Position

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.inputs.Input
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal
import scala.util.Failure
import scala.util.Success

final class Ammonite(
    buffers: Buffers,
    compilers: Compilers,
    compilations: Compilations,
    statusBar: StatusBar,
    diagnostics: Diagnostics,
    doctor: Doctor,
    tables: () => Tables,
    languageClient: MetalsLanguageClient,
    buildClient: ForwardingMetalsBuildClient,
    userConfig: () => UserConfiguration,
    indexWorkspace: () => Future[Unit],
    workspace: () => AbsolutePath,
    focusedDocument: () => Option[AbsolutePath],
    buildTargets: BuildTargets,
    config: MetalsServerConfig
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  def buildServer: Option[BuildServerConnection] =
    buildServer0
  def lastImportedBuild: ImportedBuild =
    lastImportedBuild0

  private var buildServer0 = Option.empty[BuildServerConnection]
  private var lastImportedBuild0 = ImportedBuild.empty
  private var lastImportVersions = VersionsOption(None, None)

  private val cancelables = new MutableCancelable()
  private val isCancelled = new AtomicBoolean(false)
  def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      val buildShutdown = buildServer0 match {
        case Some(build) => build.shutdown()
        case None => Future.unit
      }
      try cancelables.cancel()
      catch {
        case NonFatal(_) =>
      }
      try buildShutdown.asJava.get(100, TimeUnit.MILLISECONDS)
      catch {
        case _: TimeoutException =>
      }
    }
  }

  private def connectToNewBuildServer(
      build: BuildServerConnection
  ): Future[BuildChange] = {
    scribe.info(s"Connected to Ammonite Build server v${build.version}")
    cancelables.add(build)
    compilers.cancel()
    buildServer0 = Some(build)
    val importedBuild0 = // FIXME timed("imported ammonite build") {
      MetalsLanguageServer.importedBuild(build)
    for {
      build0 <- statusBar.trackFuture(
        "Importing Ammonite scripts",
        importedBuild0
      )
      _ = {
        lastImportedBuild0 = build0
      }
      _ <- indexWorkspace()
      // _ = checkRunningBloopVersion(build.version)
      toCompile = buffers.open.toSeq.filter(_.isAmmoniteScript)
      _ <- Future.sequence[Unit, List](
        compilations
          .cascadeCompileFiles(toCompile) ::
          compilers.load(toCompile) ::
          Nil
      )
    } yield BuildChange.Reconnected
  }

  private def disconnectOldBuildServer(): Future[Unit] = {
    if (buildServer0.isDefined)
      scribe.info("disconnected: ammonite build server")
    buildServer0 match {
      case None => Future.unit
      case Some(value) =>
        buildServer0 = None
        lastImportedBuild0 = ImportedBuild.empty
        cancelables.cancel()
        diagnostics.resetAmmoniteScripts()
        value.shutdown()
    }
  }

  private def command(
      path: AbsolutePath
  ): Either[AmmoniteFetcherException, (AmmCommand, AbsolutePath)] = {
    val it = path.toInputFromBuffers(buffers).value.linesIterator
    val versionsOpt = VersionsOption.fromScript(it)
    val versions = versionsOpt
      .orElse(lastImportVersions)
      .getOrElse(
        AmmVersions
          .default()
          .withScalaVersion(BuildInfo.scala212)
      )
    val res = AmmoniteFetcher(versions)
      .withInterpOnly(false)
      .withProgressBars(false)
      .withResolutionParams(
        coursierapi.ResolutionParams
          .create()
          .withScalaVersion(versions.scalaVersion)
      )
      .command()
    res match {
      case Left(e) =>
        scribe.error(
          s"Error getting Ammonite ${versions.ammoniteVersion} (scala ${versions.scalaVersion})",
          e
        )
        Left(e)
      case Right(command) =>
        lastImportVersions = VersionsOption(
          Some(versions.ammoniteVersion),
          Some(versions.scalaVersion)
        )
        Right((command, path))
    }
  }

  def start(doc: Option[AbsolutePath] = None): Future[Unit] = {

    disconnectOldBuildServer().onComplete {
      case Failure(e) =>
        scribe.warn("Error disconnecting old Ammonite build server", e)
      case Success(()) =>
    }

    // TODO Look at buffers.open rather than focusedDocument?
    val commandScriptOpt = focusedDocument() match {
      case None =>
        val msg = "No Ammonite script is opened"
        scribe.error(msg)
        Future.failed(new Exception(msg))
      case Some(path) if path.isAmmoniteScript =>
        Future.fromTry(command(path).toTry)
      case Some(path) =>
        val msg =
          if (path.toNIO.getFileName.toString.endsWith(".worksheet.sc"))
            "Current document is a worksheet, not an Ammonite script"
          else
            "Current document is not an Ammonite script"
        scribe.error(msg)
        Future.failed(new Exception(msg))
    }

    commandScriptOpt.flatMap {
      case (command, script) =>
        val extraScripts = buffers.open.toVector
          .filter(path => path.isAmmoniteScript && path != script)
        val futureConn = BuildServerConnection.fromSockets(
          workspace(),
          buildClient,
          languageClient,
          () =>
            Ammonite
              .socketConn(command, script +: extraScripts, workspace()),
          tables().dismissedNotifications.ReconnectAmmonite,
          config
        )
        for {
          conn <- futureConn
          _ <- connectToNewBuildServer(conn)
        } yield ()
    }
  }

  def stop(): CompletableFuture[Object] = {
    lastImportVersions = VersionsOption(None, None)
    disconnectOldBuildServer().asJavaObject
  }

  def generatedScalaPath(
      targetId: BuildTargetIdentifier,
      source: AbsolutePath
  ): Option[AbsolutePath] =
    if (Ammonite.isAmmBuildTarget(targetId) && source.isAmmoniteScript)
      buildTargets.scalacOptions(targetId).map { target =>
        val rel = source.toRelative(workspace())
        val path = Paths
          .get(new URI(target.getClassDirectory))
          .getParent
          .resolve(
            s"src/ammonite/$$file/${rel.toString.stripSuffix(".sc")}.scala"
          )
        AbsolutePath(path.toAbsolutePath.normalize)
      }
    else
      None

  def generatedScalaInputForPc(
      targetId: BuildTargetIdentifier,
      source: AbsolutePath,
      position: Position
  ): Option[(Input.VirtualFile, Position)] =
    generatedScalaPath(targetId, source)
      .map { scalaPath =>
        val scInput = source.toInputFromBuffers(buffers)
        val input = scalaPath.toInput

        /*

        When given a script like

            case class Bar(xs: Vector[String])

        Ammonite generates a .scala file like

            package ammonite
            package $file

            import _root_.ammonite.interp.api.InterpBridge.{value => interp}

            object `main-1`{
              /*<script>*/case class Bar(xs: Vector[String])/*</script>*/ /*<generated>*/
              def $main() = { scala.Iterator[String]() }
              override def toString = "main$minus1"
              /*</generated>*/
            }

        When the script is being edited, we re-generate on-the-fly a valid .scala file ourselves
        from the one originally generated by Ammonite. The result should be a valid scala file, that
        we can pass to the PC.

        In order to update the .scala file above, we:
        - remove the section between '/*<generated>*/' and '/*</generated>*/'
        - replace the section between '/*<script>*/' and '/*</script>*/' by the new content of the script

         */

        val updatedContent = input.value
          .replaceAllBetween("/*<generated>*/", "/*</generated>*/")("")
          .replaceAllBetween("/*<script>*/", "/*</script>*/")(
            Ammonite.startTag + scInput.value
          )
        val updatedInput = input.copy(value = updatedContent)

        val scriptStartIdx =
          updatedContent.indexOf(Ammonite.startTag) + Ammonite.startTag.length
        val addedLineCount = updatedContent.lineAtIndex(scriptStartIdx)
        val updatedPos =
          new Position(addedLineCount + position.getLine, position.getCharacter)
        (updatedInput, updatedPos)
      }
}

object Ammonite {

  private def startTag: String =
    "/*<start>*/\n"

  def isAmmBuildTarget(id: BuildTargetIdentifier): Boolean =
    id.getUri.endsWith(".sc")

  private def logOutputThread(
      is: InputStream,
      stopSendingOutput: => Boolean
  ): Thread =
    new Thread {
      setDaemon(true)
      val buf = Array.ofDim[Byte](2048)
      override def run(): Unit = {
        var read = 0
        while ({
          !stopSendingOutput && {
            read = is.read(buf)
            read >= 0
          }
        }) {
          if (read > 0) {
            val content =
              new String(buf, 0, read, Charset.defaultCharset())
            scribe.info("Ammonite: " + content)
          }
        }
      }
    }

  private def socketConn(
      command: AmmCommand,
      scripts: Seq[AbsolutePath],
      workspace: AbsolutePath
  )(implicit ec: ExecutionContext): Future[SocketConnection] =
    // meh, blocks on random ec
    Future {
      val proc = command
        .withArgs(Seq("--bsp") ++ scripts.map(_.toNIO.toString))
        .runBg { proc0 =>
          proc0
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .directory(workspace.toFile)
        }
      val os = new ClosableOutputStream(proc.getOutputStream, "Ammonite")
      @volatile var stopSendingOutput = false
      val sendOutput =
        Ammonite.logOutputThread(proc.getErrorStream, stopSendingOutput)
      sendOutput.start()
      val finished = Promise[Unit]()
      Future {
        proc.waitFor()
        finished.success(())
        ()
      }.onComplete {
        case Success(()) =>
        case f @ Failure(exception) => finished.tryComplete(f)
      }
      SocketConnection(
        "Ammonite",
        os,
        proc.getInputStream,
        List(
          Cancelable { () => proc.destroyForcibly() },
          Cancelable { () => stopSendingOutput = true }
        ),
        finished
      )
    }

}
