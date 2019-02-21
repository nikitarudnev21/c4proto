package ee.cone.c4actor.tests

import ee.cone.c4actor.TestProtocol
import ee.cone.c4proto.{Cat, Id, Protocol, protocol, OrigCategory}

@protocol(Cat1) object TestProtocolMBase  {
    @Id(1) @Cat(Cat2, Cat1) case class LUL ()
    @Id(2)  @Cat(Cat1) case class LUL2 ()
}

case object Cat1 extends OrigCategory {
  def uid: Int = 0x001
}

case object Cat2 extends OrigCategory {
  def uid: Int = 0x002
}

object TestProtocolMain {
  def main(args: Array[String]): Unit = {
    println(TestProtocolM.adapters.map(_.categories))
  }
}
