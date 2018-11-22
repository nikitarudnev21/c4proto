
package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{TxRef, Update, Updates}
import ee.cone.c4proto.{HasId, Protocol, ToByteString}

import scala.collection.immutable.{Queue, Seq}
import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4actor.Types.NextOffset
import okio.ByteString

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

case class RawHeaderImpl(key: String, value: String) extends RawHeader

class QRecordImpl(val topic: TopicName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord

class QMessagesImpl(toUpdate: ToUpdate, getRawQSender: ()⇒RawQSender) extends QMessages {
  //import qAdapterRegistry._
  // .map(o⇒ nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: Context): Context = {
    val updates: List[Update] = WriteModelKey.of(local).toList
    if(updates.isEmpty) return local
    //println(s"sending: ${updates.size} ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
    val (bytes, headers) = toUpdate.toBytes(updates)
    val rec = new QRecordImpl(InboxTopicName(), bytes, headers)
    val debugStr = WriteModelDebugKey.of(local).map(_.toString).mkString("\n---\n")
    val debugRec = new QRecordImpl(LogTopicName(),debugStr.getBytes(UTF_8), Nil)
    val List(offset,_)= getRawQSender().send(List(rec,debugRec))
    Function.chain(Seq(
      WriteModelKey.set(Queue.empty),
      WriteModelDebugKey.set(Queue.empty),
      ReadAfterWriteOffsetKey.set(offset)
    ))(local)
  }
}

class ToUpdateImpl(
  qAdapterRegistry: QAdapterRegistry,
  compressorRegistry: CompressorRegistry,
  compressor: Compressor,
  compressionMinSize: Long = 50000000L
)(
  updatesAdapter: ProtoAdapter[Updates] with HasId =
  qAdapterRegistry.byName(classOf[QProtocol.Updates].getName)
    .asInstanceOf[ProtoAdapter[Updates] with HasId],
  refAdapter: ProtoAdapter[TxRef] with HasId =
  qAdapterRegistry.byName(classOf[TxRef].getName)
    .asInstanceOf[ProtoAdapter[TxRef] with HasId]
) extends ToUpdate {
  def toUpdate[M <: Product](message: LEvent[M]): Update = {
    val valueAdapter = qAdapterRegistry.byName(message.className)
    val byteString = ToByteString(message.value.map(valueAdapter.encode).getOrElse(Array.empty))
    Update(message.srcId, valueAdapter.id, byteString)
  }

  private val compressionKey = "c"

  private def findCompressor: List[RawHeader] ⇒ Option[Compressor] = list ⇒
    list.collectFirst { case header if header.key == compressionKey ⇒ header.value } match {
      case Some(name) ⇒ Option(compressorRegistry.byName(name))
      case None ⇒ None
    }

  private def makeHeaderFromName: Compressor ⇒ List[RawHeader] = jc ⇒
    RawHeaderImpl(compressionKey, jc.name) :: Nil


  def toBytes(updates: List[Update]): (Array[Byte], List[RawHeader]) = {
    val updatesBytes = updatesAdapter.encode(Updates("", updates))
      if (updatesBytes.size < compressionMinSize)
        (updatesBytes, Nil)
      else
        (compressor.compressRaw(updatesBytes), makeHeaderFromName(compressor))
  }

  def toUpdates(events: List[RawEvent]): List[Update] =
    for {
      event ← events
      compressorOpt = findCompressor(event.headers)
      update ← updatesAdapter.decode(
        compressorOpt.map(_.deCompress(event.data)).getOrElse(event.data)
      ).updates
    } yield
      if (update.valueTypeId != refAdapter.id) update
      else {
        val ref: TxRef = refAdapter.decode(update.value)
        if (ref.txId.nonEmpty) update
        else update.copy(value = ToByteString(refAdapter.encode(ref.copy(txId = event.srcId))))
      }


  def toKey(up: Update): Update = up.copy(value=ByteString.EMPTY)
  def by(up: Update): (Long, String) = (up.valueTypeId,up.srcId)
}

object QAdapterRegistryFactory {
  def apply(protocols: List[Protocol]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters).asInstanceOf[List[ProtoAdapter[Product] with HasId]]
    val byName = CheckedMap(adapters.map(a ⇒ a.className → a))
    val byId = CheckedMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
    new QAdapterRegistry(byName, byId)
  }
}

class LocalQAdapterRegistryInit(qAdapterRegistry: QAdapterRegistry) extends ToInject {
  def toInject: List[Injectable] = QAdapterRegistryKey.set(qAdapterRegistry)
}

/*object NoRawQSender extends RawQSender {
  def send(recs: List[QRecord]): List[NextOffset] = Nil
}*/