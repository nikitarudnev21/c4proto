package ee.cone.c4gate

import ee.cone.c4actor.TestCat
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol(TestCat) object TestFilterProtocolBase   {
  @Id(0x0005) case class Content(
    @Id(0x0006) sessionKey: String,
    @Id(0x0007) value: String
  )
}

