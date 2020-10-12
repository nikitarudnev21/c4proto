package ee.cone.c4ui

import ee.cone.c4di._
import ee.cone.c4actor.Context
import ee.cone.c4ui.ListTagTypes._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._


trait VGridCol
trait VGridCell
trait VGridCellContent extends OfDiv

object ListTagTypes {
  type VGridRoot = OfDiv
  type VFilterArea = OfDiv
}

trait VFilterItem
trait VFilterButton
trait VFilterButtonOption
trait VFilterItemContent extends OfDiv

abstract class CSSClassName(val value: String) extends Product
case object NoCSSClassName extends CSSClassName("")

sealed abstract class FilterButtonArea(val value: String) extends Product
case object LeftFilterButtonArea extends FilterButtonArea("lt")
case object RightFilterButtonArea extends FilterButtonArea("rt")

@c4tags("UICompApp") trait ListTags {
  @c4tag("GridRoot") def gridRoot(
    key: String,
    dragCol: Receiver[Context],
    dragRow: Receiver[Context],
    rowKeys: List[String],
    cols: List[VDom[VGridCol]],
    children: List[VDom[VGridCell]],
  ): VDom[VGridRoot]
  @c4tag("GridCol") def gridCol(
    key: String,
    colKey: String,
    hideWill: Int,
    minWidth: Int,
    maxWidth: Int,
    canDrag: Boolean,
    caption: String = "",
    isExpander: Boolean = false,
  ): VDom[VGridCol]
  @c4tag("GridCell") def gridCell(
    key: String,
    colKey: String,
    rowKey: String,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[VGridCellContent]] = Nil,
    isExpander: Boolean = false,
    isRowDragHandle: Boolean = false
  ): VDom[VGridCell]

  @c4tag("FilterArea") def filterArea(
    key: String,
    centerButtonText: String,
    filters: List[VDom[VFilterItem]] = Nil,
    buttons: List[VDom[VFilterButton]] = Nil,
  ): VDom[VFilterArea]
  @c4tag("FilterButton") def filterButton(
    key: String,
    minWidth: Int,
    activate: Receiver[Context],
    area: FilterButtonArea,
    className: CSSClassName = NoCSSClassName,
    caption: String = "",
  ): VDom[VFilterButton]
  @c4tag("FilterButtonExpander") def filterButtonExpander(
    key: String,
    minWidth: Int,
    area: FilterButtonArea,
    optButtons: List[VDom[VFilterButtonOption]],
    className: CSSClassName = NoCSSClassName,
  ): VDom[VFilterButton]
  @c4tag("FilterButtonOption") def filterButtonOption(
    key: String,
    minWidth: Int,
    activate: Receiver[Context],
    className: CSSClassName = NoCSSClassName,
    caption: String = "",
  ): VDom[VFilterButtonOption]
  @c4tag("FilterItem") def filterItem(
    key: String,
    minWidth: Int,
    maxWidth: Int,
    canHide: Boolean = false,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[VFilterItemContent]] = Nil,
  ): VDom[VFilterItem]

}