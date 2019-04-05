package scala.meta.internal.metals

import java.util
import java.util.Collections
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeCapabilities
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta._
import scala.meta.inputs.Position
import scala.meta.internal.metals.FoldingRangeProvider._
import scala.meta.io.AbsolutePath

final class FoldingRangeProvider(val trees: Trees, foldOnlyLines: Boolean) {
  def getRangedFor(path: AbsolutePath): util.List[FoldingRange] = {
    trees
      .get(path)
      .map(findFoldingRanges)
      .getOrElse(util.Collections.emptyList())
  }

  private def findFoldingRanges(tree: Tree): util.List[FoldingRange] = {
    val ranges = new FoldingRanges(foldOnlyLines)

    tree traverse {
      case block: Term.Block =>
        ranges.add(Region, block.pos)
      case template: Template =>
        ranges.add(Region, template.pos)
      case loop: Term.For =>
        val startLine = loop.pos.startLine
        val startColumn = loop.pos.startColumn + 3 // just after "for" since there may be no whitespace (e.g. "for{")

        val endLine = loop.body.pos.startLine
        val endColumn = loop.body.pos.startColumn // must be exact$startColumn, since it can be "}{"

        val range = new FoldingRange(startLine, endLine)
        range.setStartCharacter(startColumn)
        range.setEndCharacter(endColumn)

        ranges.add(Region, range)

      // it preserves the whitespaces between "yield" token and the body
      case loop: Term.ForYield =>
        val startLine = loop.pos.startLine
        val startColumn = loop.pos.startColumn + 3 // just after "for" since there may be no whitespace (e.g. "for{")

        val range = loop.tokens.collectFirst {
          case token: Token.KwYield => // fold up to the 'yield' token
            val endLine = token.pos.startLine
            val endColumn = token.pos.startColumn

            val range = new FoldingRange(startLine, endLine)
            range.setStartCharacter(startColumn)
            range.setEndCharacter(endColumn)
            range
        }

        range.foreach(ranges.add(Region, _))

      case matchTerm: Term.Match => {
        // range for the whole match block
        val wholeMatchRange = matchTerm.tokens.collectFirst {
          case token: Token.KwMatch => // fold just behind the 'match' token
            val startLine = token.pos.endLine
            val startColumn = token.pos.endColumn

            val range = new FoldingRange(startLine, matchTerm.pos.endLine)
            range.setStartCharacter(startColumn)
            range.setEndCharacter(matchTerm.pos.endColumn)
            range
        }
        wholeMatchRange.foreach(ranges.add(Region, _))
      }
    }

    ranges.get
  }
}

object FoldingRangeProvider {
  val foldingThreshold = 2 // e.g. {}
  val Region = "region"

  def apply(
      trees: Trees,
      capabilities: FoldingRangeCapabilities
  ): FoldingRangeProvider = {
    val foldOnlyLines: Boolean =
      if (capabilities.getLineFoldingOnly == null) false
      else capabilities.getLineFoldingOnly

    new FoldingRangeProvider(trees, foldOnlyLines)
  }
}

final class FoldingRanges(foldOnlyLines: Boolean) {
  private val allRanges = new util.ArrayList[FoldingRange]()

  def get: util.List[FoldingRange] = Collections.unmodifiableList(allRanges)

  def add(kind: String, pos: Position): Unit = {
    import MetalsEnrichments._
    val range = pos.toLSPFoldingRange
    add(kind, range)
  }

  def add(kind: String, range: FoldingRange): Unit = {
    range.setKind(kind)
    add(range)
  }

  def add(range: FoldingRange): Unit = {
    if (isNotCollapsed(range)) {
      if (foldOnlyLines) {
        range.setEndLine(range.getEndLine - 1) // we want to preserve the last line containing e.g. '}'
      }

      allRanges.add(range)
    }
  }

  // examples of collapsed: "class A {}" or "def foo = {}"
  private def isNotCollapsed(range: FoldingRange): Boolean =
    spansMultipleLines(range) || foldsMoreThanThreshold(range)

  private def spansMultipleLines(range: FoldingRange): Boolean =
    range.getStartLine < range.getEndLine

  /**
   * Calling this method makes sense only when range does not spanMultipleLines
   */
  private def foldsMoreThanThreshold(range: FoldingRange): Boolean =
    range.getEndCharacter - range.getStartCharacter > foldingThreshold
}
