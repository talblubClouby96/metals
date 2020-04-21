package scala.meta.internal.metals.codeactions

import scala.concurrent.Future
import scala.collection.mutable.Buffer
import scala.meta.pc.CancelToken
import org.eclipse.{lsp4j => l}
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._

class ImportMissingSymbol(compilers: Compilers) extends CodeAction {

  override def kind: String = l.CodeActionKind.QuickFix

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def importMissingSymbol(
        diagnostic: l.Diagnostic,
        name: String
    ): Future[Seq[l.CodeAction]] = {
      val textDocumentPositionParams = new l.TextDocumentPositionParams(
        params.getTextDocument(),
        diagnostic.getRange.getEnd()
      )
      compilers
        .autoImports(textDocumentPositionParams, name, token)
        .map { imports =>
          imports.asScala.map { i =>
            val uri = params.getTextDocument().getUri()
            val edit = new l.WorkspaceEdit(Map(uri -> i.edits).asJava)

            val codeAction = new l.CodeAction()

            codeAction.setTitle(ImportMissingSymbol.title(name, i.packageName))
            codeAction.setKind(l.CodeActionKind.QuickFix)
            codeAction.setDiagnostics(List(diagnostic).asJava)
            codeAction.setEdit(edit)

            codeAction
          }
        }
    }

    def importAllMissingSymbol(
        codeActions: Buffer[l.CodeAction]
    ): Seq[l.CodeAction] = {
      pprint.log(codeActions)
      val uniqueCodeActions =
        codeActions.distinctBy(_.getDiagnostics()).toBuffer
      pprint.log(uniqueCodeActions)
      if (uniqueCodeActions.length > 1) {
        val allSymbols: l.CodeAction = new l.CodeAction()

        val uri = params.getTextDocument().getUri()
        val diags = uniqueCodeActions.flatMap(_.getDiagnostics().asScala)
        val edits =
          uniqueCodeActions.flatMap(_.getEdit().getChanges().get(uri).asScala)

        allSymbols.setTitle(ImportMissingSymbol.allSymbolsTitle)
        allSymbols.setKind(l.CodeActionKind.QuickFix)
        allSymbols.setDiagnostics(diags.asJava)
        allSymbols.setEdit(new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava))

        uniqueCodeActions += allSymbols
      }
      codeActions
    }

    Future
      .sequence(params.getContext().getDiagnostics().asScala.collect {
        case d @ ScalacDiagnostic.SymbolNotFound(name)
            if params.getRange().overlapsWith(d.getRange()) =>
          importMissingSymbol(d, name)
      })
      .map(actions => importAllMissingSymbol(actions.flatten))
  }

}

object ImportMissingSymbol {

  def title(name: String, packageName: String): String =
    s"Import '$name' from package '$packageName'"

  def allSymbolsTitle: String =
    s"Import all missing symbols that are unambiguous"

}
