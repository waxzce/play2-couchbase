package org.ancelin.play2.couchbase.store

import akka.actor.{ActorSystem, Props, Actor, ActorRef}
import java.util.concurrent.{TimeUnit, ConcurrentHashMap}
import org.ancelin.play2.couchbase.{Constants, Couchbase, CouchbaseBucket}
import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectStreamClass}
import scala.concurrent.{Await, ExecutionContext}
import play.api.libs.json.{Format, Json}
import play.api.Play
import com.couchbase.client.protocol.views.{ComplexKey, Stale, Query}
import net.spy.memcached.transcoders.{Transcoder, SerializingTranscoder}
import scala.parallel.Future
import net.spy.memcached.ops.OperationStatus
import scala.concurrent.duration.Duration

case class Message(payload: Any, eventId: Long = 0L, aggregateId: Long = 0L, timestamp: Long = System.currentTimeMillis(), version: Int = 0)
case class CouchbaseMessage(blobKey: String, eventId: Long = 0L, aggregateId: Long = 0L, timestamp: Long = System.currentTimeMillis(), version: Int = 0, datatype: String = "eventsourcing-message")

object Message {
  def create(payload: Any) = Message(payload, 0L, 0L, System.currentTimeMillis(), 0)
  def create(payload: Any, id: Long) = Message(payload, id, 0L, System.currentTimeMillis(), 0)
  def create(payload: Any, id: Long, aggregate: Long) = Message(payload, id, aggregate, System.currentTimeMillis(), 0)
  def create(payload: Any, id: Long, aggregate: Long, version: Int) = Message(payload, id, aggregate, System.currentTimeMillis(), version)
}

case class WriteInJournal(message: Message, replyTo: ActorRef)
case class WrittenInJournal(message: Message)
case class Replay(message: Message)

trait EventStored extends Actor {

  private val couchbaseJournal = CouchbaseJournal(context.system)

  abstract override def receive = {
    case msg: Message => {
      couchbaseJournal.journal.forward(WriteInJournal(msg, self))
    }
    case WrittenInJournal(msg) => {
      super.receive(msg)
      super.receive(msg.payload)
    }
    case Replay(msg) => {
      super.receive(msg)
      super.receive(msg.payload)
    }
  }

  override def postStop() {
    couchbaseJournal.actors = couchbaseJournal.actors.filter( _ != self )
  }
}

class CouchbaseJournalActor(bucket: CouchbaseBucket, format: Format[CouchbaseMessage], ec: ExecutionContext) extends Actor {
  def receive = {
    case WriteInJournal(msg, to) => {
      val blobKey = s"eventsourcing-message-${msg.eventId}-${msg.aggregateId}-${msg.timestamp}-blob"
      val dataKey = s"eventsourcing-message-${msg.eventId}-${msg.aggregateId}-${msg.timestamp}-data"
      val dataMsg = CouchbaseMessage(blobKey, msg.eventId, msg.aggregateId, msg.timestamp, msg.version)
      Couchbase.set(dataKey, dataMsg)(bucket, format, ec).map(_ => BlobRepo.set(bucket, blobKey, msg.payload))(ec)
      to ! WrittenInJournal(msg)
    }
    case _ =>
  }
}

class CouchbaseJournal(system: ActorSystem, bucket: CouchbaseBucket, format: Format[CouchbaseMessage]) {
  implicit val ec = Couchbase.couchbaseExecutor(Play.current)
  val journal: ActorRef = system.actorOf(Props(new CouchbaseJournalActor(bucket, format, ec)))
  var actors: List[ActorRef] = List[ActorRef]()
  val byDataType = Couchbase.view("event-sourcing", "datatype")(bucket, ec)
  val byTimestamp = Couchbase.view("event-sourcing", "by_timestamp")(bucket, ec)
  val byEventId = Couchbase.view("event-sourcing", "by_eventid")(bucket, ec)
  val byAggregateId = Couchbase.view("event-sourcing", "by_aggregateid")(bucket, ec)
  val byVersion = Couchbase.view("event-sourcing", "by_version")(bucket, ec)

  val all = new Query().setIncludeDocs(true).setStale(Stale.FALSE).setDescending(false)
  def allFrom(from: Long) = new Query().setIncludeDocs(true).setStale(Stale.FALSE).setDescending(false).setRangeStart(ComplexKey.of(from.asInstanceOf[AnyRef])).setRangeEnd(ComplexKey.of(Long.MaxValue.asInstanceOf[AnyRef]))
  def allUntil(to: Long) = new Query().setIncludeDocs(true).setStale(Stale.FALSE).setDescending(false).setRangeStart(ComplexKey.of(0L.asInstanceOf[AnyRef])).setRangeEnd(ComplexKey.of(to.asInstanceOf[AnyRef]))

  def processorOf(props: Props): ActorRef = {
    val actorRef = system.actorOf(props)
    actors = actors :+ actorRef
    actorRef
  }
  def processorOf(props: Props, name: String): ActorRef = {
    val actorRef = system.actorOf(props, name)
    actors = actors :+ actorRef
    actorRef
  }

  def replayAll() = {
    byTimestamp.flatMap { view =>
      Couchbase.find[CouchbaseMessage](view)(all)(bucket, format, ec)
    }.map { messages =>
      messages.map { message =>
        val msg = Message(BlobRepo.get(bucket, message.blobKey), message.eventId, message.aggregateId, message.timestamp, message.version)
        actors.foreach { actor =>
          actor ! Replay(msg)
        }
      }
    }
  }

  def replayFrom(timestamp: Long) = {
    byTimestamp.flatMap { view =>
      Couchbase.find[CouchbaseMessage](view)(allFrom(timestamp))(bucket, format, ec)
    }.map { messages =>
      messages.map { message =>
        val msg = Message(BlobRepo.get(bucket, message.blobKey), message.eventId, message.aggregateId, message.timestamp, message.version)
        actors.foreach { actor =>
          actor ! Replay(msg)
        }
      }
    }
  }

  def replayUntil(timestamp: Long) = {
    byTimestamp.flatMap { view =>
      Couchbase.find[CouchbaseMessage](view)(allUntil(timestamp))(bucket, format, ec)
    }.map { messages =>
      messages.map { message =>
        val msg = Message(BlobRepo.get(bucket, message.blobKey), message.eventId, message.aggregateId, message.timestamp, message.version)
        actors.foreach { actor =>
          actor ! Replay(msg)
        }
      }
    }
  }

  def replayFromId(id: Long) = {
    byEventId.flatMap { view =>
      Couchbase.find[CouchbaseMessage](view)(allFrom(id))(bucket, format, ec)
    }.map { messages =>
      messages.map { message =>
        val msg = Message(BlobRepo.get(bucket, message.blobKey), message.eventId, message.aggregateId, message.timestamp, message.version)
        actors.foreach { actor =>
          actor ! Replay(msg)
        }
      }
    }
  }

  def replayUntilId(id: Long) = {
    byEventId.flatMap { view =>
      Couchbase.find[CouchbaseMessage](view)(allUntil(id))(bucket, format, ec)
    }.map { messages =>
      messages.map { message =>
        val msg = Message(BlobRepo.get(bucket, message.blobKey), message.eventId, message.aggregateId, message.timestamp, message.version)
        actors.foreach { actor =>
          actor ! Replay(msg)
        }
      }
    }
  }
}

object CouchbaseJournal {
  val format = Json.format[CouchbaseMessage]
  val journals: ConcurrentHashMap[String, CouchbaseJournal] = new ConcurrentHashMap[String, CouchbaseJournal]()
  def apply(system: ActorSystem, bucket: CouchbaseBucket) = {
    if (!journals.containsKey(system.name)) {
      // TODO : check if view are here. If not, insert them
      val ec = Couchbase.couchbaseExecutor(Play.current)
      val eventSourcingDesignDoc =
        """ {
            "views":{
               "datatype": {
                   "map": "function (doc, meta) { if (doc.datatype === 'eventsourcing-message') { emit(meta.id, null); } } "
               },
               "by_timestamp": {
                   "map": "function (doc, meta) { if (doc.datatype === 'eventsourcing-message') { emit(meta.timestamp, null); } } "
               },
               "by_version": {
                   "map": "function (doc, meta) { if (doc.datatype === 'eventsourcing-message') { emit(meta.version, null); } } "
               },
               "by_aggregateId": {
                   "map": "function (doc, meta) { if (doc.datatype === 'eventsourcing-message') { emit(meta.aggregateId, null); } } "
               },
               "by_eventId": {
                   "map": "function (doc, meta) { if (doc.datatype === 'eventsourcing-message') { emit(meta.eventId, null); } } "
               }
            }
        } """
      try {
        Await.result(Couchbase.createDesignDoc("event-sourcing", eventSourcingDesignDoc)(bucket, ec), Duration(2, TimeUnit.SECONDS))
      } catch {
        case e => println(e)
      }
      journals.putIfAbsent(system.name, new CouchbaseJournal(system, bucket, format))
    }
    journals.get(system.name)
  }
  def apply(system: ActorSystem) = {
    journals.get(system.name)
  }
}

private object MessageSerializer extends SerializingTranscoder {

  override protected def deserialize(data: Array[Byte]): java.lang.Object = {
    new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(data)) {
      override protected def resolveClass(desc: ObjectStreamClass) = {
        Class.forName(desc.getName(), false, play.api.Play.current.classloader)
      }
    }.readObject()
  }

  override protected def serialize(obj: java.lang.Object) = {
    val bos: ByteArrayOutputStream = new ByteArrayOutputStream()
    new ObjectOutputStream(bos).writeObject(obj)
    bos.toByteArray()
  }
}

private object BlobRepo {

  lazy val tc = MessageSerializer.asInstanceOf[Transcoder[Any]]

  def get(bucket: CouchbaseBucket, key: String) = {
    val future = bucket.couchbaseClient.asyncGet(key, tc)
    future.get(Constants.timeout, TimeUnit.MILLISECONDS)
  }

  def set(bucket: CouchbaseBucket, key: String, value: Any) {
    bucket.couchbaseClient.set(key, -1, value, tc)
  }
}

