package ee.cone.c4actor.hashsearch.index

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.HashSearch.Request
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.base.{HashSearchAssembleSharedKeys, InnerConditionEstimate, InnerLeaf}
import ee.cone.c4actor.hashsearch.index.HashSearchMockAssembleTest.K2TreeAll
import ee.cone.c4actor.hashsearch.rangers.K2TreeUtils._
import ee.cone.c4actor.hashsearch.rangers.RangeTreeProtocol.{K2TreeParams, TreeNode, TreeNodeOuter, TreeRange}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto.ToByteString

object HashSearchMockUtils {
  def heapId(cl: Class[_], node: TreeNode, qAdapterRegistry: QAdapterRegistry, uuidUtil: UUIDUtil): SrcId = {
    val clName = cl.getName
    val valueAdapter = qAdapterRegistry.byName(node.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(node))
    uuidUtil.srcIdFromSerialized(valueAdapter.id, bytes)
  }

  def heapIds(cl: Class[_], nodes: List[TreeNode], qAdapterRegistry: QAdapterRegistry, uuidUtil: UUIDUtil): List[SrcId] =
    for {
      node ← nodes
    } yield heapId(cl, node, qAdapterRegistry, uuidUtil)

  def single[Something]: Values[Something] ⇒ Values[Something] =
    l ⇒ Single.option(l.distinct).toList

  def count[Model <: Product](heapSrcId: SrcId, respLines: Values[Model]): K2Count[Model] =
    K2Count(heapSrcId, Log2Pow2(respLines.length))

  def isMy[Model](cond: Condition[Model], name: NameMetaAttr): Boolean =
    cond match {
      case a: ProdCondition[_, Model] ⇒ a.metaList.collectFirst{case a:NameMetaAttr ⇒ a}.get == name
      case _ ⇒ false
    }
}

case class K2Need[Model <: Product](requestId: SrcId, modelCl: Class[Model])

case class K2Count[Model <: Product](heapSrcId: SrcId, count: Int)

object HashSearchMockAssembleTest {
  type K2TreeAll[Test] = All
}

import HashSearchMockUtils._

@assemble class HashSearchMockAssemble[Model <: Product](
  modelCl: Class[Model],
  getDate: Model ⇒ (Option[Long], Option[Long]),
  conditionToRegion: Condition[Model] ⇒ TreeRange,
  filterName: NameMetaAttr,
  qAdapterRegistry: QAdapterRegistry,
  uuidUtil: UUIDUtil
) extends Assemble with HashSearchAssembleSharedKeys{
  type K2HeapId = SrcId
  type K2ToCountId = SrcId


  def GetTreeToAll(
    treeId: SrcId,
    params: Values[K2TreeParams],
    trees: Values[TreeNodeOuter]
  ): Values[(K2TreeAll[Model], TreeNodeOuter)] =
    for {
      param ← params
      if param.modelName == modelCl.getName
      tree ← trees
    } yield All → tree


  // Index builder
  def RespLineByHeap(
    respLineId: SrcId,
    respLines: Values[Model],
    @by[K2TreeAll[Model]] trees: Values[TreeNodeOuter]
  ): Values[(K2HeapId, Model)] =
    for {
      respLine ← respLines
      tree ← trees
      tagId ← heapId(modelCl, findRegion(tree.root.get, getDate(respLine)), qAdapterRegistry, uuidUtil) :: Nil
    } yield tagId → respLine

  // end index builder

  // LeafRequest receive
  def ReqByHeap(
    leafCondId: SrcId,
    leafConditions: Values[InnerLeaf[Model]],
    @by[K2TreeAll[Model]] trees: Values[TreeNodeOuter]
  ): Values[(K2HeapId, K2Need[Model])] =
    for {
      leaf ← single(leafConditions)
      if isMy(leaf.condition, filterName)
      tree ← trees
      heapId ← heapIds(modelCl, getRegions(tree.root.get, conditionToRegion(leaf.condition)), qAdapterRegistry, uuidUtil)
    } yield heapId → K2Need[Model](ToPrimaryKey(leaf), modelCl)


  def RespHeapCountByReq(
    heapId: SrcId,
    @by[K2HeapId] respLines: Values[Model],
    @by[K2HeapId] needs: Values[K2Need[Model]]
  ): Values[(K2ToCountId, K2Count[Model])] =
    for {
      need ← needs
    } yield need.requestId → count(heapId, respLines)

  // Count response
  def CountByReq(
    needId: K2HeapId,
    requests: Values[InnerLeaf[Model]],
    @by[K2ToCountId] counts: Values[K2Count[Model]]
  ): Values[(SrcId, InnerConditionEstimate[Model])] =
    for {
      request ← requests
      if isMy(request.condition, filterName)
    } yield request.srcId → InnerConditionEstimate[Model](request.srcId, counts.map(_.count).sum, counts.toList.map(_.heapSrcId))


  // Lines Response
  def RespByReq(
    heapId: SrcId,
    @by[K2HeapId] responses: Values[Model],
    @by[SharedHeapId] requests: Values[Request[Model]]
  ): Values[(SharedResponseId, Model)] = {
    println(requests.toString().take(50), responses.size)
    for {
      request ← requests
      line ← responses
      if request.condition.check(line)
    } yield ToPrimaryKey(request) → line
  }

}