
package ee.cone.c4actor

import java.util.Collections.singletonMap
import java.util.UUID
import java.util.concurrent.{CompletableFuture, Future}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import ee.cone.c4actor.QProtocol.{Leader, Updates}
import ee.cone.c4assemble.Single
import ee.cone.c4assemble.Types.World
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.annotation.tailrec
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.immutable.{Map, Queue}

class KafkaRawQSender(bootstrapServers: String, inboxTopicPrefix: String)(
  producer: CompletableFuture[Producer[Array[Byte], Array[Byte]]] = new CompletableFuture()
) extends RawQSender with Executable {
  def run(ctx: ExecutionContext): Unit = {
    val props = Map[String, Object](
      "bootstrap.servers" → bootstrapServers,
      "acks" → "all",
      "retries" → "0",
      "batch.size" → "16384",
      "linger.ms" → "1",
      "buffer.memory" → "33554432",
      "compression.type" → "lz4",
      "max.request.size" → "10000000"
      // max.request.size -- seems to be uncompressed
      // + in broker config: message.max.bytes
    )
    val serializer = new ByteArraySerializer
    producer.complete(new KafkaProducer[Array[Byte], Array[Byte]](
      props.asJava, serializer, serializer
    ))
    ctx.onShutdown("Producer",() ⇒ producer.get.close())
  }
  def topicNameToString(topicName: TopicName): String = topicName match {
    case InboxTopicName() ⇒ s"$inboxTopicPrefix.inbox"
    case LogTopicName() ⇒ s"$inboxTopicPrefix.inbox.log"
    case NoTopicName ⇒ throw new Exception
  }
  private def sendStart(rec: QRecord): Future[RecordMetadata] = {
    //println(s"sending to server [$bootstrapServers] topic [${topicNameToString(rec.topic)}]")
    val value = if(rec.value.nonEmpty) rec.value else null
    val topic = topicNameToString(rec.topic)
    producer.get.send(new ProducerRecord(topic, 0, rec.key, value))
  }
  def send(recs: List[QRecord]): List[Long] = {
    val futures: List[Future[RecordMetadata]] = recs.map(sendStart)
    futures.map(_.get().offset())
  }
}

////

class KafkaQConsumerRecordAdapter(topicName: TopicName, rec: ConsumerRecord[Array[Byte], Array[Byte]]) extends QRecord {
  def topic: TopicName = topicName
  def key: Array[Byte] = rec.key
  def value: Array[Byte] = if(rec.value ne null) rec.value else Array.empty
  def offset: Option[Long] = Option(rec.offset)
  //rec.timestamp()
}

class KafkaActor(bootstrapServers: String, actorName: ActorName)(
    qMessages: QMessages, reducer: Reducer, rawQSender: KafkaRawQSender, initialObservers: List[Observer]
)(
  alive: AtomicBoolean = new AtomicBoolean(true)
) extends Executable {
  private def iterator(consumer: Consumer[Array[Byte], Array[Byte]]) = Iterator.continually{
    if(Thread.interrupted || !alive.get) throw new InterruptedException
    consumer.poll(200 /*timeout*/).asScala
      .map(new KafkaQConsumerRecordAdapter(NoTopicName, _)).toList
  }
  type BConsumer = Consumer[Array[Byte], Array[Byte]]
  private def initConsumer(ctx: ExecutionContext): BConsumer = {
    val deserializer = new ByteArrayDeserializer
    val props: Map[String, Object] = Map(
      "bootstrap.servers" → bootstrapServers,
      "enable.auto.commit" → "false"
      //"receive.buffer.bytes" → "1000000",
      //"max.poll.records" → "10001"
      //"group.id" → actorName.value //?pos
    )
    val consumer = new KafkaConsumer[Array[Byte], Array[Byte]](
      props.asJava, deserializer, deserializer
    )
    ctx.onShutdown("Consumer",() ⇒ {
      alive.set(false)
      consumer.wakeup()
    })
    consumer
  }
/*private def recoverWorld(consumer: BConsumer, part: List[TopicPartition], topicName: TopicName): AtomicReference[World] = {
  LEvent.delete(Updates("",Nil)).foreach(ev⇒
    rawQSender.send(List(qMessages.toRecord(topicName,qMessages.toUpdate(ev))))
  ) //! prevents hanging on empty topic
  val until = Single(consumer.endOffsets(part.asJava).asScala.values.toList)
  consumer.seekToBeginning(part.asJava)

  val recsIterator = iterator(consumer).flatten
  @tailrec def toQueue(queue: Queue[QRecord], count: Long, nonEmptyCount: Long): Queue[QRecord] = {
    if(count % 100000 == 0) println(count,nonEmptyCount)
    val rec: KafkaQConsumerRecordAdapter = recsIterator.next()
    if(rec.offset.get + 1 >= until){
      println(count,nonEmptyCount)
      queue.enqueue(rec)
    }
    else toQueue(queue.enqueue(rec), count+1L, nonEmptyCount+(if(rec.value.length>0) 1L else 0L))
  }
  val zeroRecord = qMessages.offsetUpdate(0L).map(qMessages.toRecord(topicName,_))
  val recsQueue = toQueue(Queue(zeroRecord:_*),0,0)
  val recsList = recsQueue.toList
  new AtomicReference(reducer.reduceRecover(reducer.createWorld(Map()), recsList))
}*/
  private def startIncarnation(world:  World): (Long,World⇒Boolean) = {
    val local = reducer.createTx(world)(Map())
    val leader = Leader(actorName.value,UUID.randomUUID.toString)
    val nLocal = LEvent.add(LEvent.update(leader)).andThen(qMessages.send)(local)
    (
      OffsetWorldKey.of(nLocal),
      world ⇒ By.srcId(classOf[Leader]).of(world).getOrElse(actorName.value,Nil).contains(leader)
    )
  }

  def run(ctx: ExecutionContext): Unit = {
    println("starting world recover...")
    val localWorldRef: AtomicReference[World] = ???
    val startOffset = qMessages.worldOffset(localWorldRef.get)
    println(s"world state recovered, next offset [$startOffset]")
    val consumer = initConsumer(ctx)
    try {
      val inboxTopicName = InboxTopicName()
      val inboxTopicPartition = List(new TopicPartition(rawQSender.topicNameToString(inboxTopicName), 0))
      println(s"server [$bootstrapServers] inbox [${rawQSender.topicNameToString(inboxTopicName)}]")
      consumer.assign(inboxTopicPartition.asJava)
      consumer.seek(Single(inboxTopicPartition),startOffset)
      val (incarnationNextOffset,checkIncarnation) = startIncarnation(localWorldRef.get)
      val observerContext = new ObserverContext(ctx, ()⇒localWorldRef.get)
      iterator(consumer).scanLeft(initialObservers){ (prevObservers, recs) ⇒
        for((rec:QRecord) ← recs.nonEmpty) try {
          localWorldRef.set(reducer.reduceRecover(localWorldRef.get,
            qMessages.toUpdates(actorName, rec.value) ::: qMessages.offsetUpdate(rec.offset.get + 1)
          ))
        } catch {
          case e: Exception ⇒ e.printStackTrace()// ??? exception to record
        }
        //println(s"then to receive: ${qMessages.worldOffset(localWorldRef.get)}")
        if(checkIncarnation(localWorldRef.get))
            prevObservers.flatMap(_.activate(observerContext))
        else prevObservers.map{
          case p: ProgressObserver ⇒
            val np = p.progress()
            if(p != np) println(s"loaded ${recs.lastOption.map(_.offset).getOrElse("?")}/${incarnationNextOffset-1}")
            np
          case o ⇒ o
        }

      }.foreach(_⇒())
    } finally {
      consumer.close()
    }
  }
}

