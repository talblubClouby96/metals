package tests.search

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.languageserver.internal.BuildInfo
import scala.meta.languageserver.ScalametaLanguageServer
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.search.InverseSymbolIndexer
import scala.meta.languageserver.search.SymbolIndex
import scala.{meta => m}
import langserver.messages.ClientCapabilities
import monix.execution.schedulers.TestScheduler
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath
import org.langmeta.io.Classpath
import tests.MegaSuite
import utest._

object SymbolIndexTest extends MegaSuite {
  implicit val cwd: AbsolutePath =
    AbsolutePath(BuildInfo.testWorkspaceBaseDirectory)
  val path = cwd
    .resolve("src")
    .resolve("test")
    .resolve("scala")
    .resolve("example")
    .resolve("UserTest.scala")
  Predef.assert(Files.isRegularFile(path.toNIO), path.toString())
  val s = TestScheduler()
  val config = ServerConfig(
    cwd,
    setupScalafmt = false,
    indexJDK = false, // TODO(olafur) enabling this breaks go to definition
    indexClasspath = true // set to false to speedup edit/debug cycle
  )
  val client = new PipedOutputStream()
  val stdin = new PipedInputStream(client)
  val stdout = new PipedOutputStream()
  // TODO(olafur) run this as part of utest.runner.Framework.setup()
  val server =
    new ScalametaLanguageServer(config, stdin, stdout, System.out)(s)
  server.initialize(0L, cwd.toString(), ClientCapabilities())
  while (s.tickOne()) () // Trigger indexing
  val indexer: SymbolIndex = server.symbolIndexer
  override val tests = Tests {

    def assertSymbolFound(
        line: Int,
        column: Int,
        expected: String
    ): Unit = {
      val term = indexer.findSymbol(path, line, column)
      Predef.assert(
        term.isDefined,
        s"Symbol not found at $path:$line:$column. Did you run scalametaEnableCompletions from sbt?"
      )
      assertNoDiff(term.get.symbol, expected)
      Predef.assert(
        term.get.definition.isDefined,
        s"Definition not found for term $term"
      )
    }

    "fallback" - {
      "<<User>>(...)" -
        assertSymbolFound(3, 17, "_root_.a.User#")
      "User.<<apply>>(...)" -
        assertSymbolFound(3, 22, "_root_.a.User#")
      "User.apply(<<name>> ...)" -
        assertSymbolFound(3, 28, "_root_.a.User#(name)")
      "user.copy(<<age>> = ...)" -
        assertSymbolFound(4, 14, "_root_.a.User#(age)")
    }

    "classpath" - {
      "<<List>>(...)" - // ScalaMtags
        assertSymbolFound(5, 5, "_root_.scala.collection.immutable.List.")
      "<<CharRef>>.create(...)" - // JavaMtags
        assertSymbolFound(8, 19, "_root_.scala.runtime.CharRef.")
    }

    "bijection" - {
      val target = cwd.resolve("target").resolve("scala-2.12")
      val originalDatabase = {
        val complete = m.Database.load(
          Classpath(
            target.resolve("classes") ::
              target.resolve("test-classes") ::
              Nil
          )
        )
        val slimDocuments = complete.documents.map { d =>
          d.copy(messages = Nil, synthetics = Nil, symbols = Nil)
        }
        m.Database(slimDocuments)
      }
      val reconstructedDatabase = InverseSymbolIndexer.reconstructDatabase(
        cwd,
        indexer.documents,
        indexer.symbols.allSymbols
      )
      val filenames = reconstructedDatabase.documents.toIterator.map { d =>
        Paths.get(d.input.syntax).getFileName.toString
      }.toList
      assert(filenames.nonEmpty)
      assert(
        filenames == List(
          "User.scala",
          "UserTest.scala"
        )
      )
      assertNoDiff(reconstructedDatabase.syntax, originalDatabase.syntax)
    }
  }

  override def utestAfterAll(): Unit = {
    println("Shutting down server...")
    server.shutdown()
    while (s.tickOne()) ()
    stdin.close()
  }
}
