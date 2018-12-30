addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.4")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.0")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.2.2")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "1.0.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")

addSbtCoursier

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("releases")
