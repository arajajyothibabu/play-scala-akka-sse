package controllers

import javax.inject._

import play.api.mvc._
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.stream.scaladsl.{Source, StreamConverters}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.EventSource
import play.api.libs.iteratee.{Concurrent, Iteratee}

case class Start(out: Concurrent.Channel[String])
case class NewItem(name: String)

class Listener extends Actor {

  var out: Option[Concurrent.Channel[String]] = None
  def receive = {
    case Start(out)    => print(out);this.out = Some(out)
    case NewItem(name) => print(name);this.out.map(_.push(name))
  }
}

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  val system = ActorSystem("SSE")

  val listener = system.actorOf(Props[Listener], "listener")

  val (out, channel) = Concurrent.broadcast[String]

  listener ! Start(channel)

  /**
   * Create an Action to render an HTML page with a welcome message.
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def postItem = Action.async(parse.json) { implicit request =>
    val postedItem = (request.body \ "name").validate[String].getOrElse("Undefined")
    listener ! NewItem(postedItem)
    Future(Ok(postedItem))
  }

  def handleStream = Action { implicit request =>
    val consume = Iteratee.consume[String]()
    val resultFromEnumerator = Iteratee.flatten(out(consume)).run
    val source = Source.fromFuture(resultFromEnumerator)
    Ok.chunked(source via EventSource.flow).as("text/event-stream")
  }

}
