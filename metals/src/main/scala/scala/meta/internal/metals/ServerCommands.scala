package scala.meta.internal.metals

import scala.util.matching.Regex

/**
 * LSP commands supported by the Metals language server.
 */
object ServerCommands {

  /**
   * Walk all files in the workspace and index where symbols are defined.
   *
   * Is automatically run once after `initialized` notification and incrementally
   * updated on file wathching events. A language client that doesn't support
   * file watching can run this manually instead. It should not be much slower
   * than walking the entire file tree and reading `*.scala` files to string,
   * indexing itself is cheap.
   */
  val ScanWorkspaceSources = "workspace.sources.scan"

  /**
   * Unconditionally `sbt bloopInstall` and re-connect to the build server.
   *
   * Is by default automatically managed by the language server, but sometimes it's
   * useful to manually trigger it instead.
   */
  val ImportBuild = "build.import"

  /**
   * Unconditionally cancel existing build server connection and re-connect.
   *
   * Useful if you manually run `bloopInstall` from the sbt shell, in which
   * case this command is needed to tell metals to communicate with the bloop
   * server.
   */
  val ConnectBuildServer = "build.connect"

  /**
   * Open the browser at the given url.
   */
  val OpenBrowser: Regex = "browser.open-url:(.*)".r
  def OpenBrowser(url: String): String = s"browser.open-url:$url"

}
