package scala.meta.internal.metals

import java.util.Properties
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import scala.collection.JavaConverters._
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

object Configs {

  final case class GlobSyntaxConfig(value: String) {
    import GlobSyntaxConfig._
    def isUri: Boolean = this == uri
    def isVscode: Boolean = this == vscode
    def registrationOptions(
        workspace: AbsolutePath
    ): DidChangeWatchedFilesRegistrationOptions = {
      val root: String =
        if (isVscode) workspace.toString()
        else workspace.toURI.toString.stripSuffix("/")
      new DidChangeWatchedFilesRegistrationOptions(
        List(
          new FileSystemWatcher(s"$root/*.sbt"),
          new FileSystemWatcher(s"$root/project/*.{scala,sbt}"),
          new FileSystemWatcher(s"$root/project/project/*.{scala,sbt}"),
          new FileSystemWatcher(s"$root/project/build.properties")
        ).asJava
      )
    }
  }

  object GlobSyntaxConfig {
    def uri = new GlobSyntaxConfig("uri")
    def vscode = new GlobSyntaxConfig("vscode")
    def default = new GlobSyntaxConfig(
      System.getProperty("metals.glob-syntax", uri.value)
    )
  }

  object CompilersConfig {
    def apply(
        props: Properties = System.getProperties
    ): PresentationCompilerConfigImpl = {
      PresentationCompilerConfigImpl(
        MetalsServerConfig.binaryOption("metals.pc.debug", default = false),
        Option(props.getProperty("metals.signature-help-command")),
        overrideDefFormat =
          Option(props.getProperty("metals.override-def-format")) match {
            case Some("unicode") => OverrideDefFormat.Unicode
            case Some("ascii") => OverrideDefFormat.Ascii
            case _ => OverrideDefFormat.Ascii
          },
        isCompletionItemDetailEnabled = MetalsServerConfig.binaryOption(
          "metals.completion-item.detail",
          default = true
        ),
        isCompletionItemDocumentationEnabled = MetalsServerConfig.binaryOption(
          "metals.completion-item.documentation",
          default = true
        ),
        isHoverDocumentationEnabled = MetalsServerConfig.binaryOption(
          "metals.hover.documentation",
          default = true
        ),
        isSignatureHelpDocumentationEnabled = MetalsServerConfig.binaryOption(
          "metals.signature-help.documentation",
          default = true
        )
      )
    }
  }

}
