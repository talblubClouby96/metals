def localSnapshotVersion = "0.8.5-SNAPSHOT"
def isCI = System.getenv("CI") != null
def crossSetting[A](
    scalaVersion: String,
    if211: List[A],
    otherwise: List[A] = Nil
): List[A] =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, 11)) => if211
    case _ => otherwise
  }

val MUnitFramework = new TestFramework("munit.Framework")

inThisBuild(
  List(
    version ~= { dynVer =>
      if (isCI) dynVer
      else localSnapshotVersion // only for local publishing
    },
    scalaVersion := V.scala212,
    crossScalaVersions := List(V.scala212),
    scalacOptions ++= List(
      "-target:jvm-1.8",
      "-Yrangepos",
      // -Xlint is unusable because of
      // https://github.com/scala/bug/issues/10448
      "-Ywarn-unused:imports"
    ),
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-scalac" % V.scalameta cross CrossVersion.full
    ),
    scalacOptions += s"-P:semanticdb:sourceroot:${baseDirectory.in(ThisBuild).value}",
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalameta/metals")),
    developers := List(
      Developer(
        "laughedelic",
        "Alexey Alekhin",
        "laughedelic@gmail.com",
        url("https://github.com/laughedelic")
      ),
      Developer(
        "ckipp01",
        "Chris Kipp",
        "ckipp@pm.me",
        url("https://chris-kipp.io")
      ),
      Developer(
        "gabro",
        "Gabriele Petronella",
        "gabriele@buildo.io",
        url("https://github.com/gabro")
      ),
      Developer(
        "mudsam",
        "Johan Mudsam",
        "johan@mudsam.com",
        url("https://github.com/mudsam")
      ),
      Developer(
        "jvican",
        "Jorge Vicente Cantero",
        "jorgevc@fastmail.es",
        url("https://jvican.github.io/")
      ),
      Developer(
        "marek1840",
        "Marek Żarnowski",
        "mzarnowski@virtuslab.com",
        url("https://github.com/marek1840")
      ),
      Developer(
        "olafurpg",
        "Ólafur Páll Geirsson",
        "olafurpg@gmail.com",
        url("https://geirsson.com")
      ),
      Developer(
        "ShaneDelmore",
        "Shane Delmore",
        "sdelmore@twitter.com",
        url("http://delmore.io")
      ),
      Developer(
        "tgodzik",
        "Tomasz Godzik",
        "tgodzik@virtuslab.com",
        url("https://github.com/tgodzik")
      )
    ),
    testFrameworks := List(),
    resolvers += Resolver.sonatypeRepo("public"),
    dependencyOverrides += V.guava,
    // faster publishLocal:
    publishArtifact.in(packageDoc) := sys.env.contains("CI"),
    publishArtifact.in(packageSrc) := sys.env.contains("CI"),
    // forking options
    javaOptions += {
      import scala.collection.JavaConverters._
      val props = System.getProperties
      props
        .stringPropertyNames()
        .asScala
        .map { configKey => s"-D$configKey=${props.getProperty(configKey)}" }
        .mkString(" ")
    },
    resolvers += Resolver.bintrayRepo("scalacenter", "releases")
  )
)

onLoad.in(Global) ~= { old =>
  if (!scala.util.Properties.isWin) {
    import java.nio.file._
    val prePush = Paths.get(".git", "hooks", "pre-push")
    Files.createDirectories(prePush.getParent)
    Files.write(
      prePush,
      """#!/bin/sh
        |set -eux
        |bin/scalafmt --diff
        |git diff --exit-code
        |""".stripMargin.getBytes()
    )
    prePush.toFile.setExecutable(true)
  }
  old
}
cancelable.in(Global) := true
crossScalaVersions := Nil

addCommandAlias("scalafixAll", "all compile:scalafix test:scalafix")
addCommandAlias("scalafixCheck", "; scalafix --check ; test:scalafix --check")

commands += Command.command("save-expect") { s =>
  "unit/test:runMain tests.SaveExpect" ::
    s
}

lazy val V = new {
  val scala210 = "2.10.7"
  val scala211 = "2.11.12"
  val scala212 = "2.12.11"
  val scala213 = "2.13.1"
  val scalameta = "4.3.8"
  val semanticdb = scalameta
  val bsp = "2.0.0-M4+10-61e61e87"
  val bloop = "1.4.0-RC1-192-72a856b6"
  val bloopNightly = bloop
  val sbtBloop = bloop
  val gradleBloop = bloop
  val mavenBloop = bloop
  val mdoc = "2.1.5"
  val scalafmt = "2.4.2"
  val munit = "0.7.1"
  // List of supported Scala versions in SemanticDB. Needs to be manually updated
  // for every SemanticDB upgrade.
  def supportedScalaBinaryVersions =
    supportedScalaVersions.iterator
      .map(CrossVersion.partialVersion)
      .collect { case Some((a, b)) => s"$a.$b" }
      .toList
      .distinct
  def supportedScalaVersions =
    nonDeprecatedScalaVersions ++ deprecatedScalaVersions
  def deprecatedScalaVersions =
    Seq(scala211, "2.12.8", "2.12.9", "2.13.0")
  def nonDeprecatedScalaVersions = Seq(scala213, scala212, "2.12.10")
  def guava = "com.google.guava" % "guava" % "29.0-jre"
  def lsp4j = "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.9.0"
  def dap4j =
    "org.eclipse.lsp4j" % "org.eclipse.lsp4j.debug" % "0.9.0"
  val coursier = "2.0.0-RC6-11"
}

skip.in(publish) := true

lazy val interfaces = project
  .in(file("mtags-interfaces"))
  .settings(
    moduleName := "mtags-interfaces",
    autoScalaLibrary := false,
    libraryDependencies ++= List(
      V.lsp4j
    ),
    crossVersion := CrossVersion.disabled,
    javacOptions in (Compile / doc) ++= List(
      "-tag",
      "implNote:a:Implementation Note:"
    )
  )

val genyVersion = Def.setting {
  if (scalaVersion.value.startsWith("2.11")) "0.1.6"
  else "0.4.2"
}

lazy val mtags = project
  .settings(
    moduleName := "mtags",
    crossVersion := CrossVersion.full,
    crossScalaVersions := V.supportedScalaVersions,
    scalacOptions ++= crossSetting(
      scalaVersion.value,
      if211 = List("-Xexperimental", "-Ywarn-unused-import")
    ),
    scalacOptions --= crossSetting(
      scalaVersion.value,
      if211 = List("-Ywarn-unused:imports")
    ),
    libraryDependencies ++= List(
      "com.thoughtworks.qdox" % "qdox" % "2.0.0", // for java mtags
      "org.jsoup" % "jsoup" % "1.13.1", // for extracting HTML from javadocs
      "org.lz4" % "lz4-java" % "1.7.1", // for streaming hashing when indexing classpaths
      "com.lihaoyi" %% "geny" % genyVersion.value,
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1",
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full
    ),
    libraryDependencies ++= {
      if (isCI) Nil
      // NOTE(olafur) pprint is indispensable for me while developing, I can't
      // use println anymore for debugging because pprint.log is 100 times better.
      else
        crossSetting(
          scalaVersion.value,
          if211 = List("com.lihaoyi" %% "pprint" % "0.5.4"),
          otherwise = List("com.lihaoyi" %% "pprint" % "0.5.9")
        )
    },
    buildInfoPackage := "scala.meta.internal.mtags",
    buildInfoKeys := Seq[BuildInfoKey](
      "scalaCompilerVersion" -> scalaVersion.value
    )
  )
  .dependsOn(interfaces)
  .enablePlugins(BuildInfoPlugin)

lazy val metals = project
  .settings(
    fork.in(Compile, run) := true,
    mainClass.in(Compile) := Some("scala.meta.metals.Main"),
    // As a general rule of thumb, we try to keep Scala dependencies to a minimum.
    libraryDependencies ++= List(
      // =================
      // Java dependencies
      // =================
      // for bloom filters
      V.guava,
      "com.geirsson" %% "metaconfig-core" % "0.9.10",
      // for measuring memory footprint
      "org.openjdk.jol" % "jol-core" % "0.10",
      // for file watching
      "io.methvin" % "directory-watcher" % "0.9.9",
      // for http client
      "io.undertow" % "undertow-core" % "2.0.30.Final",
      "org.jboss.xnio" % "xnio-nio" % "3.8.0.Final",
      // for persistent data like "dismissed notification"
      "org.flywaydb" % "flyway-core" % "6.3.3",
      "com.h2database" % "h2" % "1.4.200",
      // for starting `sbt bloopInstall` process
      "com.zaxxer" % "nuprocess" % "2.0.0",
      "net.java.dev.jna" % "jna" % "4.5.2",
      "net.java.dev.jna" % "jna-platform" % "4.5.2",
      // for token edit-distance used by goto definition
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
      // for BSP
      "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.1",
      "ch.epfl.scala" % "bsp4j" % V.bsp,
      "ch.epfl.scala" %% "bloop-launcher" % V.bloopNightly,
      // for LSP
      V.lsp4j,
      // for DAP
      V.dap4j,
      // for producing SemanticDB from Java source files
      "com.thoughtworks.qdox" % "qdox" % "2.0.0",
      // for finding paths of global log/cache directories
      "io.github.soc" % "directories" % "11",
      // ==================
      // Scala dependencies
      // ==================
      "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1",
      "org.scalameta" % "mdoc-interfaces" % V.mdoc,
      "org.scalameta" %% "scalafmt-dynamic" % V.scalafmt,
      // For reading classpaths.
      // for fetching ch.epfl.scala:bloop-frontend and other library dependencies
      "io.get-coursier" % "interface" % "0.0.21",
      // for logging
      "com.outr" %% "scribe" % "2.7.12",
      "com.outr" %% "scribe-slf4j" % "2.7.12", // needed for flyway database migrations
      // for debugging purposes, not strictly needed but nice for productivity
      "com.lihaoyi" %% "pprint" % "0.5.9",
      // For exporting Pants builds.
      "com.lihaoyi" %% "ujson" % "1.0.0",
      "ch.epfl.scala" %% "bloop-config" % V.bloop,
      "ch.epfl.scala" %% "bloop-frontend" % V.bloop,
      // For remote language server
      "com.lihaoyi" %% "requests" % "0.5.1",
      // for producing SemanticDB from Scala source files
      "org.scalameta" %% "scalameta" % V.scalameta,
      "org.scalameta" % "semanticdb-scalac-core" % V.scalameta cross CrossVersion.full
    ),
    buildInfoPackage := "scala.meta.internal.metals",
    buildInfoKeys := Seq[BuildInfoKey](
      "localSnapshotVersion" -> localSnapshotVersion,
      "metalsVersion" -> version.value,
      "mdocVersion" -> V.mdoc,
      "bspVersion" -> V.bsp,
      "bloopVersion" -> V.bloop,
      "bloopNightlyVersion" -> V.bloop,
      "sbtBloopVersion" -> V.sbtBloop,
      "gradleBloopVersion" -> V.gradleBloop,
      "mavenBloopVersion" -> V.mavenBloop,
      "scalametaVersion" -> V.scalameta,
      "semanticdbVersion" -> V.semanticdb,
      "scalafmtVersion" -> V.scalafmt,
      "supportedScalaVersions" -> V.supportedScalaVersions,
      "supportedScalaBinaryVersions" -> V.supportedScalaBinaryVersions,
      "deprecatedScalaVersions" -> V.deprecatedScalaVersions,
      "scala211" -> V.scala211,
      "scala212" -> V.scala212,
      "scala213" -> V.scala213
    ),
    mainClass in GraalVMNativeImage := Some(
      "scala.meta.internal.pantsbuild.BloopPants"
    ),
    graalVMNativeImageOptions ++= {
      val reflectionFile =
        Keys.sourceDirectory.in(Compile).value./("graal")./("reflection.json")
      assert(reflectionFile.exists, "no such file: " + reflectionFile)
      List(
        "-H:+ReportUnsupportedElementsAtRuntime",
        "--initialize-at-build-time",
        "--initialize-at-run-time=scala.meta.internal.pantsbuild,metaconfig",
        "--no-server",
        "--enable-http",
        "--enable-https",
        "-H:EnableURLProtocols=http,https",
        "--enable-all-security-services",
        "--no-fallback",
        s"-H:ReflectionConfigurationFiles=$reflectionFile",
        "--allow-incomplete-classpath",
        "-H:+ReportExceptionStackTraces"
      )
    }
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin, GraalVMNativeImagePlugin)

lazy val input = project
  .in(file("tests/input"))
  .settings(
    skip.in(publish) := true,
    scalacOptions ++= List(
      "-P:semanticdb:synthetics:on"
    ),
    libraryDependencies ++= List(
      // these projects have macro annotations
      "org.scalameta" %% "scalameta" % V.scalameta,
      "io.circe" %% "circe-derivation-annotations" % "0.9.0-M5"
    ),
    scalacOptions += "-P:semanticdb:synthetics:on",
    addCompilerPlugin(
      "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full
    )
  )
  .disablePlugins(ScalafixPlugin)

lazy val testSettings: Seq[Def.Setting[_]] = List(
  Test / parallelExecution := false,
  skip.in(publish) := true,
  fork := true,
  testFrameworks := List(MUnitFramework),
  testOptions.in(Test) ++= {
    if (isCI) {
      // Enable verbose logging using sbt loggers in CI.
      List(Tests.Argument(MUnitFramework, "+l", "--verbose"))
    } else {
      Nil
    }
  }
)

lazy val mtest = project
  .in(file("tests/mtest"))
  .settings(
    skip.in(publish) := true,
    crossScalaVersions := V.supportedScalaVersions,
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % V.coursier,
      "org.scalameta" %% "munit" % V.munit
    ),
    scalacOptions ++= crossSetting(
      scalaVersion.value,
      if211 = List("-Xexperimental", "-Ywarn-unused-import")
    ),
    scalacOptions --= crossSetting(
      scalaVersion.value,
      if211 = List("-Ywarn-unused:imports")
    ),
    buildInfoPackage := "tests",
    buildInfoObject := "BuildInfoVersions",
    buildInfoKeys := Seq[BuildInfoKey](
      "scala211" -> V.scala211,
      "scala212" -> V.scala212,
      "scalaVersion" -> scalaVersion.value
    )
  )
  .dependsOn(mtags)
  .enablePlugins(BuildInfoPlugin)

lazy val cross = project
  .in(file("tests/cross"))
  .settings(
    testSettings,
    libraryDependencies ++= List(
      "com.chuusai" %% "shapeless" % "2.3.3",
      "org.typelevel" %% "cats-core" % "2.1.1",
      "org.typelevel" %% "simulacrum" % "1.0.0",
      "com.olegpy" %% "better-monadic-for" % "0.3.1",
      "org.typelevel" %% "kind-projector" % "0.10.3"
    ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, major)) if major <= 12 =>
        List("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
      case _ => Nil
    }),
    crossScalaVersions := V.nonDeprecatedScalaVersions,
    scalacOptions ++= crossSetting(
      scalaVersion.value,
      if211 = List("-Xexperimental", "-Ywarn-unused-import")
    ),
    scalacOptions --= crossSetting(
      scalaVersion.value,
      if211 = List("-Ywarn-unused:imports")
    )
  )
  .dependsOn(mtest, mtags)

lazy val unit = project
  .in(file("tests/unit"))
  .settings(
    testSettings,
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % V.coursier, // for jars
      "ch.epfl.scala" %% "bloop-config" % V.bloop,
      "org.scalameta" %% "munit" % V.munit
    ),
    buildInfoPackage := "tests",
    resourceGenerators.in(Compile) += InputProperties.resourceGenerator(input),
    compile.in(Compile) :=
      compile.in(Compile).dependsOn(compile.in(input, Test)).value,
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceroot" -> baseDirectory.in(ThisBuild).value,
      "targetDirectory" -> target.in(Test).value,
      "testResourceDirectory" -> resourceDirectory.in(Test).value,
      "scalaVersion" -> scalaVersion.value
    )
  )
  .dependsOn(mtest, metals)
  .enablePlugins(BuildInfoPlugin)

def crossPublishLocal(scalaV: String) = Def.task[Unit] {
  // Runs `publishLocal` for mtags with `scalaVersion := $scalaV`
  val newState = Project
    .extract(state.value)
    .appendWithSession(
      List(
        scalaVersion.in(mtags) := scalaV,
        useSuperShell.in(ThisBuild) := false
      ),
      state.value
    )
  val (s, _) = Project
    .extract(newState)
    .runTask(publishLocal.in(mtags), newState)
}

def publishMtags =
  publishLocal
    .in(interfaces)
    .dependsOn(
      crossPublishLocal(V.scala211)
        .dependsOn(crossPublishLocal(V.scala213))
    )
lazy val slow = project
  .in(file("tests/slow"))
  .settings(
    testSettings,
    testOnly.in(Test) := testOnly.in(Test).dependsOn(publishMtags).evaluated,
    test.in(Test) := test.in(Test).dependsOn(publishMtags).value
  )
  .dependsOn(unit)

lazy val bench = project
  .in(file("metals-bench"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    fork.in(run) := true,
    skip.in(publish) := true,
    moduleName := "metals-bench",
    libraryDependencies ++= List(
      // for measuring memory usage
      "org.spire-math" %% "clouseau" % "0.2.2"
    ),
    buildInfoKeys := Seq[BuildInfoKey](scalaVersion),
    buildInfoPackage := "bench"
  )
  .dependsOn(unit)
  .enablePlugins(JmhPlugin)

lazy val docs = project
  .in(file("metals-docs"))
  .settings(
    skip.in(publish) := true,
    moduleName := "metals-docs",
    mdoc := run.in(Compile).evaluated,
    munitRepository := Some("scalameta/metals"),
    libraryDependencies ++= List(
      "org.jsoup" % "jsoup" % "1.13.1"
    )
  )
  .dependsOn(metals)
  .enablePlugins(DocusaurusPlugin, MUnitReportPlugin)

addCommandAlias(
  "fastpass-link",
  "; metals/graalvm-native-image:packageBin ; taskready"
)
commands += Command.command("taskready") { s =>
  import scala.sys.process._
  "say 'native-image ready'".!
  s
}
