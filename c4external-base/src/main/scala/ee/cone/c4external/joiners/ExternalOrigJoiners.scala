package ee.cone.c4external.joiners

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, assemble, by}
import ee.cone.c4external.ExternalProtocol.{CacheResponses, ExternalUpdates}
import ee.cone.c4proto.HasId
import okio.ByteString

case class ExternalUpdate[Model <: Product](srcId: SrcId, update: Update, offset: NextOffset) {
  def origValue: ByteString = update.value
}

case class CacheResponse[Model <: Product](srcId: SrcId, update: Update, offset: NextOffset) {
  def origValue: ByteString = update.value
}

trait ExternalUpdateUtil[Model <: Product] {
  type TxRefId[ModelType] = SrcId
  def adapter: ProtoAdapter[Model] with HasId
  def decode: ByteString ⇒ Values[(SrcId, Model)] = bs ⇒
    if (bs.size() == 0)
      Nil
    else
      WithPK(adapter.decode(bs)) :: Nil

}


import ee.cone.c4external.ExternalOrigKey._

@assemble class ExternalOrigJoinerBase[Model <: Product](
  modelCl: Class[Model],
  modelId: Long,
  qAdapterRegistry: QAdapterRegistry
)(
  val adapter: ProtoAdapter[Model] with HasId = qAdapterRegistry.byId(modelId).asInstanceOf[ProtoAdapter[Model] with HasId]
) extends ExternalUpdateUtil[Model] {
  type MergeId = SrcId
  type CombineId = SrcId

  def ToMergeExtUpdate(
    origId: SrcId,
    extU: Each[ExternalUpdates]
  ): Values[(MergeId, ExternalUpdate[Model])] =
    if (extU.valueTypeId == modelId)
      extU.updates
        .filter(_.valueTypeId == modelId)
        .map(u ⇒ u.srcId → ExternalUpdate[Model](extU.txId + u.srcId, u, extU.txId))
    else
      Nil

  def ToSingleExtUpdate(
    origId: SrcId,
    @by[MergeId] extUs: Values[ExternalUpdate[Model]]
  ): Values[(CombineId, ExternalUpdate[Model])] =
    if (extUs.nonEmpty) {
      val u = extUs.maxBy(_.offset)
      List(origId → u)
    } else Nil

  def ToMergeCacheResponse(
    origId: SrcId,
    cResp: Each[CacheResponses]
  ): Values[(MergeId, CacheResponse[Model])] =
    cResp.updates
      .filter(_.valueTypeId == modelId)
      .map(u ⇒ u.srcId → CacheResponse[Model](cResp.extOffset + u.srcId, u, cResp.extOffset))

  def ToSingleCacheResponse(
    origId: SrcId,
    @by[MergeId] cResps: Values[CacheResponse[Model]]
  ): Values[(CombineId, CacheResponse[Model])] =
    if (cResps.nonEmpty) {
      val u = cResps.maxBy(_.offset)
      List(u.update.srcId → u)
    } else Nil

  def CreateExternal(
    origId: SrcId,
    @by[ExtSrcId] model: Values[Model],
    @by[CombineId] externals: Values[ExternalUpdate[Model]],
    @by[CombineId] caches: Values[CacheResponse[Model]]
  ): Values[(SrcId, Model)] =
    (Single.option(externals), Single.option(caches)) match {
      case (Some(e), None) ⇒ decode(e.origValue)
      case (None, Some(c)) ⇒ decode(c.origValue)
      case (Some(e), Some(c)) ⇒
        if (e.offset > c.offset)
          decode(e.origValue)
        else if (e.offset < c.offset)
          decode(c.origValue)
        else {
          assert(e.origValue == c.origValue, s"Same offset, different values: $e, $c")
          decode(e.origValue)
        }
      case _ ⇒ Nil
    }

}
