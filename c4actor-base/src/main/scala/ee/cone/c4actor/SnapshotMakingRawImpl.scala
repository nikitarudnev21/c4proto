package ee.cone.c4actor

import java.time.temporal.TemporalAmount
import java.time.{Duration, Instant}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Update, Updates}
import ee.cone.c4actor.Types.NextOffset
import okio.ByteString

import scala.annotation.tailrec
import scala.collection.immutable.Seq

class SnapshotMakingRawWorldFactory(config: SnapshotConfig) extends RawWorldFactory {
  def create(updates: Updates): RawWorld =
    new SnapshotMakingRawWorld(config.ignore, Map.empty, updates.srcId).reduce(List(updates))
}

class SnapshotMakingRawWorld(
  ignore: Set[Long],
  state: Map[Update,Update],
  val offset: NextOffset
) extends RawWorld with LazyLogging {
  def reduce(events: List[Updates]): RawWorld = if(events.isEmpty) this else {
    val updates = events.flatMap(_.updates)
    val newState = (state /: updates){(state,up)⇒
      if(ignore(up.valueTypeId)) state
      else if(up.value.size > 0) state + (up.copy(value=ByteString.EMPTY)→up)
      else state - up
    }
    new SnapshotMakingRawWorld(ignore,newState,events.last.srcId)
  }
  def hasErrors: Boolean = false

  @tailrec private def makeStatLine(
    currType: Long, currCount: Long, currSize: Long, updates: List[Update]
  ): List[Update] =
    if(updates.isEmpty || currType != updates.head.valueTypeId) {
      logger.info(s"t:${java.lang.Long.toHexString(currType)} c:$currCount s:$currSize")
      updates
    } else makeStatLine(currType,currCount+1,currSize+updates.head.value.size(),updates.tail)
  @tailrec private def makeStats(updates: List[Update]): Unit =
    if(updates.nonEmpty) makeStats(makeStatLine(updates.head.valueTypeId,0,0,updates))

  def save(rawSnapshot: RawSnapshot): Unit = {
    logger.info("Saving...")
    val updates = state.values.toList.sortBy(u⇒(u.valueTypeId,u.srcId))
    makeStats(updates)
    rawSnapshot.save(Updates(offset,updates))
    logger.info("OK")
  }
}

class OnceSnapshotMakingRawObserver(rawSnapshot: RawSnapshot, completing: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case world: SnapshotMakingRawWorld ⇒
    world.save(rawSnapshot)
    completing.activate(rawWorld)
  }
}

class PeriodicSnapshotMakingRawObserver(
  rawSnapshot: RawSnapshot, period: TemporalAmount, until: Instant=Instant.MIN
) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case world: SnapshotMakingRawWorld ⇒
      if(Instant.now.isBefore(until)) this else {
        world.save(rawSnapshot)
        new PeriodicSnapshotMakingRawObserver(rawSnapshot, period, Instant.now.plus(period))
      }
  }
}