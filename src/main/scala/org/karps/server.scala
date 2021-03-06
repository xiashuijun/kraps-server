package org.karps

import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.slf4j.{StrictLogging => Logging}
import spray.can.Http
import spray.http.MediaTypes._
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol
import spray.routing._

import org.karps.structures.{BatchComputationResultJson, ComputationResultJson, UntypedNodeJson}
import org.karps.ops.{HdfsPath, HdfsResourceResult}


object Boot extends App {

  val karpsPort = 8081
  val interface = "localhost"

  SparkRegistry.setup()

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("karps-on-spray-can")

  // create and start our service actor
  val service = system.actorOf(Props[MyServiceActor], "demo-service")

  implicit val timeout = Timeout(5.seconds)
  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ? Http.Bind(service, interface = interface, port = karpsPort)
}

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class MyServiceActor extends Actor with MyService {

  // TODO: move outside an actor.
  val manager = {
    val m = new Manager()
    m.init()
    m
  }

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
  def receive = runRoute(myRoute)
}


case class Person(name: String, favoriteNumber: Int)

object KarpsServerImplicits extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val PortofolioFormats = jsonFormat2(Person)
  implicit val UntypedNodeJsonF = jsonFormat7(UntypedNodeJson)
  implicit val HdfsResourceResultF = jsonFormat3(HdfsResourceResult)
}

import KarpsServerImplicits._



// this trait defines our service behavior independently from the service actor
trait MyService extends HttpService with Logging {

  val manager: Manager

  val myRoute =
    path("") {
      get {
        respondWithMediaType(`text/html`) { // XML is marshalled to `text/xml` by default, so we simply override here
          complete {
            <html>
              <body>
                <h1>Say hello to <i>spray-routing</i> on <i>spray-can</i>!</h1>
              </body>
            </html>
          }
        }
      }
    } ~
    path("sessions" / Segment / "create") { sessionIdTxt =>
      val sessionId = SessionId(sessionIdTxt)
      post {
        manager.create(sessionId)
        complete("xxx")
      }
    } ~
    path("resources_status" / Segment ) { sessionIdTxt =>
      val sessionId = SessionId(sessionIdTxt)

      post {
        entity(as[Seq[String]]) { paths =>
          val ps = paths.map(HdfsPath.apply)
          logger.debug(s"Requesting status for paths $ps")
          val s = manager.resourceStatus(sessionId, ps)
          s.foreach(st => logger.debug(s"Status received: $st"))
          complete(s)
        }
      }
    } ~
    path("sessions" / Segment ) { sessionIdTxt => // TOOD: that one is useless
      val sessionId = SessionId(sessionIdTxt)

      get {
        complete {
          Person(sessionIdTxt, 32)
        }
      }
    } ~
    path("computations" / Segment / Segment / "create") { (sessionIdTxt, computationIdTxt) =>
      val sessionId = SessionId(sessionIdTxt)
      val computationId = ComputationId(computationIdTxt)

      post {
        entity(as[Seq[UntypedNodeJson]]) { nodes =>
          manager.execute(sessionId, computationId, nodes)
          complete(nodes.size.toString)
        }
      }
    } ~
    path("computations_status" / Segment / Segment / Rest ) {
        (sessionIdTxt, computationIdTxt, rest) =>
      val sessionId = SessionId(sessionIdTxt)
      val computationId = ComputationId(computationIdTxt)
      if (rest.isEmpty) {
        // We are being asked for the computation
        get {
          complete {
            val s = manager.statusComputation(sessionId, computationId).getOrElse(
              throw new Exception(s"$sessionId $computationId"))
            import ComputationResultJson._
            BatchComputationResultJson.fromResult(s)
          }
        }
      } else {
        // Asking for a given element.
        val p = Path.create(rest.split("/"))
        val gp = GlobalPath.from(sessionId, computationId, p)

        get {
          complete {
            val s = manager.status(gp).getOrElse(throw new Exception(gp.toString))
            ComputationResultJson.fromResult(s)
          }
        }
      }
    }
}