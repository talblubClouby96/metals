package scala.meta.languageserver

import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.Properties
import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect.io
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.{Global, Response}
import scala.tools.nsc.reporters.StoreReporter
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Connection
import langserver.messages.MessageType
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Document

case class ModuleID(organization: String, name: String, revision: String) {
  override def toString: String = s"$organization:$name:$revision"
}
case class CompilerConfig(
    sources: List[AbsolutePath],
    scalacOptions: List[String],
    classpath: String,
    libraryDependencies: List[ModuleID]
)
object CompilerConfig extends LazyLogging {
  def fromPath(
      path: AbsolutePath
  )(implicit cwd: AbsolutePath): CompilerConfig = {
    logger.info(s"Parsing $path")
    val input = Files.newInputStream(path.toNIO)
    try {
      val props = new Properties()
      props.load(input)
      val sources = props
        .getProperty("sources")
        .split(File.pathSeparator)
        .iterator
        .map(AbsolutePath(_))
        .toList
      val scalacOptions = props.getProperty("scalacOptions").split(" ").toList
      val classpath = props.getProperty("classpath")
      val libraryDependencies = props
        .getProperty("libraryDependencies")
        .split(";")
        .iterator
        .flatMap { moduleId =>
          logger.info(s"Parsing $moduleId")
          moduleId.split(":") match {
            case Array(org, name, rev) =>
              ModuleID(org, name, rev) :: Nil
            case _ => Nil
          }
        }
      CompilerConfig(
        sources,
        scalacOptions,
        classpath,
        libraryDependencies.toList
      )
    } finally input.close()
  }
}

class Compiler(
    out: PrintStream,
    config: Observable[AbsolutePath],
    connection: Connection,
    buffers: Buffers
)(implicit cwd: AbsolutePath, s: Scheduler)
    extends LazyLogging {
  private val documentPubSub =
    Observable.multicast[Document](MulticastStrategy.Publish)
  private val documentSubscriber = documentPubSub._1
  val documentPublisher: Observable[Document] = documentPubSub._2
  val onNewCompilerConfig: Observable[Unit] =
    config
      .map(path => CompilerConfig.fromPath(path))
      .map(onNewConfig)

  def autocomplete(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): List[(String, String)] = {
    logger.info(s"Completion request at $path:$line:$column")
    val code = buffers.read(path)
    val offset = lineColumnToOffset(code, line, column)
    compilerByPath.get(path).fold(noCompletions) { compiler =>
      compiler.reporter.reset()
      val source = code.take(offset) + "_CURSOR_" + code.drop(offset)
      val unit = compiler.newCompilationUnit(source, path.toString())
      val richUnit = new compiler.RichCompilationUnit(unit.source)
      compiler.unitOfFile(richUnit.source.file) = richUnit
      val position = richUnit.position(offset)
      logger.info(s"Completion request at position $position")
      val results = compiler.completionsAt(position).matchingResults()
      results
        .map(r => (r.sym.signatureString, r.symNameDropLocal.decoded))
        .distinct
    }
  }

  def typeAt(path: AbsolutePath, line: Int, column: Int): Option[String] = {
    val code = buffers.read(path)
    val offset = lineColumnToOffset(code, line, column)
    compilerByPath.get(path).flatMap { compiler =>
      compiler.reporter.reset()
      val unit = compiler.newCompilationUnit(code, path.toString())
      val richUnit = new compiler.RichCompilationUnit(unit.source)
      compiler.unitOfFile(richUnit.source.file) = richUnit
      val position = richUnit.position(offset)
      val response = ask[compiler.Tree](r => compiler.askTypeAt(position, r))
      val typedTree = response.get.swap
      typedTree.toOption.flatMap(t => typeOfTree(compiler)(t))
    }
  }

  private val compilerByPath = mutable.Map.empty[AbsolutePath, Global]
  private def onNewConfig(config: CompilerConfig): Unit = {
    logger.info(s"Loading new compiler from config $config")
    val vd = new io.VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = config.classpath
    settings.processArgumentString(
      ("-Ypresentation-any-thread" :: config.scalacOptions).mkString(" ")
    )
    val compiler = new Global(settings, new StoreReporter)
    config.sources.foreach { path =>
      // TODO(olafur) garbage collect compilers from removed files.
      compilerByPath(path) = compiler
    }
    logger.warn(s"libs = ${config.libraryDependencies}")
    Future {
      val sourcesClasspath = config.libraryDependencies.flatMap {
        case module @ ModuleID(org, name, version) =>
          val sourceJars = Jars.fetch(org, name, version, out, sources = true)
          logger.info(s"Fetched jars for $module")
          sourceJars
      }
      logger.info(s"Start indexing classpath ${config.classpath}")
      Ctags.index(sourcesClasspath) { doc =>
        documentSubscriber.onNext(doc)
      }
    }
  }
  private def noCompletions: List[(String, String)] = {
    connection.showMessage(
      MessageType.Warning,
      "Run project/config:scalametaEnableCompletions to setup completion for this " +
        "config.in(project) or *:scalametaEnableCompletions for all projects/configurations"
    )
    Nil
  }
  private def lineColumnToOffset(
      contents: String,
      line: Int,
      column: Int
  ): Int = {
    var i = 0
    var l = line
    while (l > 0) {
      if (contents(i) == '\n') l -= 1
      i += 1
    }
    i + column
  }

  private def ask[A](f: Response[A] => Unit): Response[A] = {
    val r = new Response[A]
    f(r)
    r
  }

  private def typeOfTree(c: Global)(t: c.Tree): Option[String] = {
    import c._

    val refinedTree = t match {
      case t: ImplDef if t.impl != null => t.impl
      case t: ValOrDefDef if t.tpt != null => t.tpt
      case t: ValOrDefDef if t.rhs != null => t.rhs
      case x => x
    }

    Option(refinedTree.tpe).map(_.widen.toString)
  }

}
