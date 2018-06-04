package ee.cone.c4actor.hashsearch.base

import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

case class RootCondition[Model <: Product](srcId: SrcId, innerUnion: InnerUnionList[Model], requestId: SrcId)

case class InnerConditionEstimate[Model <: Product](srcId: SrcId, count: Int, heapIds: List[SrcId]) extends LazyHashCodeProduct

case class ResponseModelList[Model <: Product](srcId: SrcId, modelList: List[Model]) extends LazyHashCodeProduct

case class OuterIntersectList[Model <: Product](srcId: SrcId, innerLeaf: InnerLeaf[Model]) extends LazyHashCodeProduct

case class InnerIntersectList[Model <: Product](srcId: SrcId, innerLeafs: List[InnerLeaf[Model]]) extends InnerCondition[Model] {
  def check(model: Model): Boolean = innerLeafs.forall(_.check(model))
}

case class OuterUnionList[Model <: Product](srcId: SrcId, innerIntersect: InnerIntersectList[Model]) extends LazyHashCodeProduct

case class InnerUnionList[Model <: Product](srcId: SrcId, innerIntersects: List[InnerIntersectList[Model]]) extends InnerCondition[Model] {
  def check(model: Model): Boolean = innerIntersects.foldLeft(false)((z, inter) ⇒ z || inter.check(model))
}

case class InnerLeaf[Model <: Product](srcId: SrcId, condition: Condition[Model]) extends InnerCondition[Model] {
  def check(model: Model): Boolean = condition.check(model)
}

sealed trait InnerCondition[Model <: Product] extends LazyHashCodeProduct {
  def check(model: Model): Boolean
}

trait HashSearchAssembleSharedKeys {
  // Shared keys
  type SharedHeapId = SrcId
  type SharedResponseId = SrcId
}

trait HashSearchModelsApp {
  def hashSearchModels: List[Class[_ <: Product]] = Nil
}

trait HashSearchAssembleApp extends AssemblesApp with HashSearchModelsApp with SerializationUtilsApp with PreHashingApp{
  def qAdapterRegistry: QAdapterRegistry

  def debugModeHashSearchAssemble: Boolean = false

  override def assembles: List[Assemble] = hashSearchModels.distinct.map(new HashSearchAssemble(_, qAdapterRegistry, serializer, preHashing, debugModeHashSearchAssemble)) ::: super.assembles
}

object HashSearchAssembleUtils {
  def parseCondition[Model <: Product]: Condition[Model] ⇒ List[Condition[Model]] = {
    case IntersectCondition(left, right) ⇒ left :: right :: Nil
    case UnionCondition(left, right) ⇒ left :: right :: Nil
    case _ ⇒ Nil
  }

  def bestEstimateInter[Model <: Product]: Values[InnerConditionEstimate[Model]] ⇒ Option[InnerConditionEstimate[Model]] = estimates ⇒ {
    val distinctEstimates = estimates.distinct
    Utility.minByOpt(distinctEstimates)(_.count)
  }

  def bestEstimateUnion[Model <: Product]: Values[InnerConditionEstimate[Model]] ⇒ Option[InnerConditionEstimate[Model]] = estimates ⇒ {
    val distinctEstimates = estimates.distinct
    Utility.reduceOpt(distinctEstimates)(
      (a, b) ⇒ a.copy(count = a.count + b.count, heapIds = (a.heapIds ::: b.heapIds).distinct)
    )
  }

  private case class ParseLeaf[Model <: Product](cond: Condition[Model])

  private case class ParseIntersect[Model <: Product](list: List[ParseLeaf[Model]])

  private case class ParseUnion[Model <: Product](list: List[ParseIntersect[Model]])

  def conditionToUnionList[Model <: Product]: Class[Model] ⇒ SerializationUtils ⇒ Condition[Model] ⇒ InnerUnionList[Model] = model ⇒ ser ⇒ cond ⇒ {
    val parsed: ParseUnion[Model] = flattenCondition(cond)
    parsedToPublic(model)(ser)(parsed)
  }

  private def parsedToPublic[Model <: Product]: Class[Model] ⇒ SerializationUtils ⇒ ParseUnion[Model] ⇒ InnerUnionList[Model] = model ⇒ ser ⇒ parsed ⇒ {
    val inters = for {
      inter ← parsed.list
    } yield interParsedToPublic(model)(ser)(inter)
    val pk = ser.srcIdFromSrcIds("InnerUnionList" :: inters.map(_.srcId))
    InnerUnionList(pk, inters)
  }

  private def interParsedToPublic[Model <: Product]: Class[Model] ⇒ SerializationUtils ⇒ ParseIntersect[Model] ⇒ InnerIntersectList[Model] = model ⇒ ser ⇒ parsed ⇒ {
    val leafs = for {
      leaf ← parsed.list
    } yield leafParsedToPublic(model)(ser)(leaf)
    val pk = ser.srcIdFromSrcIds("InnerIntersectList" :: leafs.map(_.srcId))
    InnerIntersectList(pk, leafs)
  }

  private def leafParsedToPublic[Model <: Product]: Class[Model] ⇒ SerializationUtils ⇒ ParseLeaf[Model] ⇒ InnerLeaf[Model] = model ⇒ ser ⇒ {
    case ParseLeaf(a) ⇒
      val pk = ser.getConditionPK(model, a)
      InnerLeaf(pk, a)
  }

  private def flattenCondition[Model <: Product]: Condition[Model] ⇒ ParseUnion[Model] = {
    case a: ProdCondition[_, Model] ⇒
      ParseUnion(List(ParseIntersect(List(ParseLeaf(a)))))
    case a: AnyCondition[Model] ⇒
      ParseUnion(List(ParseIntersect(List(ParseLeaf(a)))))
    case a: IntersectCondition[Model] ⇒
      val left: ParseUnion[Model] = flattenCondition(a.left)
      val right: ParseUnion[Model] = flattenCondition(a.right)
      interParseUnion(left, right)
    case a: UnionCondition[Model] ⇒
      val left: ParseUnion[Model] = flattenCondition(a.left)
      val right: ParseUnion[Model] = flattenCondition(a.right)
      uniteParseUnion(left, right)
  }

  private def uniteParseUnion[Model <: Product](a: ParseUnion[Model], b: ParseUnion[Model]): ParseUnion[Model] = {
    ParseUnion((a.list ::: b.list).distinct)
  }

  private def interParseUnion[Model <: Product](a: ParseUnion[Model], b: ParseUnion[Model]): ParseUnion[Model] = {
    val inters = for {
      interL ← a.list
      interR ← b.list
    } yield interInter(interL, interR)
    ParseUnion(inters.distinct)
  }

  private def interInter[Model <: Product](a: ParseIntersect[Model], b: ParseIntersect[Model]): ParseIntersect[Model] = {
    ParseIntersect((a.list ::: b.list).distinct)
  }
}

import ee.cone.c4actor.hashsearch.base.HashSearchAssembleUtils._

@assemble class HashSearchAssemble[Model <: Product](
  modelCl: Class[Model],
  val qAdapterRegistry: QAdapterRegistry,
  condSer: SerializationUtils,
  preHashing: PreHashing,
  debugMode: Boolean = false
) extends Assemble with HashSearchAssembleSharedKeys {
  type InnerUnionId = SrcId
  type InnerIntersectId = SrcId
  type InnerLeafId = SrcId

  // Handle root
  def RequestToRootCondition(
    requestId: SrcId,
    requests: Values[Request[Model]]
  ): Values[(SrcId, RootCondition[Model])] =
    for {
      request ← requests
    } yield {
      val condition: Condition[Model] = request.condition
      val condUnion: InnerUnionList[Model] = conditionToUnionList(modelCl)(condSer)(condition)
      WithPK(RootCondition(condSer.srcIdFromSrcIds(request.requestId, condUnion.srcId), condUnion, request.requestId))
    }

  def RootCondToInnerConditionId(
    rootCondId: SrcId,
    rootConditions: Values[RootCondition[Model]]
  ): Values[(InnerUnionId, RootCondition[Model])] =
    for {
      rootCond ← rootConditions
    } yield rootCond.innerUnion.srcId → rootCond

  def RootCondIntoInnerUnionList(
    rootCondId: SrcId,
    @by[InnerUnionId] rootConditions: Values[RootCondition[Model]]
  ): Values[(SrcId, InnerUnionList[Model])] = {
    val inner = Single(rootConditions.map(_.innerUnion).distinct)
    WithPK(inner) :: Nil
  }

  // Handle Union
  def InnerUnionListToInnerInterIds(
    innerUnionId: SrcId,
    innerUnions: Values[InnerUnionList[Model]]
  ): Values[(InnerIntersectId, OuterUnionList[Model])] =
    for {
      union ← innerUnions
      inter ← union.innerIntersects
    } yield inter.srcId → OuterUnionList(union.srcId, inter)

  def InnerUnionIntoInnerInters(
    innerInterId: SrcId,
    @by[InnerIntersectId] outerUnions: Values[OuterUnionList[Model]]
  ): Values[(SrcId, InnerIntersectList[Model])] = {
    val inner = outerUnions.head.innerIntersect
    WithPK(inner) :: Nil
  }

  // Handle Inter
  def InnerInterListToInnerLeafIds(
    innerInterId: SrcId,
    innerInters: Values[InnerIntersectList[Model]]
  ): Values[(InnerLeafId, OuterIntersectList[Model])] =
    for {
      inter ← innerInters
      leaf ← inter.innerLeafs
    } yield leaf.srcId → OuterIntersectList(inter.srcId, leaf)

  def InnerInterListIntoLeafs(
    innerInterId: SrcId,
    @by[InnerLeafId] outerInters: Values[OuterIntersectList[Model]]
  ): Values[(SrcId, InnerLeaf[Model])] = {
    TimeColored("g", ("InnerInterListIntoLeafs", outerInters.size), doNotPrint = !debugMode) {
      val inner = outerInters.head.innerLeaf
      WithPK(inner) :: Nil
    }
  }

  // Handle count for Leaf
  def LeafInnerCondEstToInnerInter(
    leafCondEstimateId: SrcId,
    leafCondEstimates: Values[InnerConditionEstimate[Model]],
    innerLeafs: Values[InnerLeaf[Model]],
    @by[InnerLeafId] outerIntersects: Values[OuterIntersectList[Model]]
  ): Values[(InnerIntersectId, InnerConditionEstimate[Model])] =
    TimeColored("b", ("LeafInnerCondEstToInnerInter", outerIntersects.size, innerLeafs.map(_.condition)), doNotPrint = !debugMode) {
      for {
        leafEstimate ← SingleInSeq(leafCondEstimates)
        _ ← SingleInSeq(innerLeafs)
        inter ← outerIntersects
      } yield inter.srcId → leafEstimate
    }

  // Handle count for Inter
  def LeafInnerCondEstIntoInterInnerCondEstToInnerUnion(
    innerInterId: SrcId,
    innerInters: Values[InnerIntersectList[Model]],
    @by[InnerIntersectId] leafCondEstimates: Values[InnerConditionEstimate[Model]],
    @by[InnerIntersectId] outerUnions: Values[OuterUnionList[Model]]
  ): Values[(InnerUnionId, InnerConditionEstimate[Model])] =
    for {
      inter ← innerInters
      estimate ← bestEstimateInter(leafCondEstimates).toList
      union ← outerUnions
    } yield union.srcId → estimate

  // Handle count for Union
  def InnerInterEstimateIntoInnerUnionCondEstimateToHeap(
    innerUnionId: SrcId,
    innerUnions: Values[InnerUnionList[Model]],
    @by[InnerUnionId] interCondEstimates: Values[InnerConditionEstimate[Model]]
  ): Values[(SharedHeapId, InnerUnionList[Model])] =
    for {
      union ← innerUnions
      estimate ← bestEstimateUnion(interCondEstimates).toList
      heapId ← estimate.heapIds
    } yield heapId → union

  type RequestId = SrcId

  def ResponsesToRequest(
    innerUnionId: SrcId,
    innerUnions: Values[InnerUnionList[Model]],
    @by[SharedResponseId] responses: Values[ResponseModelList[Model]],
    @by[InnerUnionId] rootConditions: Values[RootCondition[Model]]
  ): Values[(RequestId, ResponseModelList[Model])] = {
    TimeColored("g", ("ResponsesToRequest", responses.size, responses.map(_.modelList.size).sum), doNotPrint = !debugMode) {
      val distinctList = MergeBySrcId(responses.map(_.modelList))
      val result = for {
        root ← rootConditions.par
      } yield {
        root.requestId → ResponseModelList(root.requestId + innerUnionId, distinctList)
      }
      val transResult = result.to[Values]
      transResult
    }
  }

  def ResponseByRequest(
    requestId: SrcId,
    requests: Values[Request[Model]],
    @by[RequestId] responses: Values[ResponseModelList[Model]]
  ): Values[(SrcId, Response[Model])] =
    TimeColored("y", ("ResponseByRequest", responses.size, responses.map(_.modelList.size).size), doNotPrint = !debugMode) {
      for {
        request ← requests
      } yield {
        val pk = ToPrimaryKey(request)
        WithPK(Response(pk, request, preHashing.wrap(Single.option(responses).map(_.modelList).toList.flatten)))
      }
    }

}