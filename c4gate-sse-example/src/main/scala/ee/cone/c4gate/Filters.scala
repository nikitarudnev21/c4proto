package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.CommonFilterProtocol.{Contains, DateBefore}
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui.{AccessView, AccessViewsApp}
import ee.cone.c4vdom.{ChildPair, OfDiv}

//todo access Meta from here, ex. precision
//todo ask if predicate active ?Option[Field=>Boolean]

//// mix

trait CommonFilterApp extends CommonFilterPredicateFactoriesApp
  with CommonFilterInjectApp with DateBeforeAccessViewApp with ContainsAccessViewApp

trait CommonFilterPredicateFactoriesApp {
  lazy val commonFilterConditionChecks: CommonFilterConditionChecks =
    CommonFilterConditionChecksImpl
}

trait CommonFilterInjectApp extends DefaultModelFactoriesApp {
  override def defaultModelFactories: List[DefaultModelFactory[_]] =
    DateBeforeDefault :: ContainsDefault :: super.defaultModelFactories
}

trait DateBeforeAccessViewApp extends AccessViewsApp {
  def testTags: TestTags[Context]
  private lazy val dateBeforeAccessView = DateBeforeAccessView(testTags,???)
  override def accessViews: List[AccessView[_]] = dateBeforeAccessView :: super.accessViews
}
trait ContainsAccessViewApp extends AccessViewsApp {
  def testTags: TestTags[Context]
  private lazy val containsAccessView = ContainsAccessView(testTags,???)
  override def accessViews: List[AccessView[_]] = containsAccessView :: super.accessViews
}


//// api

@protocol object CommonFilterProtocol extends Protocol {
  @Id(0x0006) case class DateBefore(
    @Id(0x0001) srcId: String,
    @Id(0x0002) value: Option[Long]
  )
  @Id(0x0007) case class Contains(
    @Id(0x0001) srcId: String,
    @Id(0x0002) value: String
  )
}

trait CommonFilterConditionChecks {
  implicit def dateBefore: ConditionCheck[DateBefore,Long]
  implicit def contains: ConditionCheck[Contains,String]
}

//// impl

object DateBeforeDefault extends DefaultModelFactory(classOf[DateBefore],DateBefore(_,None))
object ContainsDefault extends DefaultModelFactory(classOf[Contains],Contains(_,""))

case object DateBeforeCheck extends ConditionCheck[DateBefore,Long] {
  def check: DateBefore ⇒ Long ⇒ Boolean = by ⇒ value ⇒ by.value forall (_>value)
}

case object ContainsCheck extends ConditionCheck[Contains,String] {
  def check: Contains ⇒ String ⇒ Boolean = by ⇒ value ⇒ value contains by.value
}

object CommonFilterConditionChecksImpl extends CommonFilterConditionChecks {
  lazy val dateBefore: ConditionCheck[DateBefore,Long] = DateBeforeCheck
  lazy val contains: ConditionCheck[Contains,String] = ContainsCheck
}

@c4component @listed
case class DateBeforeAccessView(
  testTags: TestTags[Context],
  dateBeforeValue: ProdLens[DateBefore,Option[Long]] = ProdLens.of(_.value)
) extends AccessView(classOf[DateBefore]) {
  def view(access: Access[DateBefore]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.dateInput(access to dateBeforeValue))
}

@c4component @listed
case class ContainsAccessView(
  testTags: TestTags[Context],
  containsValue: ProdLens[Contains,String] = ProdLens.of(_.value)
) extends AccessView(classOf[Contains]) {
  def view(access: Access[Contains]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.input(access to containsValue))
}