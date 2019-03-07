package scala.meta.internal.metals

import java.util
import java.lang.{Iterable => JIterable}
import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * In-memory cache for looking up build server metadata.
 */
final class BuildTargets() {
  private val sourceDirectoriesToBuildTarget =
    TrieMap.empty[AbsolutePath, ConcurrentLinkedQueue[BuildTargetIdentifier]]
  private val buildTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, BuildTarget]
  private val scalacTargetInfo =
    TrieMap.empty[BuildTargetIdentifier, ScalacOptionsItem]
  private val inverseDependencies =
    TrieMap.empty[BuildTargetIdentifier, ListBuffer[BuildTargetIdentifier]]
  private val buildTargetSources =
    TrieMap.empty[BuildTargetIdentifier, util.Set[AbsolutePath]]
  private val inverseDependencySources =
    TrieMap.empty[AbsolutePath, BuildTargetIdentifier]

  def reset(): Unit = {
    sourceDirectoriesToBuildTarget.values.foreach(_.clear())
    sourceDirectoriesToBuildTarget.clear()
    buildTargetInfo.clear()
    scalacTargetInfo.clear()
    inverseDependencies.clear()
    buildTargetSources.clear()
    inverseDependencySources.clear()
  }
  def sourceDirectories: Iterable[AbsolutePath] =
    sourceDirectoriesToBuildTarget.keys
  def sourceDirectoriesToBuildTargets
    : Iterator[(AbsolutePath, JIterable[BuildTargetIdentifier])] =
    sourceDirectoriesToBuildTarget.iterator
  def scalacOptions: Iterable[ScalacOptionsItem] =
    scalacTargetInfo.values

  def all: Iterator[ScalaTarget] =
    for {
      (id, target) <- buildTargetInfo.iterator
      scalac <- scalacTargetInfo.get(id)
    } yield ScalaTarget(target, scalac)

  def addSourceDirectory(
      directory: AbsolutePath,
      buildTarget: BuildTargetIdentifier
  ): Unit = {
    val queue = sourceDirectoriesToBuildTarget.getOrElseUpdate(
      directory,
      new ConcurrentLinkedQueue()
    )
    queue.add(buildTarget)
  }

  def onCreate(source: AbsolutePath): Unit = {
    for {
      buildTarget <- sourceBuildTargets(source)
    } {
      linkSourceFile(buildTarget, source)
    }
  }

  def buildTargetTransitiveSources(
      id: BuildTargetIdentifier
  ): Iterator[AbsolutePath] = {
    for {
      dependency <- buildTargetTransitiveDependencies(id).iterator
      sources <- buildTargetSources.get(dependency).iterator
      source <- sources.asScala.iterator
    } yield source
  }

  def buildTargetTransitiveDependencies(
      id: BuildTargetIdentifier
  ): Iterable[BuildTargetIdentifier] = {
    val isVisited = mutable.Set.empty[BuildTargetIdentifier]
    val toVisit = new java.util.ArrayDeque[BuildTargetIdentifier]
    toVisit.add(id)
    while (!toVisit.isEmpty) {
      val next = toVisit.pop()
      if (!isVisited(next)) {
        isVisited.add(next)
        for {
          info <- info(next).iterator
          dependency <- info.getDependencies.asScala.iterator
        } {
          toVisit.add(dependency)
        }
      }
    }
    isVisited
  }

  def linkSourceFile(id: BuildTargetIdentifier, source: AbsolutePath): Unit = {
    val set = buildTargetSources.getOrElseUpdate(id, ConcurrentHashSet.empty)
    set.add(source)
  }

  def addWorkspaceBuildTargets(result: WorkspaceBuildTargetsResult): Unit = {
    result.getTargets.asScala.foreach { target =>
      buildTargetInfo(target.getId) = target
      target.getDependencies.asScala.foreach { dependency =>
        val buf =
          inverseDependencies.getOrElseUpdate(dependency, ListBuffer.empty)
        buf += target.getId
      }
    }
  }

  def addScalacOptions(result: ScalacOptionsResult): Unit = {
    result.getItems.asScala.foreach { item =>
      scalacTargetInfo(item.getTarget) = item
    }
  }

  def info(
      buildTarget: BuildTargetIdentifier
  ): Option[BuildTarget] =
    buildTargetInfo.get(buildTarget)

  def scalacOptions(
      buildTarget: BuildTargetIdentifier
  ): Option[ScalacOptionsItem] =
    scalacTargetInfo.get(buildTarget)

  /**
   * Returns the first build target containing this source file.
   */
  def inverseSources(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    val buildTargets = sourceBuildTargets(source)
    buildTargets // prioritize JVM targets over JS/Native
      .find(x => scalacOptions(x).exists(_.isJVM))
      .orElse(buildTargets.headOption)
  }

  def sourceBuildTargets(
      source: AbsolutePath
  ): Iterable[BuildTargetIdentifier] = {
    sourceDirectoriesToBuildTarget
      .collectFirst {
        case (sourceDirectory, buildTargets)
            if source.toNIO.startsWith(sourceDirectory.toNIO) =>
          buildTargets.asScala
      }
      .getOrElse(Iterable.empty)
  }

  def inverseSourceDirectory(source: AbsolutePath): Option[AbsolutePath] =
    sourceDirectories.find(dir => source.toNIO.startsWith(dir.toNIO))

  def isInverseDependency(
      query: BuildTargetIdentifier,
      roots: List[BuildTargetIdentifier]
  ): Boolean = {
    BuildTargets.isInverseDependency(query, roots, inverseDependencies.get)
  }
  def inverseDependencies(
      target: BuildTargetIdentifier
  ): collection.Set[BuildTargetIdentifier] = {
    BuildTargets.inverseDependencies(List(target), inverseDependencies.get)
  }

  def addDependencySource(
      sourcesJar: AbsolutePath,
      target: BuildTargetIdentifier
  ): Unit = {
    inverseDependencySources(sourcesJar) = target
  }

  def inverseDependencySource(
      sourceJar: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    inverseDependencySources.get(sourceJar)
  }

}

object BuildTargets {
  def isInverseDependency(
      query: BuildTargetIdentifier,
      roots: List[BuildTargetIdentifier],
      inverseDeps: BuildTargetIdentifier => Option[Seq[BuildTargetIdentifier]]
  ): Boolean = {
    val isVisited = mutable.Set.empty[BuildTargetIdentifier]
    @tailrec
    def loop(toVisit: List[BuildTargetIdentifier]): Boolean = toVisit match {
      case Nil => false
      case head :: tail =>
        if (head == query) true
        else if (isVisited(head)) false
        else {
          isVisited += head
          inverseDeps(head) match {
            case Some(next) =>
              loop(next.toList ++ tail)
            case None =>
              loop(tail)
          }
        }
    }
    loop(roots)
  }

  /**
   * Given an acyclic graph and a root target, returns the leaf nodes that depend on the root target.
   *
   * For example, returns `[D, E, C]` given the following graph with root A: {{{
   *      A
   *    ^   ^
   *    |   |
   *    B   C
   *   ^ ^
   *   | |
   *   D E
   * }}}
   */
  def inverseDependencies(
      root: List[BuildTargetIdentifier],
      inverseDeps: BuildTargetIdentifier => Option[Seq[BuildTargetIdentifier]]
  ): collection.Set[BuildTargetIdentifier] = {
    val isVisited = mutable.Set.empty[BuildTargetIdentifier]
    val result = mutable.Set.empty[BuildTargetIdentifier]
    def loop(toVisit: List[BuildTargetIdentifier]): Unit = toVisit match {
      case Nil => ()
      case head :: tail =>
        if (!isVisited(head)) {
          isVisited += head
          inverseDeps(head) match {
            case Some(next) =>
              loop(next.toList)
            case None =>
              // Only add leaves of the tree to the result to minimize the number
              // of targets that we compile. If `B` depends on `A`, it's faster
              // in Bloop to compile only `B` than `A+B`.
              result += head
          }
          loop(tail)
        }
    }
    loop(root)
    result
  }

}
