
import { useState, useEffect, createElement, useRef } from "react"

const initViewBox = "0 0 0 0"
const rotateStyle = (rotate) => rotate ? { transform: `rotate(${rotate})` } : {}

const adaptiveTag = "#adaptive"

const $ = createElement

const clear = (url) => url.replace(/#.*$/, "")

const SVGElement = ({ url, ...props }) => {
    const ref = useRef(null)
    const [state, setState] = useState({ viewBox: initViewBox, color: "black", content: "" })


    useEffect(() => {
        function replaceSvgTag(str) {
            return str.replace(/<\/?svg(.|\n)*?>/g, "")
    }

        function getViewBox(str) {
            const reg = /viewBox=["'](.+?)["']/
            const res = str.match(reg)
            return res ? res[1] : initViewBox
        }

        if (url.startsWith("data:")) {
            const decodedUrl = atob(url.replace(/data:.+?,/, ""))
            const viewBox = getViewBox(decodedUrl)
            setState(was => { return { ...was, viewBox, content: replaceSvgTag(decodedUrl) } })
        } else {
            fetch(url)
                .then(r => r.text())
                .then(r => {
                    const viewBox = getViewBox(r)
                    ref.current && setState(was => { return { ...was, viewBox, content: replaceSvgTag(r) } })
                })
        }
    }, [url])

    useEffect(() => {
        if (!ref.current) return

        const win = ref.current.ownerDocument.defaultView
        const color = !props.color || props.color == "adaptive" ? win.getComputedStyle(ref.current).color : props.color
        const { x, y, width, height } = ref.current.getBBox()
        const defViewBox = `${x} ${y} ${width} ${height}`
        const viewBox = props.viewPort ? props.viewPort : state.viewBox !== initViewBox ? state.viewBox : defViewBox
        if (state.color != color || state.viewBox != viewBox)
            setState(was => { return { ...was, viewBox, color } })
    })

    const size = state.viewBox == initViewBox ? { width: "0px", height: "0px" } : {}
    const htmlObject = { __html: state.content }
    return $("svg", {
        dangerouslySetInnerHTML: htmlObject,
        viewBox: state.viewBox,
        fill: state.color,
        ref: ref,
        className: props.className,
        style: props.style,
        ...size
    })
}

const ImageElement = ({ src, title, className: argClassName, rotate, color, viewPort, ...props }) => {
    const className = (argClassName || "") + (rotate ? " transitions" : "")
    const style = rotateStyle(rotate)
    if (color) {
        const __src = clear(src)
        const colorOpt = color === "adaptive" ? {} : {color}
        return $(SVGElement, { url: __src, className, style, ...colorOpt, viewPort })
    }
    else {
        return $("img", { src, title, className, style, ...props })
    }
}

export { ImageElement, SVGElement, adaptiveTag }