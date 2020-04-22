package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.decorations.PublishDecorationsParams

/**
 * Delegates requests/notifications to the underlying language client according to the user configuration.
 *
 * This wrapper class manages differences in how editors interpret LSP endpoints slightly differently,
 * especially the window/{logMessage,showMessage} notifications. For example, with vim-lsc the messages
 * from window/logMessage are always visible in the UI while in VS Code the logs are hidden by default.
 */
final class ConfiguredLanguageClient(
    initial: MetalsLanguageClient,
    config: MetalsServerConfig
)(implicit ec: ExecutionContext)
    extends DelegatingLanguageClient(initial) {

  private var clientCapabilities = ClientExperimentalCapabilities.Default
  private var initializationOptions = InitializationOptions.Default

  override def configure(capabilities: ClientExperimentalCapabilities): Unit = {
    clientCapabilities = capabilities
  }

  override def configure(options: InitializationOptions): Unit = {
    initializationOptions = options
  }

  override def shutdown(): Unit = {
    underlying = NoopLanguageClient
  }

  override def metalsStatus(params: MetalsStatusParams): Unit = {
    val statusBarIsOn = config.statusBar.isOn ||
      clientCapabilities.statusBarIsOn ||
      initializationOptions.statusBarIsOn

    if (statusBarIsOn) {
      underlying.metalsStatus(params)
    } else if (params.text.nonEmpty && !pendingShowMessage.get()) {
      if (config.statusBar.isShowMessage || clientCapabilities.statusBarIsShowMessage || initializationOptions.statusBarIsShowMessage) {
        underlying.showMessage(new MessageParams(MessageType.Log, params.text))
      } else if (config.statusBar.isLogMessage || clientCapabilities.statusBarIsLogMessage || initializationOptions.statusBarIsLogMessage) {
        underlying.logMessage(new MessageParams(MessageType.Log, params.text))
      } else {
        ()
      }
    } else {
      ()
    }
  }
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] = {
    val slowTaskIsOn = config.slowTask.isOn ||
      clientCapabilities.slowTaskProvider ||
      initializationOptions.slowTaskProvider

    if (slowTaskIsOn) {
      underlying.metalsSlowTask(params)
    } else {
      new CompletableFuture[MetalsSlowTaskResult]()
    }
  }
  override def showMessage(params: MessageParams): Unit = {
    underlying.showMessage(params)
  }

  private val pendingShowMessage = new AtomicBoolean(false)
  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    pendingShowMessage.set(true)
    val result = underlying.showMessageRequest(params)
    result.asScala.onComplete(_ => pendingShowMessage.set(false))
    result
  }

  override def logMessage(message: MessageParams): Unit = {
    val statusBarIsLogMessage = config.statusBar.isLogMessage ||
      clientCapabilities.statusBarIsLogMessage ||
      initializationOptions.statusBarIsLogMessage

    if (statusBarIsLogMessage && message.getType == MessageType.Log) {
      // window/logMessage is reserved for the status bar so we don't publish
      // scribe.{info,warn,error} logs here. Users should look at .metals/metals.log instead.
      ()
    } else {
      underlying.logMessage(message)
    }
  }

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    val executeClientCommandProvider = config.executeClientCommand.isOn ||
      clientCapabilities.executeClientCommandProvider ||
      initializationOptions.executeClientCommandProvider

    if (executeClientCommandProvider) {
      params.getCommand match {
        case ClientCommands.RefreshModel()
            if !clientCapabilities.debuggingProvider =>
          () // ignore
        case _ =>
          underlying.metalsExecuteClientCommand(params)
      }
    }
  }

  override def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[MetalsInputBoxResult] = {
    val isInputBoxEnabled = config.isInputBoxEnabled ||
      clientCapabilities.inputBoxProvider ||
      initializationOptions.inputBoxProvider

    if (isInputBoxEnabled) {
      underlying.metalsInputBox(params)
    } else {
      CompletableFuture.completedFuture(MetalsInputBoxResult(cancelled = true))
    }
  }

  override def metalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[MetalsQuickPickResult] = {
    if (clientCapabilities.quickPickProvider || initializationOptions.quickPickProvider) {
      underlying.metalsQuickPick(params)
    } else {
      showMessageRequest(
        toShowMessageRequestParams(params)
      ).asScala
        .map(item => MetalsQuickPickResult(itemId = item.getTitle()))
        .asJava
    }
  }

  override def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit = {
    if (clientCapabilities.decorationProvider) {
      underlying.metalsPublishDecorations(params)
    }
  }

  private def toShowMessageRequestParams(
      params: MetalsQuickPickParams
  ): ShowMessageRequestParams = {
    val result = new ShowMessageRequestParams()
    result.setMessage(params.placeHolder)
    result.setActions(params.items.map(item => new MessageActionItem(item.id)))
    result
  }

}
