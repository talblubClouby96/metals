inThisBuild(
  List(
    scalaVersion := "2.12.3",
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalameta/language-server")),
    developers := List(
      Developer(
        "olafurpg",
        "Ólafur Páll Geirsson",
        "olafurpg@gmail.com",
        url("https://geirsson.com")
      ),
      Developer(
        "gabro",
        "Gabriele Petronella",
        "gabriele@buildo.io",
        url("https://github.com/gabro")
      ),
      Developer(
        "laughedelic",
        "Alexey Alekhin",
        "laughedelic@gmail.com",
        url("https://github.com/laughedelic")
      )
    ),
    releaseEarlyWith := BintrayPublisher,
    releaseEarlyEnableSyncToMaven := false,
    publishMavenStyle := true,
    bintrayOrganization := Some("scalameta"),
    bintrayReleaseOnPublish := dynverGitDescribeOutput.value.isVersionStable,
    // faster publishLocal:
    publishArtifact in packageDoc := sys.env.contains("CI"),
    publishArtifact in packageSrc := sys.env.contains("CI")
  )
)

lazy val noPublish = List(
  publishTo := None,
  publishArtifact := false,
  skip in publish := true
)

// not publishing the root project
noPublish

lazy val semanticdbSettings = List(
  addCompilerPlugin(
    "org.scalameta" % "semanticdb-scalac" % "2.1.2" cross CrossVersion.full
  ),
  scalacOptions += "-Yrangepos"
)

lazy val languageserver = project
  .settings(
    resolvers += Resolver.bintrayRepo("dhpcs", "maven"),
    libraryDependencies ++= Seq(
      "com.dhpcs" %% "scala-json-rpc" % "2.0.1",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
      "org.slf4j" % "slf4j-api" % "1.7.25",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "org.codehaus.groovy" % "groovy" % "2.4.0",
      "org.scalatest" %% "scalatest" % "3.0.1" % Test
    )
  )

lazy val metaserver = project
  .settings(
    PB.targets.in(Compile) := Seq(
      scalapb.gen(
        flatPackage = true // Don't append filename to package
      ) -> sourceManaged.in(Compile).value./("protobuf")
    ),
    resolvers += Resolver.bintrayRepo("dhpcs", "maven"),
    testFrameworks := new TestFramework("utest.runner.Framework") :: Nil,
    fork in Test := true,
    buildInfoKeys := Seq[BuildInfoKey](
      "testWorkspaceBaseDirectory" -> baseDirectory.in(testWorkspace).value.getParent
    ),
    buildInfoPackage := "scala.meta.languageserver.internal",
    libraryDependencies ++= List(
      "io.github.soc" % "directories" % "5",
      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
      "io.monix" %% "monix" % "2.3.0",
      "com.lihaoyi" %% "pprint" % "0.5.3",
      "com.thoughtworks.qdox" % "qdox" % "2.0-M7", // for java ctags
      "io.get-coursier" %% "coursier" % coursier.util.Properties.version,
      "io.get-coursier" %% "coursier-cache" % coursier.util.Properties.version,
      "ch.epfl.scala" % "scalafix-cli" % "0.5.3" cross CrossVersion.full,
      "com.lihaoyi" %% "utest" % "0.6.0" % Test,
      "org.scalameta" %% "testkit" % "2.0.1" % Test
    )
  )
  .dependsOn(
    languageserver,
    testWorkspace % "test->test"
  )
  .enablePlugins(BuildInfoPlugin)

lazy val testWorkspace = project
  .in(file("test-workspace") / "a")
  .settings(
    noPublish,
    semanticdbSettings,
    scalacOptions += {
      // Need to fix source root so it matches the workspace folder.
      val sourceRoot = baseDirectory.in(ThisBuild).value / "test-workspace"
      s"-P:semanticdb:sourceroot:$sourceRoot"
    }
  )
