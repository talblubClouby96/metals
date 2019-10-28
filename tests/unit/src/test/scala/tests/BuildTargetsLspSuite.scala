package tests
import scala.concurrent.duration.Duration
import scala.concurrent.Future

object BuildTargetsLspSuite
    extends BaseLspSuite("build-targets")
    with TestHovers {

  override def testAsync(
      name: String,
      maxDuration: Duration = Duration("3min")
  )(run: => Future[Unit]): Unit = {
    if (BaseSuite.isWindows) {
      // Tests are not working on windows CI
      ignore(name) {}
    } else {
      super.testAsync(name, maxDuration)(run)
    }
  }

  testAsync("scala-priority") {
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "2.10.6",
           |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.7"],
           |    "additionalSources": [ "shared/Main.scala" ]
           |  },
           |  "b": {
           |    "scalaVersion": "${BuildInfo.scalaVersion}",
           |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.1.7"],
           |    "additionalSources": [ "shared/Main.scala" ]
           |  }
           |}
        """.stripMargin
      )
      // Assert that a supported Scala version target is picked over 2.10.
      _ <- server.assertHover(
        "shared/Main.scala",
        """
          |object Main {
          |  sourcecode.Line(1).val@@ue
          |}""".stripMargin,
        """val value: Int""".hover
      )
    } yield ()
  }
}
