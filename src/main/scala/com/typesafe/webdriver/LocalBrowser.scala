package com.typesafe.webdriver

import akka.actor._
import spray.json.JsObject
import scala.sys.process._
import com.typesafe.webdriver.LocalBrowser._
import scala.Some

/**
 * Provides an Actor on behalf of a browser. Browsers are represented as operating system processes and are
 * communicated with by using the http/json based WebDriver protocol.
 * @param sessionProps the properties required in order to produce a session actor.
 * @param maybeArgs a sequence of command line arguments used to launch the browser from the command line. If this is
 *                  set to None then the process is deemed to be controlled outside of this actor.
 */
class LocalBrowser(sessionProps: Props, maybeArgs: Option[Seq[String]]) extends Actor with FSM[State, Option[Process]] {

  startWith(Uninitialized, None)

  when(Uninitialized) {
    case Event(Startup, None) =>
      maybeArgs match {
        case Some(args) =>
          val p = Process(args).run(ProcessLogger(log.debug, log.error))
          goto(Started) using Some(p)
        case None => goto(Started) using None
      }
  }

  when(Started) {
    case Event(CreateSession(desiredCapabilities, requiredCapabilities), _) =>
      val session = context.actorOf(sessionProps, "session")
      session ! Session.Connect(desiredCapabilities, requiredCapabilities)
      sender ! session
      stay()
  }

  onTermination {
    case StopEvent(_, _, maybeProcess) =>
      maybeProcess.foreach(p => p.destroy())
  }

  initialize()
}

object LocalBrowser {

  /**
   * Start a browser. This is typically sent upon having obtained an actor ref to the browser.
   */
  case object Startup

  /**
   * Start a new session.
   */
  case class CreateSession(desiredCapabilities:JsObject=JsObject(), requiredCapabilities:JsObject=JsObject())


  // Internal FSM states

  private[webdriver] trait State

  private[webdriver] case object Uninitialized extends State

  private[webdriver] case object Started extends State

}

/**
 * Used to manage a local instance of PhantomJs. The default is to assume that phantomjs is on the path.
 */
object PhantomJs {
  def props(arf: ActorRefFactory, host: String = "127.0.0.1", port: Int = 8910, _args:Seq[String]=Seq.empty ): Props = {
    val wd = new PhantomJsWebDriverCommands(arf, host, port)
    val args = Some(Seq("phantomjs", s"--webdriver=$host:$port") ++ _args)
    Props(classOf[LocalBrowser], Session.props(wd), args)
  }
}

/**
 * Used to manage a JVM resident browser via HtmlUnit.
 */
object HtmlUnit {
  def props(): Props = {
    val wd = new HtmlUnitWebDriverCommands
    Props(classOf[LocalBrowser], Session.props(wd), None)
  }
}
