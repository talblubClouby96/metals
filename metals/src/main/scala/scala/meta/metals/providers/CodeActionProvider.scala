package scala.meta.metals.providers

import scala.meta.metals.WorkspaceCommand.ScalafixUnusedImports
import scala.meta.lsp.CodeActionParams
import scala.meta.lsp.Command
import scala.meta.lsp.Diagnostic
import com.typesafe.scalalogging.LazyLogging
import scala.meta.lsp.Command
import io.circe.syntax._

object CodeActionProvider extends LazyLogging {
  def codeActions(params: CodeActionParams): List[Command] = {
    params.context.diagnostics.collectFirst {
      case Diagnostic(_, _, _, Some("scalac"), "Unused import") =>
        Command(
          "Remove unused imports",
          ScalafixUnusedImports.entryName,
          params.textDocument.asJson :: Nil
        )
    }.toList
  }
}
