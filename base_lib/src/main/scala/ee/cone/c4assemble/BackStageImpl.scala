package ee.cone.c4assemble

import ee.cone.c4assemble.Types._
import ee.cone.c4di.{c4, c4multi}

import scala.concurrent.{ExecutionContext, Future}

object PrepareBackStage extends WorldPartExpression {
  def transform(transition: WorldTransition): WorldTransition =
    transition.copy(prev=Option(transition), diff=emptyReadModel)
}

@c4multi("AssembleApp") final class ConnectBackStage[MapKey, Value](
  val outputWorldKeys: Seq[AssembledKey], //was=true
  val nextKeys:        Seq[AssembledKey], //was=false
)(
  updater: IndexUpdater,
  composes: IndexUtil
) extends WorldPartExpression {
  def transform(transition: WorldTransition): WorldTransition = {
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    val next = for {
      diff <- Future.sequence(nextKeys.map(_.of(transition.prev.get.diff)))
      result <- Future.sequence(nextKeys.map(_.of(transition.result)))
    } yield new IndexUpdates(diff,result,Nil)
    //println(s"AAA: $nextKey $diffPart")
    //println(s"BBB: $transition")
    //if(composes.isEmpty(diffPart)) transition else
    updater.setPart(outputWorldKeys,next,logTask = true)(transition)
  }
}

@c4("AssembleApp") final class BackStageFactoryImpl(factory: ConnectBackStageFactory) extends BackStageFactory {
  def create(l: List[DataDependencyFrom[_]]): List[WorldPartExpression] = {
    val wasKeys = (for {
      e <- l
      key <- Single.option(e.inputWorldKeys.collect{
        case k:JoinKey if k.was => k
      }) // multiple @was are not supported due to possible different join loop rates
    } yield key).distinct
    PrepareBackStage :: factory.create(wasKeys, wasKeys.map(_.withWas(was=false))) :: Nil
  }
}
