package scala.meta.internal.metals.debug

import java.net.Socket
import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.google.gson.JsonElement
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug._
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.jsonrpc.debug.messages.{
  DebugResponseMessage => Response
}
import org.eclipse.lsp4j.jsonrpc.debug.messages.{DebugRequestMessage => Request}
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.lsp4j.jsonrpc.messages.{NotificationMessage => Notification}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.metals.Cancelable
import scala.reflect.ClassTag
import scala.meta.internal.metals.JsonParser._
import scala.reflect.classTag

private[debug] final class RemoteServer(
    socket: Socket,
    listener: RemoteServer.Listener
)(implicit ec: ExecutionContext)
    extends IDebugProtocolServer
    with Cancelable {

  private val remote = new SocketEndpoint(socket)
  private val ongoing = new TrieMap[String, Response => Unit]()
  private val id = new AtomicInteger(0)
  lazy val listening: Future[Unit] = Future(listen())

  override def initialize(
      args: InitializeRequestArguments
  ): CompletableFuture[Capabilities] = {
    sendRequest("initialize", args)
  }

  override def launch(
      args: util.Map[String, AnyRef]
  ): CompletableFuture[Void] = {
    sendRequest("launch", args)
  }

  override def configurationDone(
      args: ConfigurationDoneArguments
  ): CompletableFuture[Void] = {
    sendRequest("configurationDone", args)
  }

  override def disconnect(
      args: DisconnectArguments
  ): CompletableFuture[Void] = {
    sendRequest("disconnect", args)
  }

  private def listen(): Unit = {
    remote.listen {
      case response: Response =>
        ongoing.remove(response.getId) match {
          case Some(callback) =>
            callback(response)
          case None =>
            scribe.error(s"Response to invalid message: [$response]")
        }
      case notification: Notification =>
        notification.getMethod match {
          case "output" =>
            notify[OutputEventArguments](notification, listener.onOutput)
          case "terminated" =>
            listener.onTerminated()
          case _ =>
            scribe.debug(s"Unsupported notification: ${notification.getMethod}")
        }
      case msg =>
        scribe.error(s"Message [$msg] is not supported")
    }
  }

  private def notify[A: ClassTag](msg: Notification, f: A => Unit): Unit = {
    msg.getParams match {
      case json: JsonElement =>
        json.as[A].map(f).recover {
          case e => scribe.error(s"Could not handle notification [msg]", e)
        }
      case _ =>
        scribe.error(s"Not a json: ${msg.getParams}")
    }
  }

  private def sendRequest[A, B: ClassTag](
      endpoint: String,
      arg: A
  ): CompletableFuture[B] = {
    val request = new Request()
    request.setId(id.getAndIncrement())
    request.setMethod(endpoint)
    request.setParams(arg)

    val promise = Promise[Response]()
    ongoing.put(request.getId, response => promise.success(response))
    remote.consume(request)

    val expectedType = classTag[B].runtimeClass.asInstanceOf[Class[B]]
    val response = promise.future.flatMap { response =>
      response.getResult match {
        case null if expectedType == classOf[Void] =>
          Future[Void](null).asInstanceOf[Future[B]]
        case json: JsonElement =>
          Future.fromTry(json.as[B])
        case result =>
          Future.failed(new IllegalStateException(s"not a json: $result"))
      }
    }

    response.onTimeout(90, TimeUnit.SECONDS)(logTimeout(endpoint)).asJava
  }

  private def logTimeout(endpoint: String): Unit = {
    scribe.error(s"Timeout when waiting for a response to $endpoint request")
  }

  override def cancel(): Unit = {
    remote.cancel()
  }
}

object RemoteServer {
  trait Listener {
    def onOutput(output: OutputEventArguments): Unit
    def onTerminated(): Unit
  }

  def apply(socket: Socket, listener: Listener)(
      implicit ec: ExecutionContext
  ): RemoteServer = {
    val server = new RemoteServer(socket, listener)
    server.listening.onComplete(_ => server.cancel())
    server
  }
}
