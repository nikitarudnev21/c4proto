package ee.cone.c4actor.jms_processing

import ee.cone.c4actor.UpdatesCat
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol(UpdatesCat) object JmsProtocol extends Protocol {

  @Id(0x0a11) case class JmsIncomeMessage(
    @Id(0x0a0c) messageId: String,
    @Id(0x0a0b) jmsType: String,
    @Id(0x0acc) messageBody: String
  )

  @Id(0x0dab) case class JmsIncomeDoneMarker(
    @Id(0x0bad) srcId: String // messageId
  )

  @Id(0x014c) case class ModelsChanged(
    @Id(0x024c) srcId: String,
    @Id(0x024d) changedOrigs: List[ChangedOrig]
  )

  @Id(0x054a) case class ChangedOrig(
    @Id(0x065d) srcId: String,
    @Id(0x0325) valueTypeId: Long
  )

  @Id(0x0d45) case class ChangedProcessedMarker(
    @Id(0x0d32) srcId: String
  )

}
