package ee.cone.c4actor.dep.request

import java.time.Instant

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.dep.request.CurrentTimeRequestProtocol.{CurrentTimeMetaAttr, CurrentTimeRequest}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4proto._

trait CurrentTimeHandlerApp extends AssemblesApp with ProtocolsApp with CurrentTimeConfigApp with DepResponseFactoryApp {


  override def currentTimeConfig: List[CurrentTimeConfig] = CurrentTimeConfig(CurrentTimeRequestAssembleTimeId.id, 10L) :: super.currentTimeConfig

  override def assembles: List[Assemble] = new CurrentTimeRequestAssemble(depResponseFactory) :: super.assembles

  override def protocols: List[Protocol] = QProtocol :: CurrentTimeRequestProtocol :: super.protocols
}

case class CurrentTimeTransform(srcId: SrcId, refreshRateSeconds: Long) extends TxTransform {
  private val refreshMilli = refreshRateSeconds * 1000L + 5L

  def transform(l: Context): Context = {
    val newLocal = InsertOrigMeta(CurrentTimeMetaAttr(srcId, refreshRateSeconds) :: Nil)(l)
    val now = Instant.now
    val nowMilli = now.toEpochMilli
    val prev = ByPK(classOf[CurrentTimeNode]).of(newLocal).get(srcId)
    if (prev.isEmpty || prev.get.currentTimeMilli > nowMilli + refreshMilli) {
      val nowSeconds = now.getEpochSecond
      TxAdd(LEvent.update(CurrentTimeNode(srcId, nowSeconds, nowMilli)))(newLocal)
    } else {
      newLocal
    }
  }
}

@protocol(OperativeCat) object CurrentTimeProtocol extends Protocol {

  @Id(0x0123) case class CurrentTimeNode(
    @Id(0x0124) srcId: String,
    @Id(0x0125) currentTimeSeconds: Long,
    @Id(0x0126) currentTimeMilli: Long
  )

}

case class CurrentTimeConfig(srcId: SrcId, periodSeconds: Long)

trait CurrentTimeConfigApp {
  def currentTimeConfig: List[CurrentTimeConfig] = Nil
}

trait CurrentTimeAssembleMix extends CurrentTimeConfigApp with AssemblesApp with ProtocolsApp {

  override def protocols: List[Protocol] = CurrentTimeProtocol :: super.protocols

  override def assembles: List[Assemble] = new CurrentTimeAssemble(currentTimeConfig.distinct) :: super.assembles
}

object CurrentTimeRequestAssembleTimeId {
  val id: String = "CurrentTimeRequestAssemble"
}


@assemble class CurrentTimeAssemble(configList: List[CurrentTimeConfig]) extends Assemble {
  type PongSrcId = SrcId

  def FromFirstBornCreateNowTime(
    firstBornId: SrcId,
    firstborn: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] = for {
    config ← configList
  } yield WithPK(CurrentTimeTransform(config.srcId, config.periodSeconds))

  def GetCurrentTimeToAll(
    nowTimeId: SrcId,
    nowTimeNode: Each[CurrentTimeNode]
  ): Values[(All, CurrentTimeNode)] =
    List(All → nowTimeNode)
}

@assemble class CurrentTimeRequestAssemble(util: DepResponseFactory) extends Assemble {
  type CurrentTimeRequestAssembleTimeAll = All
  type FilterTimeRequests = SrcId

  def FilterTimeForCurrentTimeRequestAssemble(
    timeId: SrcId,
    time: Each[CurrentTimeNode]
  ): Values[(CurrentTimeRequestAssembleTimeAll, CurrentTimeNode)] =
    if (time.srcId == CurrentTimeRequestAssembleTimeId.id)
      List(All → time)
    else
      Nil

  def FilterTimeRequests(
    requestId: SrcId,
    rq: Each[DepInnerRequest]
  ): Values[(FilterTimeRequests, DepInnerRequest)] =
    rq.request match {
      case _: CurrentTimeRequest ⇒ WithPK(rq) :: Nil
      case _ ⇒ Nil
    }

  def TimeToDepResponse(
    alienId: SrcId,
    @by[CurrentTimeRequestAssembleTimeAll] pong: Each[CurrentTimeNode],
    @by[FilterTimeRequests] rq: Each[DepInnerRequest]
  ): Values[(SrcId, DepResponse)] = {
    val timeRq = rq.request.asInstanceOf[CurrentTimeRequest]
    val newTime = pong.currentTimeSeconds / timeRq.everyPeriod * timeRq.everyPeriod
    WithPK(util.wrap(rq, Option(newTime))) :: Nil
  }
}

@protocol object CurrentTimeRequestProtocol extends Protocol {

  @Cat(DepRequestCat)
  @Id(0x0f83) case class CurrentTimeRequest(
    @Id(0x0f86) everyPeriod: Long
  )

  @Cat(TxMetaCat)
  @Id(0x0f87) case class CurrentTimeMetaAttr(
    @Id(0x0f88) srcId: String,
    @Id(0x0f89) everyPeriod: Long
  )

}
