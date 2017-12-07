
package ee.cone.c4actor

import PCProtocol.{RawChildNode, RawParentNode}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types._
import ee.cone.c4proto._
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assembled, _}


@protocol object PCProtocol {
  @Id(0x0003) case class RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case class ParentNodeWithChildren(srcId: String, caption: String, children: Values[RawChildNode])
@assemble class TestAssemble {
  type ParentSrcId = SrcId
  def joinChildNodeByParent(
    key: SrcId,
    rawChildNode: Values[RawChildNode]
  ): Values[(ParentSrcId,RawChildNode)] =
    rawChildNode.map(child ⇒ child.parentSrcId → child)
  def joinParentNodeWithChildren(
    key: SrcId,
    @by[ParentSrcId] childNodes: Values[RawChildNode],
    rawParentNodes: Values[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] = for {
    parent ← rawParentNodes
  } yield WithPK(ParentNodeWithChildren(parent.srcId, parent.caption, childNodes))
  /* todo:
  IO[SrcId,ParentNodeWithChildren](
    for(parent <- IO[SrcId,RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, IO[ParentSrcId,RawChildNode](
        key => for(child <- IO[SrcId,RawChildNode]) yield child.parentSrcId → child
      ))
  )
  ////////

  Pairs[ParentSrcId,RawChildNode] =
    for(child <- Values[SrcId,RawChildNode]) yield child.parentSrcId → child

  Values[SrcId,ParentNodeWithChildren] =
    for(parent <- Values[SrcId,RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, Values[ParentSrcId,RawChildNode])
  */

}

object AssemblerTest extends App with LazyLogging {}

@c4component @listed case class AssemblerTestStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory,
  rawParentNodes: ByPK[RawParentNode] @c4key,
  rawChildNodes: ByPK[RawChildNode] @c4key,
  parentNodesWithChildren: ByPK[ParentNodeWithChildren] @c4key
) extends Executable with LazyLogging {
  def run(): Unit = {
    val recs = update(RawParentNode("1", "P-1")) ++
      List("2", "3")
        .flatMap(srcId ⇒ update(RawChildNode(srcId, "1", s"C-$srcId")))
    val updates = recs.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    //println(app.qMessages.toTree(rawRecs))
    val context = contextFactory.create()
    val nGlobal = ReadModelAddKey.of(context)(updates)(context)

    logger.debug(s"$nGlobal")
    List(
      rawParentNodes -> Map(
        "1" -> RawParentNode("1", "P-1")
      ),
      rawChildNodes -> Map(
        "2" -> RawChildNode("2", "1", "C-2"),
        "3" -> RawChildNode("3", "1", "C-3")
      ),
      parentNodesWithChildren -> Map(
        "1" -> ParentNodeWithChildren(
          "1",
          "P-1",
          List(RawChildNode("2", "1", "C-2"), RawChildNode("3", "1", "C-3"))
        )
      )
    ).foreach {
      case (k, v) ⇒ assert(k.of(nGlobal).toMap == v)
    }
    execution.complete()
  }
}

/*
val shouldDiff = Map(
  By.srcId(classOf[PCProtocol.RawParentNode]) -> Map(
    "1" -> List(RawParentNode("1","P-1"))
  ),
  By.srcId(classOf[PCProtocol.RawChildNode]) -> Map(
    "2" -> List(RawChildNode("2","1","C-2")),
    "3" -> List(RawChildNode("3","1","C-3"))
  )
)
assert(diff==shouldDiff)*/