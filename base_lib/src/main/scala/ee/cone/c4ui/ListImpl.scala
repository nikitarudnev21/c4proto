package ee.cone.c4ui

import java.text.{DecimalFormat, NumberFormat}

import ee.cone.c4di._
import ee.cone.c4actor.Context
import ee.cone.c4ui.ListTagTypes._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._


trait VGridCell


object ListTagTypes {
  type VGridRoot = OfDiv
  type VFilterArea = OfDiv
}

trait VFilterItem
trait VFilterButton
trait VFilterItemContent extends OfDiv

trait CSSClassName extends Product {
  def name: String
}
case object NoCSSClassName extends CSSClassName { def name: String = "" }

sealed abstract class FilterButtonArea(val value: String) extends Product
case object LeftFilterButtonArea extends FilterButtonArea("lt")
case object RightFilterButtonArea extends FilterButtonArea("rt")

sealed abstract class DragHandle(val value: String) extends Product
case object NoDragHandle extends DragHandle("")
case object ColDragHandle extends DragHandle("x")
case object RowDragHandle extends DragHandle("y")

sealed abstract class HighlightByAttr(val value: String) extends Product
case object RowHighlightByAttr extends HighlightByAttr("data-row-key")
case object ColHighlightByAttr extends HighlightByAttr("data-col-key")


@c4("UICompApp") final class IntJsonAdapterProvider(util: TagJsonUtils){
  @provide def forInt: Seq[JsonPairAdapter[Int]] =
    List(util.jsonPairAdapter((value,builder) => {
      val format = NumberFormat.getIntegerInstance match { case f: DecimalFormat => f } // to do once?
      builder.just.append(BigDecimal(value),format)
    }))
}

@c4("UICompApp") final class JsonAdapterUtil {
  def forList[T](forItem: (T,MutableJsonBuilder)=>Unit): (List[T],MutableJsonBuilder)=>Unit = (list,builder) => {
    builder.startArray()
    list.foreach(forItem(_,builder))
    builder.end()
  }
}

@c4("UICompApp") final class ListJsonAdapterProvider(
  util: TagJsonUtils,
  jsonAdapterUtil: JsonAdapterUtil,
){
  @provide def forCSSClassName: Seq[JsonPairAdapter[CSSClassName]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value.name)))
  @provide def forFilterButtonArea: Seq[JsonPairAdapter[FilterButtonArea]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value.value)))
  @provide def forDragHandle: Seq[JsonPairAdapter[DragHandle]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value.value)))
  @provide def forReceiver: Seq[JsonPairAdapter[Receiver[Context]]] = List(new JsonPairAdapter[Receiver[Context]]{
    def appendJson(key: String, value: Receiver[Context], builder: MutableJsonBuilder): Unit = {}
  })
  @provide def forStringList: Seq[JsonPairAdapter[List[String]]] =
    List(util.jsonPairAdapter(jsonAdapterUtil.forList((value, builder) => {
      builder.just.append(value)
    })))

  @provide def forBoolean: Seq[JsonPairAdapter[Boolean]] =
    List(util.jsonPairAdapter((value,builder) => builder.just.append(value)))

  @provide def forHighlightByAttr: Seq[JsonPairAdapter[HighlightByAttr]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value.value)))

}

@c4("UICompApp") final class GridColWidthJsonAdapterProvider(
  util: TagJsonUtils,
  intAdapter: JsonPairAdapter[Int],
){
  @provide def get: Seq[JsonPairAdapter[GridColWidth]] =
    List(util.jsonPairAdapter((value,builder) => {
      builder.startObject()
      builder.append("tp").append(value.tp)
      value match {
        case value: BoundGridColWidth =>
          intAdapter.appendJson("max",value.max,builder)
          intAdapter.appendJson("min",value.min,builder)
        case value: UnboundGridColWidth =>
          intAdapter.appendJson("min",value.min,builder)
      }
      builder.end()
    }))
}

@c4("UICompApp") final class GridColJsonAdapterProvider(
  util: TagJsonUtils,
  jsonAdapterUtil: JsonAdapterUtil,
  intAdapter: JsonPairAdapter[Int],
  gridColWidthAdapter: JsonPairAdapter[GridColWidth],
){
  @provide def forGridColList: Seq[JsonPairAdapter[List[GridCol]]] =
    List(util.jsonPairAdapter(jsonAdapterUtil.forList((value, builder) => {
      builder.startObject()
      builder.append("colKey").append(value.colKey)
      gridColWidthAdapter.appendJson("width", value.width, builder)
      intAdapter.appendJson("hideWill",value.hideWill,builder)
      if(value.isExpander) builder.append("isExpander").append(true)
      builder.end()
    })))
}


sealed abstract class GridColWidth(val tp: String) extends Product
case class BoundGridColWidth(min: Int, max: Int) extends GridColWidth("bound")
case class UnboundGridColWidth(min: Int) extends GridColWidth("unbound")

case class GridCol(
  colKey: String,
  width: GridColWidth,
  hideWill: Int,
  isExpander: Boolean = false,
)

@c4tags("UICompApp") trait ListTags {
  @c4tag("GridRoot") def gridRoot(
    key: String,
    dragCol: Receiver[Context],
    dragRow: Receiver[Context],
    rowKeys: List[String],
    cols: List[GridCol],
    children: List[VDom[VGridCell]],
  ): VDom[VGridRoot]
  @c4tag("GridCell") def gridCell(
    key: String,
    colKey: String,
    rowKey: String,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[OfDiv]] = Nil,
    isExpander: Boolean = false,
    dragHandle: DragHandle = NoDragHandle,
  ): VDom[VGridCell]

  @c4tag("FilterArea") def filterArea(
    key: String,
    centerButtonText: String,
    className: CSSClassName = NoCSSClassName,
    filters: List[VDom[VFilterItem]] = Nil,
    buttons: List[VDom[VFilterButton]] = Nil,
  ): VDom[VFilterArea]
  @c4tag("FilterButtonPlace") def filterButtonPlace(
    key: String,
    minWidth: Int,
    area: FilterButtonArea,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[OfDiv]] = Nil,
  ): VDom[VFilterButton]
  @c4tag("FilterButtonExpander") def filterButtonExpander(
    key: String,
    minWidth: Int,
    area: FilterButtonArea,
    className: CSSClassName = NoCSSClassName,
    popupClassName: CSSClassName = NoCSSClassName,
    popupItemClassName: CSSClassName = NoCSSClassName,
    children: List[VDom[OfDiv]] = Nil,
    openedChildren: List[VDom[OfDiv]] = Nil,
    optButtons: List[VDom[VFilterButton]] = Nil,
  ): VDom[VFilterButton]
  @c4tag("FilterItem") def filterItem(
    key: String,
    minWidth: Int,
    maxWidth: Int,
    canHide: Boolean = false,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[VFilterItemContent]] = Nil,
  ): VDom[VFilterItem]

  @c4tag("PopupManager") def popupManager(
    key: String,
    children: List[VDom[OfDiv]] = Nil,
  ): VDom[OfDiv]

  @c4tag("Highlighter") def highlighter(
    key: String,
    attrName: HighlightByAttr
  ): VDom[OfDiv]

}