package scala.meta.internal.metals

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Handles file watching of interesting files in this build.
 *
 * Tries to minimize file events by dynamically watching only relevant directories for
 * the structure of the build. We don't use the LSP dynamic file watcher capability because
 *
 * 1. the glob syntax is not defined in the LSP spec making it difficult to deliver a
 *    consistent file watching experience with all editor clients on all operating systems.
 * 2. we may have a lot of file watching events and it's presumably less overhead to
 *    get the notifications directly from the OS instead of through the editor via LSP.
 *
 * Given we rely on file watching for critical functionality like Goto Definition and it's
 * really difficult to reproduce/investigate file watching issues, I think it's best to
 * have a single file watching solution that we have control over.
 *
 * This class does not watch for changes in `*.sbt` files in the workspace directory and
 * in the `project/`. Those notifications are nice-to-have, but not critical. The library we are
 * using https://github.com/gmethvin/directory-watcher only supports recursive directory meaning
 * we would have to watch the workspace directory, resulting in a LOT of redundant file events.
 * Editors are free to send `workspace/didChangedWatchedFiles` notifications for these directories.
 */
final class FileWatcher(
    buildTargets: BuildTargets,
    didChangeWatchedFiles: DirectoryChangeEvent => Unit
) extends Cancelable {

  private val directoryExecutor = Executors.newFixedThreadPool(1)
  private val fileExecutor = Executors.newFixedThreadPool(1)

  ThreadPools.discardRejectedRunnables(
    "FileWatcher.executor",
    directoryExecutor
  )
  ThreadPools.discardRejectedRunnables("FileWatcher.fileExecutor", fileExecutor)

  private var activeDirectoryWatcher: Option[DirectoryWatcher] = None
  private var activeFileWatcher: Option[DirectoryWatcher] = None

  private var directoryWatching: CompletableFuture[Void] =
    new CompletableFuture()
  private var fileWatching: CompletableFuture[Void] = new CompletableFuture()

  override def cancel(): Unit = {
    stopWatching()
    directoryExecutor.shutdown()
    fileExecutor.shutdown()
    activeDirectoryWatcher.foreach(_.close())
    activeFileWatcher.foreach(_.close())
  }

  def restart(): Unit = {
    val sourceDirectoriesToWatch = new util.ArrayList[Path]()
    val sourceFilesToWatch = new util.ArrayList[Path]()
    val createdSourceDirectories = new util.ArrayList[AbsolutePath]()
    def watch(path: AbsolutePath, isSource: Boolean): Unit = {
      if (!path.isDirectory && !path.isFile) {
        val pathToCreate = if (path.isScalaOrJava) {
          AbsolutePath(path.toNIO.getParent())
        } else {
          path
        }
        pathToCreate.createDirectories()
        // this is a workaround for MacOS, it will continue watching
        // directories even if they are removed, however it doesn't
        // work on some other systems like Linux
        if (isSource) createdSourceDirectories.add(pathToCreate)
      }
      if (path.isScalaOrJava) sourceFilesToWatch.add(path.toNIO)
      else sourceDirectoriesToWatch.add(path.toNIO)
    }
    // Watch the source directories for "goto definition" index.
    buildTargets.sourceItems.foreach(watch(_, isSource = true))
    buildTargets.scalacOptions.foreach { item =>
      val targetroot = item.targetroot
      if (!targetroot.isJar) {
        // Watch META-INF/semanticdb directories for "find references" index.
        watch(
          targetroot.resolve(Directories.semanticdb),
          isSource = false
        )
      }
    }
    startWatching(sourceFilesToWatch, sourceDirectoriesToWatch)
    createdSourceDirectories.asScala.foreach(_.delete())
  }

  private def startWatching(
      files: util.List[Path],
      directories: util.List[Path]
  ): Unit = {
    stopWatching()
    val directoryWatcher = DirectoryWatcher
      .builder()
      .paths(directories)
      .listener(new DirectoryListener())
      .build()
    activeDirectoryWatcher = Some(directoryWatcher)
    directoryWatching = directoryWatcher.watchAsync(directoryExecutor)

    val fileWatcher = DirectoryWatcher
      .builder()
      .paths(files.map(_.getParent()))
      .listener(new FileListener(watched = files.asScala.toSet))
      .build()
    activeFileWatcher = Some(fileWatcher)
    fileWatching = fileWatcher.watchAsync(fileExecutor)
  }

  private def stopWatching(): Unit = {
    activeDirectoryWatcher.foreach(_.close())
    activeFileWatcher.foreach(_.close())
    fileWatching.cancel(false)
    directoryWatching.cancel(false)
  }

  class DirectoryListener extends DirectoryChangeListener {
    override def onEvent(event: DirectoryChangeEvent): Unit = {
      if (!Files.isDirectory(event.path())) {
        didChangeWatchedFiles(event)
      }
    }
  }

  class FileListener(watched: Set[Path]) extends DirectoryChangeListener {
    override def onEvent(event: DirectoryChangeEvent): Unit = {
      if (watched(event.path())) {
        didChangeWatchedFiles(event)
      }
    }
  }

}
