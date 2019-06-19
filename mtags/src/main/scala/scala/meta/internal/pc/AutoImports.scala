package scala.meta.internal.pc

import scala.reflect.internal.FatalError

trait AutoImports { this: MetalsGlobal =>

  /**
   * A position to insert new imports
   *
   * @param offset the offset where to place the import.
   * @param indent the indentation at which to place the import.
   */
  case class AutoImportPosition(offset: Int, indent: Int) {
    def this(offset: Int, text: String) =
      this(offset, inferIndent(offset, text))
  }

  def doLocateImportContext(
      pos: Position,
      autoImport: Option[AutoImportPosition]
  ): Context = {
    try doLocateContext(
      autoImport.fold(pos)(i => pos.focus.withPoint(i.offset))
    )
    catch {
      case _: FatalError =>
        (for {
          unit <- getUnit(pos.source)
          tree <- unit.contexts.headOption
        } yield tree.context).getOrElse(NoContext)
    }
  }

  def autoImportPosition(
      pos: Position,
      text: String
  ): Option[AutoImportPosition] = {
    if (lastVisistedParentTrees.isEmpty) {
      locateTree(pos)
    }
    lastVisistedParentTrees.headOption match {
      case Some(_: Import) => None
      case _ =>
        val enclosingPackage = lastVisistedParentTrees.collectFirst {
          case pkg: PackageDef => pkg
        }
        enclosingPackage match {
          case Some(pkg)
              if pkg.symbol != rootMirror.EmptyPackage ||
                pkg.stats.headOption.exists(_.isInstanceOf[Import]) =>
            val lastImport = pkg.stats
              .takeWhile(_.isInstanceOf[Import])
              .lastOption
              .getOrElse(pkg.pid)
            Some(
              new AutoImportPosition(
                pos.source.lineToOffset(lastImport.pos.focusEnd.line),
                text
              )
            )
          case _ =>
            Some(AutoImportPosition(0, 0))
        }
    }
  }

}
