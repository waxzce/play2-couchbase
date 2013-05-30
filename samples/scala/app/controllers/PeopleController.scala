package controllers

import org.ancelin.play2.couchbase.CouchbaseController
import play.api.mvc.{Action, Controller}
import java.util.UUID
import models.People
import models.Peoples
import models.Peoples._
import scala.concurrent.Future
import play.api.libs.json._
import play.api.libs.iteratee.{Enumeratee, Enumerator}
import java.util.concurrent.TimeUnit
import play.api.libs.concurrent.Promise

object PeopleController extends Controller with CouchbaseController {

  val peopleEnumerator: Enumerator[List[People]] = Enumerator.generateM[List[People]](
    Promise.timeout(Some, 500, TimeUnit.MILLISECONDS).flatMap( n => Peoples.findAll().map( Option( _ ) ) )
  )

  val jsonTransformer: Enumeratee[List[People], String] = Enumeratee.map { list =>
    val arr = Json.stringify( Json.toJson( list ) )
    "data: {\"peoples\":" + arr + "}\n\n"
  }

  def index() = Action {
     Async{
       Peoples.findAll().map(peoples => Ok(views.html.all(peoples)))
     }
  }

  def peoples() = Action {
    Ok.feed( peopleEnumerator &> jsonTransformer ).as( "text/event-stream" )
  }

  def show(id: String) = Action {
    Async {
      Peoples.findById(id).map { maybePeople =>
          maybePeople.map( people => Ok( Peoples.peopleWriter.writes(people) ) ).getOrElse( NotFound )
       }
    }
  }

  def create() = Action { implicit request =>
    Async {
      Peoples.peopleForm.bindFromRequest.fold(
        errors => Future(BadRequest("Not good !!!!")),
        people => {
          val peopleWithID = people.copy(id = Some(UUID.randomUUID().toString))
          Peoples.save(peopleWithID).map { status =>
            Ok( Json.obj( "success" -> status.isSuccess,"message" -> status.getMessage, "people" -> Peoples.peopleWriter.writes(people) ) )
          }
        }
      )
    }
  }

  def delete(id: String) = Action {
    Async {
      Peoples.remove(id).map { status =>
        Ok( Json.obj( "success" -> status.isSuccess,"message" -> status.getMessage) )
      }
    }
  }
}
