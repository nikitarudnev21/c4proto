package ee.cone.c4actor.time

import java.security.SecureRandom

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.time.ProtoCurrentTimeConfig.S_CurrentTimeNodeSetting
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{AbstractAll, All, byEq, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{Id, protocol}

trait T_Time extends Product {
  def srcId: SrcId
  def millis: Long
}

trait GeneralCurrentTimeAppBase

trait WithCurrentTime {
  def currentTime: CurrentTime
}

trait GeneralCurrTimeConfig extends WithCurrentTime {
  def cl: Class[_ <: T_Time]
  def process(refreshRate: Option[Long], offset: Long): Context => Seq[LEvent[Product]]
}

trait CurrTimeConfig[Model <: T_Time] extends GeneralCurrTimeConfig with LazyLogging {
  def cl: Class[Model]
  def set: Long => Model => Model
  def default: Model
  def timeGetter: GetByPK[Model]

  def process(refreshRate: Option[Long], offset: Long): Context => Seq[LEvent[Product]] = {
    val refreshRateMillis = refreshRate.getOrElse(currentTime.refreshRateSeconds) * 1000L
    local => {
      val now = System.currentTimeMillis()
      val model: Option[Model] = timeGetter.ofA(local).get(currentTime.srcId)
      model match {
        case Some(time) if time.millis + offset + refreshRateMillis < now =>
          logger.debug(s"Updating ${currentTime.srcId} with ${offset}")
          LEvent.update(set(now)(time))
        case None =>
          LEvent.update(set(now)(default))
        case _ => Nil
      }
    }
  }
}

@c4("GeneralCurrentTimeApp") final class TimeGettersImpl(timeGetters: List[TimeGetter]) extends TimeGetters {
  def all: List[TimeGetter] = timeGetters
  lazy val gettersMap: Map[SrcId, TimeGetter] = timeGetters.map(getter => getter.currentTime.srcId -> getter).toMap
  def apply(currentTime: CurrentTime): TimeGetter =
    gettersMap(currentTime.srcId)
}

@protocol("GeneralCurrentTimeApp") object ProtoCurrentTimeConfig {

  @Id(0x0127) case class S_CurrentTimeNodeSetting(
    @Id(0x0128) timeNodeId: String,
    @Id(0x0129) refreshSeconds: Long
  )

}

@c4assemble("GeneralCurrentTimeApp") class CurrentTimeGeneralAssembleBase(
  generalCurrentTimeTransformFactory: GeneralCurrentTimeTransformFactory,
) {
  type CurrentTimeGeneralAll = AbstractAll

  def timeToAll(
    timeId: SrcId,
    setting: Each[S_CurrentTimeNodeSetting]
  ): Values[(CurrentTimeGeneralAll, S_CurrentTimeNodeSetting)] =
    WithAll(setting) :: Nil

  def CreateGeneralTimeConfig(
    firstBornId: SrcId,
    firstborn: Each[S_Firstborn],
    @byEq[CurrentTimeGeneralAll](All) timeSetting: Values[S_CurrentTimeNodeSetting],
    //@time(TestDepTime) time: Each[Time] // byEq[SrcId](TestTime.srcId) time: Each[Time]
  ): Values[(SrcId, TxTransform)] =
    WithPK(generalCurrentTimeTransformFactory.create(s"${firstborn.srcId}-general-time", timeSetting.toList)) :: Nil
}

@c4multi("GeneralCurrentTimeApp") final case class GeneralCurrentTimeTransform(
  srcId: SrcId, configs: List[S_CurrentTimeNodeSetting]
)(
  generalCurrTimeConfigs: List[GeneralCurrTimeConfig],
  txAdd: LTxAdd,
) extends TxTransform {
  lazy val currentTimes: List[GeneralCurrTimeConfig] =
    CheckedMap(generalCurrTimeConfigs.map(c => c.currentTime.srcId -> c)).values.toList
  private val random: SecureRandom = new SecureRandom()

  lazy val configsMap: Map[String, Long] =
    configs.map(conf => conf.timeNodeId -> conf.refreshSeconds).toMap
  lazy val actions: List[Context => Seq[LEvent[Product]]] =
    currentTimes.map(currentTime =>
      currentTime.process(configsMap.get(currentTime.currentTime.srcId), random.nextInt(500))
    )

  def transform(local: Context): Context = {
    actions.flatMap(_.apply(local)) match { // TODO may be throttle time updates and do them one by one
      case updates if updates.isEmpty => local
      case updates => txAdd.add(updates)(local)
    }
  }

}
