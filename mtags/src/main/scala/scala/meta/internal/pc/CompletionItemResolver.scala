package scala.meta.internal.pc

import org.eclipse.lsp4j.CompletionItem
import scala.collection.JavaConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.SymbolDocumentation

class CompletionItemResolver(
    val compiler: MetalsGlobal
) {
  import compiler._
  def resolve(item: CompletionItem, msym: String): CompletionItem = {
    val gsym = inverseSemanticdbSymbol(msym)
    if (gsym != NoSymbol) {
      symbolDocumentation(gsym).orElse(symbolDocumentation(gsym.companion)) match {
        case Some(info) if item.getDetail != null =>
          if (isJavaSymbol(gsym)) {
            val data = item.data.getOrElse(CompletionItemData.empty)
            val newDetail = replaceJavaParameters(info, item.getDetail)
            item.setDetail(newDetail)
            if (item.getTextEdit != null && data.kind == CompletionItemData.OverrideKind) {
              item.getTextEdit.setNewText(
                replaceJavaParameters(info, item.getTextEdit.getNewText)
              )
              item.setLabel(replaceJavaParameters(info, item.getLabel))
            }
          } else {
            val defaults = info
              .parameters()
              .asScala
              .iterator
              .map(_.defaultValue())
              .filterNot(_.isEmpty)
            val matcher = "= \\{\\}".r.pattern.matcher(item.getDetail)
            val out = new StringBuffer()
            while (matcher.find()) {
              if (defaults.hasNext) {
                matcher.appendReplacement(out, s"= ${defaults.next()}")
              }
            }
            matcher.appendTail(out)
            item.setDetail(out.toString)
          }
          val docstring = fullDocstring(gsym)
          item.setDocumentation(docstring.toMarkupContent)
        case _ =>
      }
      item
    } else {
      item
    }
  }

  // NOTE(olafur): it's hacky to use `String.replaceAllLiterally("x$1", paramName)`, ideally we would use the
  // signature printer to pretty-print the signature from scratch with parameter names. However, that approach
  // is tricky because it would require us to JSON serialize/deserialize Scala compiler types. The reason
  // we don't print Java parameter names in `textDocument/completions` is because that requires parsing the
  // library dependency sources which is slow and `Thread.interrupt` cancellation triggers the `*-sources.jar`
  // to close causing other problems.
  private def replaceJavaParameters(
      info: SymbolDocumentation,
      detail: String
  ): String = {
    info
      .parameters()
      .asScala
      .iterator
      .zipWithIndex
      .foldLeft(detail) {
        case (accum, (param, i)) =>
          accum.replaceAllLiterally(
            s"x$$${i + 1}",
            param.displayName()
          )
      }
  }

  def fullDocstring(gsym: Symbol): String = {
    def docs(gsym: Symbol): String =
      symbolDocumentation(gsym).fold("")(_.docstring())
    val gsymDoc = docs(gsym)
    def keyword(gsym: Symbol): String =
      if (gsym.isClass) "class"
      else if (gsym.isTrait) "trait"
      else if (gsym.isJavaInterface) "interface"
      else if (gsym.isModule) "object"
      else ""
    val companion = gsym.companion
    if (companion == NoSymbol || isJavaSymbol(gsym)) {
      if (gsymDoc.isEmpty) {
        if (gsym.isAliasType) {
          fullDocstring(gsym.info.dealias.typeSymbol)
        } else if (gsym.isMethod) {
          gsym.info.finalResultType match {
            case SingleType(_, sym) =>
              fullDocstring(sym)
            case _ =>
              ""
          }
        } else ""
      } else {
        gsymDoc
      }
    } else {
      val companionDoc = docs(companion)
      if (companionDoc.isEmpty) gsymDoc
      else if (gsymDoc.isEmpty) companionDoc
      else {
        List(
          s"""|### ${keyword(companion)} ${companion.name}
              |$companionDoc
              |""".stripMargin,
          s"""|### ${keyword(gsym)} ${gsym.name}
              |${gsymDoc}
              |""".stripMargin
        ).sorted.mkString("\n")
      }
    }
  }
}
