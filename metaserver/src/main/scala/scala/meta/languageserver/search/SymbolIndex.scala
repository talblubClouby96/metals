package scala.meta.languageserver.search

import java.net.URI
import scala.meta.languageserver.Buffers
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.{index => i}
import `scala`.meta.languageserver.index.SymbolData
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Notifications
import langserver.messages.DefinitionResult
import langserver.messages.DocumentSymbolResult
import langserver.messages.MessageType
import org.langmeta.inputs.Input
import org.langmeta.internal.semanticdb.schema.Document
import org.langmeta.internal.semanticdb.schema.ResolvedName
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb.Signature
import org.langmeta.semanticdb.Symbol

/**
 * A high-level wrapper around [[DocumentIndex]] and [[SymbolIndexer]].
 *
 * Can respond to high-level queries like "go to definition" and "find references".
 */
class SymbolIndex(
    val symbols: SymbolIndexer,
    val documents: DocumentIndex,
    cwd: AbsolutePath,
    notifications: Notifications,
    buffers: Buffers,
) extends LazyLogging {

  def findSymbol(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): Option[SymbolData] = {
    logger.info(s"findSymbol at $path:$line:$column")

    // NOTE(olafur) this is false to assume that the filename is relative
    // to cwd, what about jars?
    for {
      document <- documents.getDocument(path.toNIO.toUri)
      _ = logger.info(s"Found document for $path")
      _ <- isFreshSemanticdb(path, document)
      input = Input.VirtualFile(document.filename, document.contents)
      _ = logger.info(s"Document for $path is fresh")
      name <- document.names.collectFirst {
        case name @ ResolvedName(Some(position), sym, _) if {
              val pos = input.toIndexRange(position.start, position.end)
              logger.info(
                s"$sym at ${document.filename
                  .replaceFirst(".*/", "")}:${pos.startLine}:${pos.startColumn}-${pos.endLine}:${pos.endColumn}"
              )
              pos.startLine <= line &&
              pos.startColumn <= column &&
              pos.endLine >= line &&
              pos.endColumn >= column
            } =>
          name
      }
      msym = Symbol(name.symbol)
      _ = logger.info(s"Found matching symbol $msym")
      symbol <- symbols.get(name.symbol).orElse {
        val alts = alternatives(msym)
        logger.info(s"Trying alternatives: ${alts.mkString(" | ")}")
        alts.collectFirst { case symbols(alternative) => alternative }
      }
      _ = logger.info(
        s"Found matching symbol index ${symbol.name}: ${symbol.signature}"
      )
    } yield symbol
  }

  def goToDefinition(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): Option[DefinitionResult] = {
    for {
      symbol <- findSymbol(path, line, column)
      definition <- symbol.definition
      _ = logger.info(
        s"Found definition $definition"
      )
    } yield DefinitionResult(definition.toLocation :: Nil)
  }

  /** Register this Database to symbol indexer. */
  def indexDatabase(document: s.Database): Unit = {
    document.documents.foreach(indexDocument)
  }

  /**
   *
   * Register this Document to symbol indexer.
   *
   * Indexes definitions, denotations and references in this document.
   *
   * @param document Must respect the following conventions:
   *                 - filename must be a URI
   *                 - names must be sorted
   */
  def indexDocument(document: s.Document): Unit = {
    val input = Input.VirtualFile(document.filename, document.contents)
    // what do we put as the uri?
    val uri = URI.create(document.filename)
    documents.putDocument(uri, document)
    document.names.foreach {
      // TODO(olafur) handle local symbols on the fly from a `Document` in go-to-definition
      // local symbols don't need to be indexed globally, by skipping them we should
      // def isLocalSymbol(sym: String): Boolean =
      // !sym.endsWith(".") &&
      //     !sym.endsWith("#") &&
      //     !sym.endsWith(")")
      // be able to minimize the size of the global index significantly.
      //      case s.ResolvedName(_, sym, _) if isLocalSymbol(sym) => // Do nothing, local symbol.
      case s.ResolvedName(Some(s.Position(start, end)), sym, true) =>
        symbols.addDefinition(
          sym,
          i.Position(document.filename, Some(input.toIndexRange(start, end)))
        )
      case s.ResolvedName(Some(s.Position(start, end)), sym, false) =>
        symbols.addReference(
          document.filename,
          input.toIndexRange(start, end),
          sym
        )
      case _ =>
    }
    document.symbols.foreach {
      case s.ResolvedSymbol(sym, Some(denot)) =>
        symbols.addDenotation(sym, denot.flags, denot.name, denot.signature)
      case _ =>
    }
  }

  /**
   * Returns false this this document is stale.
   *
   * A document is considered stale if it's off-sync with the contents in [[buffers]].
   */
  private def isFreshSemanticdb(
      path: AbsolutePath,
      document: Document
  ): Option[Unit] = {
    val ok = Option(())
    val s = buffers.read(path)
    if (s == document.contents) ok
    else {
      // NOTE(olafur) it may be a bit annoying to bail on a single character
      // edit in the file. In the future, we can try more to make sense of
      // partially fresh files using something like edit distance.
      notifications.showMessage(
        MessageType.Warning,
        "Please recompile for up-to-date information"
      )
      None
    }
  }

  /** Returns a list of fallback symbols that can act instead of given symbol. */
  private def alternatives(symbol: Symbol): List[Symbol] =
    symbol match {
      case Symbol.Global(owner, Signature.Term(name)) =>
        // If `case class A(a: Int)` and there is no companion object, resolve
        // `A` in `A(1)` to the class definition.
        Symbol.Global(owner, Signature.Type(name)) :: Nil
      case Symbol.Multi(ss) =>
        // If `import a.B` where `case class B()`, then
        // resolve to either symbol, whichever has a definition.
        ss
      case Symbol.Global(
          companion @ Symbol.Global(owner, signature),
          Signature.Method("apply" | "copy", _)
          ) =>
        // If `case class Foo(a: Int)`, then resolve
        // `apply` in `Foo.apply(1)`, and
        // `copy` in `Foo(1).copy(a = 2)`
        // to the `Foo` class definition.
        companion :: Symbol.Global(owner, Signature.Type(signature.name)) :: Nil
      case Symbol.Global(owner, Signature.Method(name, _)) =>
        Symbol.Global(owner, Signature.Term(name)) :: Nil
      case Symbol.Global(
          Symbol.Global(
            Symbol.Global(owner, signature),
            Signature.Method("copy" | "apply", _)
          ),
          param: Signature.TermParameter
          ) =>
        // If `case class Foo(a: Int)`, then resolve
        // `a` in `Foo.apply(a = 1)`, and
        // `a` in `Foo(1).copy(a = 2)`
        // to the `Foo.a` primary constructor definition.
        Symbol.Global(
          Symbol.Global(owner, Signature.Type(signature.name)),
          param
        ) :: Nil
      case _ =>
        logger.info(s"Found no alternative for ${symbol.structure}")
        Nil
    }

}

object SymbolIndex {
  def empty(cwd: AbsolutePath): SymbolIndex =
    apply(cwd, (_, _) => (), Buffers())
  def apply(
      cwd: AbsolutePath,
      notifications: Notifications,
      buffers: Buffers
  ): SymbolIndex = {
    val symbols = new TrieMapSymbolIndexer()
    val documents = new InMemoryDocumentIndex()
    new SymbolIndex(symbols, documents, cwd, notifications, buffers)
  }
}
