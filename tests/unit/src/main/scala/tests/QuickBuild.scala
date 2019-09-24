package tests

import bloop.config.ConfigEncoderDecoders._
import bloop.config.{Config => C}
import com.geirsson.{coursiersmall => s}
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.MalformedJsonException
import io.circe.syntax._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath
import scala.util.matching.Regex

/**
 * A basic build tool for faster testing.
 *
 * Spending 30 seconds for every `sbt bloopInstall` to run a basic test
 * makes it annoying to work on the Metals codebase because tests will be too slow.
 * QuickBuild is a basic build tool build on top of coursier+bloop and
 * generates Bloop JSON files in a few seconds (when artifacts are cached)
 * compared to 30s with sbt. The speedups are significant when you have multiple
 * test cases:
 *
 * - Time to run 5 sbt integration tests: 136s
 * - Time to run 5 metals.json integration tests: 12s
 *
 * A build is declared in metals.json and looks like this: {{{
 *   {
 *     "id": {
 *       "scalaVersion": "2.12.10",
 *       "libraryDependencies": [
 *         "org.scalatest::scalatest:3.0.5",
 *       ],
 *       "compilerPlugins": [
 *         "org.scalameta:::semanticdb-scalac:4.0.0"
 *       ],
 *       scalacOptions: [
 *         "-deprecation"
 *       ],
 *       dependsOn: [ "id2" ]
 *     },
 *    "id2": { ... }
 *   }
 * }}}
 */
case class QuickBuild(
    id: String,
    scalaVersion: String,
    libraryDependencies: Array[String],
    compilerPlugins: Array[String],
    scalacOptions: Array[String],
    dependsOn: Array[String],
    additionalSources: Array[String]
) {
  def withId(id: String): QuickBuild =
    QuickBuild(
      id,
      if (scalaVersion == null) V.scala212
      else scalaVersion,
      orEmpty(libraryDependencies),
      orEmpty(compilerPlugins),
      orEmpty(scalacOptions),
      orEmpty(dependsOn),
      orEmpty(additionalSources)
    )
  private def orEmpty(array: Array[String]): Array[String] =
    if (array == null) new Array(0) else array
  def scalaBinaryVersion: String =
    scalaVersion.split("\\.").take(2).mkString(".")
  def toBloop(workspace: AbsolutePath): C.Project = {
    val baseDirectory: Path = workspace.resolve(id).toNIO
    val binaryVersion: String = scalaBinaryVersion
    val out: Path = workspace.resolve(".bloop").resolve(id).toNIO
    val classDirectory: Path = {
      val isTest = id.endsWith("-test")
      val testPrefix = if (isTest) "test-" else ""
      out
        .resolve(s"scala-$binaryVersion")
        .resolve(s"${testPrefix}classes")
    }
    val extraSources =
      additionalSources.map(relpath => workspace.resolve(relpath).toNIO).toList
    val sources = extraSources ::: List(
      "src/main/java",
      "src/main/scala",
      s"src/main/scala-$binaryVersion",
      s"src/main/scala-$binaryVersion"
    ).map(relpath => baseDirectory.resolve(relpath))
    val allDependencies = Array(
      s"org.scala-lang:scala-library:$scalaVersion",
      s"org.scala-lang:scala-reflect:$scalaVersion"
    ) ++ libraryDependencies
    val allJars = classDirectory :: QuickBuild.fetch(
      allDependencies,
      scalaVersion,
      binaryVersion,
      sources = true
    )
    val (dependencySources, classpath) =
      allJars.partition(_.getFileName.toString.endsWith("-sources.jar"))
    val allPlugins =
      if (ScalaVersions.isSupportedScalaVersion(scalaVersion))
        s"org.scalameta:::semanticdb-scalac:${V.semanticdbVersion}" :: compilerPlugins.toList
      else compilerPlugins.toList
    val pluginDependencies = allPlugins.map(
      plugin =>
        QuickBuild
          .toDependency(plugin, scalaVersion, binaryVersion)
          .withTransitive(false)
    )
    val pluginJars = QuickBuild.fetchDependencies(pluginDependencies)
    val plugins = pluginJars.map(jar => s"-Xplugin:$jar")
    val cache =
      if (scalaVersion == V.scala212)
        List("-Ycache-plugin-class-loader:last-modified")
      else List()
    val allScalacOptions = List(
      List(
        "-Yrangepos",
        s"-Xplugin-require:semanticdb",
        s"-P:semanticdb:failures:warning",
        s"-P:semanticdb:synthetics:on",
        s"-P:semanticdb:sourceroot:$workspace",
        s"-P:semanticdb:targetroot:$classDirectory"
      ),
      plugins,
      cache,
      scalacOptions.toList
    ).flatten
    val resolution = dependencySources.map { jar =>
      C.Module(
        "",
        "",
        "",
        None,
        artifacts = List(
          C.Artifact(
            "",
            classifier = Some("sources"),
            None,
            path = jar
          )
        )
      )
    }
    val javaHome = Option(System.getProperty("java.home")).map(Paths.get(_))
    C.Project(
      id,
      baseDirectory,
      Some(workspace.toNIO),
      sources,
      dependsOn.toList,
      classpath,
      out,
      classDirectory,
      scala = Some(
        C.Scala(
          "org.scala-lang",
          "scala-compiler",
          scalaVersion,
          allScalacOptions,
          QuickBuild.fetch(
            Array(
              s"org.scala-lang:scala-compiler:$scalaVersion",
              "jline:jline:2.14.6"
            ),
            scalaVersion,
            binaryVersion
          ),
          None,
          setup = Some(
            C.CompileSetup(
              C.Mixed,
              addLibraryToBootClasspath = true,
              addCompilerToClasspath = false,
              addExtraJarsToClasspath = false,
              manageBootClasspath = true,
              filterLibraryFromClasspath = true
            )
          )
        )
      ),
      java = Some(C.Java(Nil)),
      sbt = None,
      test = None,
      platform = Some(C.Platform.Jvm(C.JvmConfig(javaHome, Nil), None)),
      resolution = Some(C.Resolution(resolution)),
      resources = None
    )
  }
}

object QuickBuild {

  /**
   * Bump up this version in case the JSON generation algorithm changes
   * A new version triggers re-generation of QuickBuild files.
   */
  val version = "v3"
  def toDependency(
      module: String,
      scalaVersion: String,
      scalaBinaryVersion: String
  ): s.Dependency = module match {
    case Full(org, name, version) =>
      new s.Dependency(org, s"${name}_$scalaVersion", version)
    case Half(org, name, version) =>
      new s.Dependency(org, s"${name}_$scalaBinaryVersion", version)
    case Java(org, name, version) =>
      new s.Dependency(org, name, version)
    case _ =>
      throw new IllegalArgumentException(module)
  }
  def fetch(
      dependencies: Array[String],
      scalaVersion: String,
      scalaBinaryVersion: String,
      sources: Boolean = false
  ): List[Path] = {
    fetchDependencies(
      dependencies.iterator
        .map(d => toDependency(d, scalaVersion, scalaBinaryVersion))
        .toList,
      sources
    )
  }
  def fetchDependencies(
      dependencies: List[s.Dependency],
      sources: Boolean = false
  ): List[Path] = {
    val settings = new s.Settings()
      .withDependencies(dependencies)
      .withClassifiers(if (sources) List("sources", "_") else List())
    s.CoursierSmall.fetch(settings)
  }
  val Full: Regex = "(.+):::(.+):(.+)".r
  val Half: Regex = "(.+)::(.+):(.+)".r
  val Java: Regex = "(.+):(.+):(.+)".r
  def parseJson(text: String): JsonObject = {
    try new JsonParser().parse(text).getAsJsonObject
    catch {
      case _: MalformedJsonException =>
        val Left(e) = io.circe.parser.parse(text)
        throw e
    }
  }

  def newDigest(workspace: AbsolutePath): Option[(AbsolutePath, String)] = {
    val digestFile =
      workspace.resolve(".metals").resolve("quick-build.md5")
    val oldDigest =
      if (digestFile.isFile) FileIO.slurp(digestFile, StandardCharsets.UTF_8)
      else "unknown"
    val newDigest = {
      val digest = MessageDigest.getInstance("MD5")
      digest.update(version.getBytes(StandardCharsets.UTF_8))
      digest.update(V.scala212.getBytes(StandardCharsets.UTF_8))
      def update(file: AbsolutePath): Unit = {
        if (file.isFile) {
          digest.update(file.readAllBytes)
        }
      }
      update(workspace.resolve("metals.json"))
      val bloopDirectory = workspace.resolve(".bloop").toNIO
      Files.createDirectories(bloopDirectory)
      AbsolutePath(bloopDirectory).list
        .filter(_.extension == "json")
        .foreach(json => update(json))
      MD5.bytesToHex(digest.digest())
    }
    if (oldDigest == newDigest) None
    else Some(digestFile -> newDigest)
  }

  def bloopInstall(workspace: AbsolutePath): Unit = {
    val json = workspace.resolve("metals.json")
    if (json.isFile) {
      newDigest(workspace) match {
        case None =>
        case Some((digestFile, digest)) =>
          val timer = new Timer(Time.system)
          val gson = new Gson()
          val text = FileIO.slurp(json, StandardCharsets.UTF_8)
          val obj = parseJson(text)
          val projects = obj.entrySet().asScala.map { entry =>
            val project =
              gson.fromJson[QuickBuild](entry.getValue, classOf[QuickBuild])
            project.withId(entry.getKey)
          }
          val bloopDirectory = workspace.resolve(".bloop").toNIO
          Files.createDirectories(bloopDirectory)
          AbsolutePath(bloopDirectory).list
            .filter(_.extension == "json")
            .foreach(json => json.delete())
          val bloopProjects = projects.map(_.toBloop(workspace))
          val byName = bloopProjects.map(p => p.name -> p).toMap
          val fullClasspathProjects = bloopProjects.map { p =>
            val fullClasspath = p.dependencies.flatMap { d =>
              byName(d).classpath
            }
            p.copy(
              classpath = (p.classpath ++ fullClasspath).distinct
            )
          }
          fullClasspathProjects.foreach { bloop =>
            val out = bloopDirectory.resolve(bloop.name + ".json")
            val json = C.File(V.bloopVersion, bloop).asJson.spaces2
            Files.write(out, json.getBytes(StandardCharsets.UTF_8))
          }
          Files.createDirectories(digestFile.toNIO.getParent)
          Files.write(digestFile.toNIO, digest.getBytes(StandardCharsets.UTF_8))
          scribe.info(s"time: generated quick build in $timer")
      }
    }
  }
}
