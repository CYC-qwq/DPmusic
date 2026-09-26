/**
 * DPmusic · MusicFree 插件运行时 —— cheerio 兼容层（纯 JS，无外部依赖）
 *
 * 为什么需要它：MusicFree 插件运行在 Node（nodejs-mobile）上，可直接 require npm 的 cheerio；
 * 本应用用的是 QuickJS（无 Node 模块系统），因此这里用纯 JS 实现一份 cheerio 子集，
 * 由 `assets/script/musicfree-preload.js` 注册到 require 表里。
 *
 * 对齐范围（面向 MusicFree 插件的真实用法）：
 * - cheerio.load(html[, options]) → $ ；$ 支持 $(selector) / $(node) / $(cheerioObj) / $(array)
 * - 遍历与筛选：find / filter / not / is / has / children / contents / parent / parents / closest
 *               siblings / next / nextAll / prev / prevAll / eq / first / last / slice / each / map
 * - 读写：text / html / outerHTML / attr / removeAttr / prop / data / val / css
 *               hasClass / addClass / removeClass / toggleClass / clone / remove / empty
 * - 静态：$.html / $.text / $.root / $.parseHTML / $.contains / $.decodeEntities
 * - 选择器：* / tag / #id / .class / [attr] / [attr=v] / [attr^=|$=|*=|~=|!=] / 组合（空格 > + ~）/ 分组（,）
 *           伪类：:first-child :last-child :only-child :nth-child() :first-of-type :last-of-type
 *                 :nth-of-type() :not() :is() :has() :contains() :empty :root :parent
 *                 :checked :selected :disabled :enabled :required :header :input :hidden :visible
 * - options：{ xmlMode, decodeEntities }（xmlMode 下大小写敏感、不隐式闭合、不做实体解码）
 *
 * 实现要点：
 * - 节点结构对齐 domhandler：{type:'tag'|'text'|'comment'|'root', name, attribs, children, parent}
 * - 解析器容错：void 元素、自闭合、原始文本元素（script/style/textarea/title）、注释、
 *   <p>/<li>/<tr>/<td> 等隐式闭合、多余闭合标签忽略、'<' 后非字母当文本
 * - 选择器缓存（按字符串），匹配从右往左回溯，保持 DOM 文档序
 */
globalThis.__mf_cheerio__ = (function () {
  'use strict'

  /* ============================================================
   * 1. 实体解码
   * ============================================================ */
  var NAMED = {
    amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0',
    copy: '\u00a9', reg: '\u00ae', trade: '\u2122', hellip: '\u2026',
    mdash: '\u2014', ndash: '\u2013', minus: '\u2212',
    lsquo: '\u2018', rsquo: '\u2019', ldquo: '\u201c', rdquo: '\u201d',
    sbquo: '\u201a', bdquo: '\u201e', dagger: '\u2020', Dagger: '\u2021',
    middot: '\u00b7', bull: '\u2022', prime: '\u2032', Prime: '\u2033',
    deg: '\u00b0', times: '\u00d7', divide: '\u00f7', plusmn: '\u00b1',
    frac12: '\u00bd', frac14: '\u00bc', sup2: '\u00b2', sup3: '\u00b3',
    laquo: '\u00ab', raquo: '\u00bb', sect: '\u00a7', para: '\u00b6',
    euro: '\u20ac', pound: '\u00a3', yen: '\u00a5', cent: '\u00a2', curren: '\u00a4',
    larr: '\u2190', uarr: '\u2191', rarr: '\u2192', darr: '\u2193', harr: '\u2194',
    alpha: '\u03b1', beta: '\u03b2', gamma: '\u03b3', delta: '\u03b4',
    epsilon: '\u03b5', zeta: '\u03b6', eta: '\u03b7', theta: '\u03b8',
    iota: '\u03b9', kappa: '\u03ba', lambda: '\u03bb', mu: '\u03bc',
    nu: '\u03bd', xi: '\u03be', pi: '\u03c0', rho: '\u03c1',
    sigma: '\u03c3', tau: '\u03c4', phi: '\u03c6', chi: '\u03c7',
    psi: '\u03c8', omega: '\u03c9'
  }

  function cp2str(cp) {
    if (cp <= 0xffff) return String.fromCharCode(cp)
    cp -= 0x10000
    return String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff))
  }

  function decodeEntities(input) {
    if (input === null || input === undefined) return ''
    var s = String(input)
    if (s.indexOf('&') < 0) return s
    return s.replace(/&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,30});/g, function (whole, body) {
      if (body.charCodeAt(0) === 35) {
        var isHex = body.charCodeAt(1) === 120 || body.charCodeAt(1) === 88
        var cp = parseInt(isHex ? body.slice(2) : body.slice(1), isHex ? 16 : 10)
        if (!isFinite(cp) || cp < 0 || cp > 0x10ffff) return whole
        return cp2str(cp)
      }
      var v = NAMED[body]
      return v === undefined ? whole : v
    })
  }

  /* ============================================================
   * 2. 节点工具
   * ============================================================ */
  var VOID_ELEMENTS = {
    area: 1, base: 1, basefont: 1, bgsound: 1, br: 1, col: 1, embed: 1, frame: 1,
    hr: 1, img: 1, input: 1, keygen: 1, link: 1, meta: 1, param: 1, source: 1,
    track: 1, wbr: 1
  }
  var RAW_TEXT = {
    script: 1, style: 1, xmp: 1, iframe: 1, noembed: 1, noframes: 1,
    plaintext: 1, textarea: 1, title: 1
  }
  var P_BLOCKERS = {
    address: 1, article: 1, aside: 1, blockquote: 1, details: 1, div: 1, dl: 1,
    fieldset: 1, figcaption: 1, figure: 1, footer: 1, form: 1, h1: 1, h2: 1, h3: 1,
    h4: 1, h5: 1, h6: 1, header: 1, hgroup: 1, hr: 1, main: 1, menu: 1, nav: 1,
    ol: 1, p: 1, pre: 1, section: 1, table: 1, ul: 1
  }
  var AUTO_CLOSE = {
    li: { li: 1 },
    dt: { dt: 1, dd: 1 },
    dd: { dt: 1, dd: 1 },
    p: { p: 1 },
    tr: { tr: 1, td: 1, th: 1 },
    td: { td: 1, th: 1 },
    th: { td: 1, th: 1 },
    option: { option: 1 },
    optgroup: { option: 1, optgroup: 1 },
    thead: { tr: 1, td: 1, th: 1 },
    tbody: { thead: 1, tbody: 1, tr: 1, td: 1, th: 1 },
    tfoot: { thead: 1, tbody: 1, tfoot: 1, tr: 1, td: 1, th: 1 }
  }

  function isTag(n) { return !!n && n.type === 'tag' }
  function isText(n) { return !!n && n.type === 'text' }
  function isRoot(n) { return !!n && n.type === 'root' }
  function kids(n) { return (n && n.children) || [] }
  function hasOwn(o, k) { return Object.prototype.hasOwnProperty.call(o, k) }

  function elementKids(n) {
    var out = []
    var cs = kids(n)
    for (var i = 0; i < cs.length; i++) if (isTag(cs[i])) out.push(cs[i])
    return out
  }

  function textOf(n) {
    if (isText(n)) return n.data
    if (!n || !n.children) return ''
    var s = ''
    for (var i = 0; i < n.children.length; i++) s += textOf(n.children[i])
    return s
  }

  function escapeText(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
  var AMP = String.fromCharCode(38)
  var ENT_QUOT = AMP + 'quot;'

  function escapeAttr(s) {
    return String(s).replace(/&/g, '&amp;').replace(/"/g, ENT_QUOT).replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }

  function serialize(n) {
    if (!n) return ''
    if (isText(n)) return escapeText(n.data)
    if (n.type === 'comment') return '<!--' + n.data + '-->'
    if (isRoot(n)) {
      var s = ''
      for (var i = 0; i < kids(n).length; i++) s += serialize(kids(n)[i])
      return s
    }
    if (!isTag(n)) return ''
    var out = '<' + n.name
    var attrs = n.attribs || {}
    for (var k in attrs) {
      if (!hasOwn(attrs, k)) continue
      var v = attrs[k]
      if (v === null || v === undefined || v === false) continue
      out += v === '' ? ' ' + k : ' ' + k + '="' + escapeAttr(v) + '"'
    }
    if (n.selfClosed || VOID_ELEMENTS[n.name]) return out + '>'
    out += '>'
    for (var j = 0; j < kids(n).length; j++) out += serialize(kids(n)[j])
    return out + '</' + n.name + '>'
  }

  function innerHTML(n) {
    var s = ''
    for (var i = 0; i < kids(n).length; i++) s += serialize(kids(n)[i])
    return s
  }

  function rootOf(n) {
    var cur = n
    while (cur && cur.parent) cur = cur.parent
    return cur
  }

  function cloneNode(n) {
    if (!n) return n
    if (isText(n)) return { type: 'text', data: n.data, parent: null }
    if (n.type === 'comment') return { type: 'comment', data: n.data, parent: null }
    var copy
    if (isRoot(n)) {
      copy = { type: 'root', name: 'root', children: [], parent: null }
    } else {
      copy = {
        type: 'tag', name: n.name,
        attribs: Object.assign({}, n.attribs || {}),
        children: [], parent: null, selfClosed: n.selfClosed
      }
    }
    var cs = kids(n)
    for (var i = 0; i < cs.length; i++) {
      var c = cloneNode(cs[i])
      c.parent = copy
      copy.children.push(c)
    }
    return copy
  }

  function isDescendant(node, ancestor) {
    var cur = node ? node.parent : null
    while (cur) {
      if (cur === ancestor) return true
      cur = cur.parent
    }
    return false
  }

  /* ============================================================
   * 3. HTML 解析（容错）
   * ============================================================ */
  function parseHTML(input, options) {
    var opts = options || {}
    var xmlMode = !!opts.xmlMode
    var decode = opts.decodeEntities !== false && !xmlMode
    var src = input === null || input === undefined ? '' : String(input)

    var root = { type: 'root', name: 'root', children: [], parent: null }
    var stack = [root]
    var i = 0
    var len = src.length

    function top() { return stack[stack.length - 1] }

    function pushText(raw) {
      if (!raw) return
      var data = decode ? decodeEntities(raw) : raw
      if (!data) return
      var parent = top()
      var last = parent.children[parent.children.length - 1]
      if (last && last.type === 'text') { last.data += data; return }
      parent.children.push({ type: 'text', data: data, parent: parent })
    }

    function closeTo(name) {
      for (var k = stack.length - 1; k >= 1; k--) {
        if (stack[k].name === name) { stack.length = k; return true }
      }
      return false
    }

    while (i < len) {
      var lt = src.indexOf('<', i)
      if (lt < 0) { pushText(src.slice(i)); break }
      if (lt > i) pushText(src.slice(i, lt))

      // 注释
      if (src.substr(lt, 4) === '<!--') {
        var cEnd = src.indexOf('-->', lt + 4)
        var cData = cEnd < 0 ? src.slice(lt + 4) : src.slice(lt + 4, cEnd)
        var cParent = top()
        cParent.children.push({ type: 'comment', data: cData, parent: cParent })
        i = cEnd < 0 ? len : cEnd + 3
        continue
      }
      // doctype / 处理指令
      if (src.charAt(lt + 1) === '!' || src.charAt(lt + 1) === '?') {
        var dEnd = src.indexOf('>', lt)
        i = dEnd < 0 ? len : dEnd + 1
        continue
      }
      // 闭合标签
      if (src.charAt(lt + 1) === '/') {
        var j = lt + 2
        while (j < len && /\s/.test(src.charAt(j))) j++
        var name = ''
        while (j < len && !/[\s>/]/.test(src.charAt(j))) { name += src.charAt(j); j++ }
        var gt = src.indexOf('>', j)
        i = gt < 0 ? len : gt + 1
        if (name) closeTo(xmlMode ? name : name.toLowerCase())
        continue
      }
      // '<' 后不是字母 → 当文本
      if (!/[a-zA-Z]/.test(src.charAt(lt + 1))) {
        pushText('<')
        i = lt + 1
        continue
      }

      // 开标签
      var p = lt + 1
      var rawName = ''
      while (p < len && !/[\s>/]/.test(src.charAt(p))) { rawName += src.charAt(p); p++ }
      var lower = rawName.toLowerCase()
      var attrs = {}
      var selfClose = false

      while (p < len) {
        while (p < len && /\s/.test(src.charAt(p))) p++
        if (p >= len) break
        if (src.charAt(p) === '>') { p++; break }
        if (src.charAt(p) === '/' && src.charAt(p + 1) === '>') { selfClose = true; p += 2; break }
        var an = ''
        while (p < len && !/[\s=>/]/.test(src.charAt(p))) { an += src.charAt(p); p++ }
        if (!an) { p++; continue }
        while (p < len && /\s/.test(src.charAt(p))) p++
        var av = ''
        if (src.charAt(p) === '=') {
          p++
          while (p < len && /\s/.test(src.charAt(p))) p++
          var q = src.charAt(p)
          if (q === '"' || q === "'") {
            p++
            var qEnd = src.indexOf(q, p)
            av = qEnd < 0 ? src.slice(p) : src.slice(p, qEnd)
            p = qEnd < 0 ? len : qEnd + 1
          } else {
            while (p < len && !/[\s>]/.test(src.charAt(p))) { av += src.charAt(p); p++ }
          }
        }
        var key = xmlMode ? an : an.toLowerCase()
        if (!hasOwn(attrs, key)) attrs[key] = decode ? decodeEntities(av) : av
      }

      i = p
      var node = {
        type: 'tag',
        name: xmlMode ? rawName : lower,
        attribs: attrs,
        children: [],
        parent: null,
        selfClosed: selfClose || (!xmlMode && !!VOID_ELEMENTS[lower])
      }
      var parentNode = top()
      node.parent = parentNode
      parentNode.children.push(node)

      if (node.selfClosed) continue

      // 原始文本元素：内容整段当文本
      if (!xmlMode && RAW_TEXT[lower]) {
        var rest = src.slice(i)
        var re = new RegExp('</' + lower + '(?=[\\s/>])', 'i')
        var m = re.exec(rest)
        var raw = m ? rest.slice(0, m.index) : rest
        if (raw) node.children.push({ type: 'text', data: raw, parent: node })
        if (m) {
          var g2 = src.indexOf('>', i + m.index + 2 + lower.length)
          i = g2 < 0 ? len : g2 + 1
        } else {
          i = len
        }
        continue
      }

      // 隐式闭合
      if (!xmlMode) {
        var ac = AUTO_CLOSE[lower]
        if (ac) {
          while (stack.length > 1 && ac[top().name]) stack.pop()
        }
        if (P_BLOCKERS[lower]) {
          while (stack.length > 1 && top().name === 'p') stack.pop()
        }
      }

      stack.push(node)
    }

    return root
  }

  /* ============================================================
   * 4. 选择器解析与匹配
   * ============================================================ */
  var selectorCache = {}

  function newCompound() {
    return { tag: null, id: null, classes: [], attrs: [], pseudos: [], hasAny: false }
  }

  function parseAttrSelector(inner) {
    var m = /^\s*([^\s~|^$*=!\]]+)\s*(?:([~|^$*!]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\s\]]*))\s*)?$/.exec(inner)
    if (!m) return { name: String(inner).trim().toLowerCase(), op: null, value: null }
    var name = m[1].toLowerCase()
    var op = m[2] || null
    var value = m[3] !== undefined ? m[3] : (m[4] !== undefined ? m[4] : m[5])
    if (value === undefined) value = null
    return { name: name, op: op, value: value }
  }

  var IDENT_STOP = /[\s.#\[:>,+~]/

  function parseSelector(selector) {
    var key = String(selector)
    if (hasOwn(selectorCache, key)) return selectorCache[key]

    var groups = []
    var chain = []
    var compound = newCompound()
    var pendingComb = ' '
    var explicitComb = false
    var i = 0
    var s = key
    var len = s.length

    function flush() {
      if (compound.hasAny) {
        chain.push({ comb: chain.length === 0 ? ' ' : pendingComb, compound: compound })
        compound = newCompound()
        explicitComb = false
      }
    }
    function flushGroup() {
      flush()
      if (chain.length) groups.push(chain)
      chain = []
      pendingComb = ' '
    }

    while (i < len) {
      var c = s.charAt(i)

      if (/\s/.test(c)) { flush(); if (!explicitComb) pendingComb = ' '; i++; continue }
      if (c === '>' || c === '+' || c === '~') { flush(); pendingComb = c; explicitComb = true; i++; continue }
      if (c === ',') { flushGroup(); i++; continue }

      if (c === '*') { compound.tag = '*'; compound.hasAny = true; i++; continue }

      if (c === '#') {
        i++
        var idv = ''
        while (i < len && !IDENT_STOP.test(s.charAt(i))) { idv += s.charAt(i); i++ }
        compound.id = idv
        compound.hasAny = true
        continue
      }
      if (c === '.') {
        i++
        var clv = ''
        while (i < len && !IDENT_STOP.test(s.charAt(i))) { clv += s.charAt(i); i++ }
        compound.classes.push(clv)
        compound.hasAny = true
        continue
      }
      if (c === '[') {
        var depth = 1
        var inner = ''
        i++
        while (i < len) {
          var ch = s.charAt(i)
          if (ch === '[') depth++
          else if (ch === ']') { depth--; if (depth === 0) { i++; break } }
          inner += ch
          i++
        }
        compound.attrs.push(parseAttrSelector(inner))
        compound.hasAny = true
        continue
      }
      if (c === ':') {
        var isPseudoElement = false
        i++
        if (s.charAt(i) === ':') { isPseudoElement = true; i++ }
        var pname = ''
        while (i < len && /[a-zA-Z-]/.test(s.charAt(i))) { pname += s.charAt(i); i++ }
        var arg = null
        if (s.charAt(i) === '(') {
          var d2 = 1
          var buf = ''
          i++
          while (i < len) {
            var ch2 = s.charAt(i)
            if (ch2 === '(') d2++
            else if (ch2 === ')') { d2--; if (d2 === 0) { i++; break } }
            buf += ch2
            i++
          }
          arg = buf.trim()
        }
        if (!isPseudoElement) {
          compound.pseudos.push({ name: pname.toLowerCase(), arg: arg })
          compound.hasAny = true
        }
        continue
      }

      // 标签名（含转义）
      var tag = ''
      while (i < len && !IDENT_STOP.test(s.charAt(i))) {
        if (s.charAt(i) === '\\' && i + 1 < len) { tag += s.charAt(i + 1); i += 2; continue }
        tag += s.charAt(i); i++
      }
      if (tag) {
        compound.tag = compound.tag ? compound.tag + tag : tag
        compound.hasAny = true
      } else {
        i++
      }
    }
    flushGroup()

    selectorCache[key] = groups
    return groups
  }

  function matchAttr(node, a) {
    var attrs = node.attribs || {}
    var has = hasOwn(attrs, a.name)
    if (!a.op) return has
    if (!has) return false
    var v = attrs[a.name] === null || attrs[a.name] === undefined ? '' : String(attrs[a.name])
    var t = a.value === null || a.value === undefined ? '' : String(a.value)
    switch (a.op) {
      case '=': return v === t
      case '!=': return v !== t
      case '~=': return v.split(/\s+/).indexOf(t) >= 0
      case '|=': return v === t || v.indexOf(t + '-') === 0
      case '^=': return t !== '' && v.indexOf(t) === 0
      case '$=': return t !== '' && v.slice(-t.length) === t
      case '*=': return t !== '' && v.indexOf(t) >= 0
      default: return false
    }
  }

  function elemIndex(node) {
    var p = node.parent
    if (!p) return 0
    var cs = kids(p)
    var idx = 0
    for (var i = 0; i < cs.length; i++) {
      if (isTag(cs[i])) {
        if (cs[i] === node) return idx
        idx++
      }
    }
    return -1
  }

  function typeIndex(node) {
    var p = node.parent
    if (!p) return 0
    var cs = kids(p)
    var idx = 0
    for (var i = 0; i < cs.length; i++) {
      if (isTag(cs[i]) && cs[i].name === node.name) {
        if (cs[i] === node) return idx
        idx++
      }
    }
    return -1
  }

  function nthMatch(nth, idx) {
    var n = idx + 1
    if (nth === 'odd') return n % 2 === 1
    if (nth === 'even') return n % 2 === 0
    var m = /^([+-]?\d*)n\s*(?:([+-])\s*(\d+))?$/.exec(nth)
    if (m) {
      var a = m[1] === '' || m[1] === '+' ? 1 : (m[1] === '-' ? -1 : parseInt(m[1], 10))
      var b = m[2] ? (m[2] === '-' ? -parseInt(m[3], 10) : parseInt(m[3], 10)) : 0
      if (a === 0) return n === b
      var k = (n - b) / a
      return k >= 0 && k === Math.floor(k)
    }
    var num = parseInt(nth, 10)
    return isFinite(num) ? n === num : false
  }

  function matchPseudo(node, p, root) {
    var attrs = node.attribs || {}
    switch (p.name) {
      case 'first-child': return elemIndex(node) === 0
      case 'last-child': {
        var pc = node.parent
        return !!pc && elemIndex(node) === elementKids(pc).length - 1
      }
      case 'only-child': return node.parent ? elementKids(node.parent).length === 1 : false
      case 'nth-child': return nthMatch(p.arg || '', elemIndex(node))
      case 'first-of-type': return typeIndex(node) === 0
      case 'last-of-type': {
        var pt = node.parent
        if (!pt) return false
        var same = 0
        var cs = elementKids(pt)
        for (var i = 0; i < cs.length; i++) if (cs[i].name === node.name) same++
        return typeIndex(node) === same - 1
      }
      case 'nth-of-type': return nthMatch(p.arg || '', typeIndex(node))
      case 'only-of-type': {
        var po = node.parent
        if (!po) return false
        var cnt = 0
        var cs2 = elementKids(po)
        for (var j = 0; j < cs2.length; j++) if (cs2[j].name === node.name) cnt++
        return cnt === 1
      }
      case 'not': return !(p.arg && matchAnySelector(node, p.arg, root))
      case 'is':
      case 'matches': return !!(p.arg && matchAnySelector(node, p.arg, root))
      case 'has': return !!(p.arg && selectWithin(node, p.arg, root).length > 0)
      case 'contains': return textOf(node).indexOf(p.arg || '') >= 0
      case 'empty': return kids(node).length === 0
      case 'root': return isRoot(node)
      case 'parent': return kids(node).length > 0
      case 'checked': return hasOwn(attrs, 'checked')
      case 'selected': return hasOwn(attrs, 'selected')
      case 'disabled': return hasOwn(attrs, 'disabled')
      case 'enabled': return !hasOwn(attrs, 'disabled')
      case 'required': return hasOwn(attrs, 'required')
      case 'read-only': return hasOwn(attrs, 'readonly')
      case 'header': return /^h[1-6]$/.test(node.name)
      case 'input': return /^(input|select|textarea|button)$/.test(node.name)
      case 'button': return node.name === 'button'
      case 'hidden': return hasOwn(attrs, 'hidden') || attrs.type === 'hidden'
      case 'visible': return !hasOwn(attrs, 'hidden') && attrs.type !== 'hidden'
      default: return false
    }
  }

  function matchCompound(node, c, root) {
    if (!isTag(node)) return false
    if (c.tag && c.tag !== '*') {
      if (node.name !== c.tag && node.name !== c.tag.toLowerCase()) return false
    }
    var attrs = node.attribs || {}
    if (c.id !== null && c.id !== undefined) {
      if (attrs.id !== c.id) return false
    }
    for (var i = 0; i < c.classes.length; i++) {
      var cls = c.classes[i]
      var cv = attrs.class
      if (!cv || (' ' + cv + ' ').indexOf(' ' + cls + ' ') < 0) return false
    }
    for (var j = 0; j < c.attrs.length; j++) if (!matchAttr(node, c.attrs[j])) return false
    for (var k = 0; k < c.pseudos.length; k++) if (!matchPseudo(node, c.pseudos[k], root)) return false
    return true
  }

  function prevElement(node) {
    var p = node.parent
    if (!p) return null
    var cs = kids(p)
    var idx = cs.indexOf(node)
    for (var i = idx - 1; i >= 0; i--) if (isTag(cs[i])) return cs[i]
    return null
  }

  function nextElement(node) {
    var p = node.parent
    if (!p) return null
    var cs = kids(p)
    var idx = cs.indexOf(node)
    for (var i = idx + 1; i < cs.length; i++) if (isTag(cs[i])) return cs[i]
    return null
  }

  function matchChain(node, chain, root) {
    var i = chain.length - 1
    if (i < 0) return false
    if (!matchCompound(node, chain[i].compound, root)) return false

    while (i > 0) {
      var comb = chain[i].comb
      var prevChain = chain.slice(0, i)

      if (comb === '>') {
        var p = node.parent
        if (!p || !isTag(p)) return false
        if (!matchChain(p, prevChain, root)) return false
        node = p
        i--
        continue
      }
      if (comb === ' ') {
        var cur = node.parent
        while (cur && isTag(cur)) {
          if (matchChain(cur, prevChain, root)) return true
          cur = cur.parent
        }
        return false
      }
      // '+' / '~'
      if (comb === '+') {
        var s1 = prevElement(node)
        return !!s1 && matchChain(s1, prevChain, root)
      }
      var s2 = prevElement(node)
      while (s2) {
        if (matchChain(s2, prevChain, root)) return true
        s2 = prevElement(s2)
      }
      return false
    }
    return true
  }

  function matchAnySelector(node, selector, root) {
    var groups = parseSelector(selector)
    for (var i = 0; i < groups.length; i++) {
      if (matchChain(node, groups[i], root)) return true
    }
    return false
  }

  function collectDescendants(node, out) {
    var cs = kids(node)
    for (var i = 0; i < cs.length; i++) {
      if (isTag(cs[i])) {
        out.push(cs[i])
        collectDescendants(cs[i], out)
      }
    }
    return out
  }

  function selectWithin(ctx, selector, root) {
    var groups = parseSelector(selector)
    var pool = collectDescendants(ctx, [])
    var out = []
    for (var i = 0; i < pool.length; i++) {
      for (var g = 0; g < groups.length; g++) {
        if (matchChain(pool[i], groups[g], root)) { out.push(pool[i]); break }
      }
    }
    return out
  }

  /* ============================================================
   * 5. Cheerio 对象
   * ============================================================ */
  function Cheerio(nodes, root, options) {
    this.nodes = nodes || []
    this._root = root || null
    this.options = options || {}
    this.length = this.nodes.length
    for (var i = 0; i < this.nodes.length; i++) this[i] = this.nodes[i]
  }

  Cheerio.prototype._wrap = function (nodes) {
    return new Cheerio(nodes, this._root, this.options)
  }
  Cheerio.prototype._rootNode = function () {
    if (this._root) return this._root
    return this.nodes.length ? rootOf(this.nodes[0]) : null
  }

  Cheerio.prototype.toArray = function () { return this.nodes.slice() }
  Cheerio.prototype.get = function (i) {
    if (i === undefined) return this.nodes.slice()
    return i < 0 ? this.nodes[this.nodes.length + i] : this.nodes[i]
  }
  Cheerio.prototype.eq = function (i) {
    var n = i < 0 ? this.nodes.length + i : i
    return this._wrap(this.nodes[n] ? [this.nodes[n]] : [])
  }
  Cheerio.prototype.first = function () {
    return this._wrap(this.nodes.length ? [this.nodes[0]] : [])
  }
  Cheerio.prototype.last = function () {
    return this._wrap(this.nodes.length ? [this.nodes[this.nodes.length - 1]] : [])
  }
  Cheerio.prototype.slice = function (a, b) { return this._wrap(this.nodes.slice(a, b)) }

  Cheerio.prototype.each = function (fn) {
    for (var i = 0; i < this.nodes.length; i++) fn.call(this.nodes[i], i, this.nodes[i])
    return this
  }

  Cheerio.prototype.map = function (fn) {
    var out = []
    for (var i = 0; i < this.nodes.length; i++) {
      var r = fn.call(this.nodes[i], i, this.nodes[i])
      if (r === null || r === undefined) continue
      if (Array.isArray(r)) {
        for (var j = 0; j < r.length; j++) if (r[j] !== null && r[j] !== undefined) out.push(r[j])
      } else {
        out.push(r)
      }
    }
    return this._wrap(out)
  }

  Cheerio.prototype.filter = function (fnOrSel) {
    var root = this._rootNode()
    var out = []
    var i
    if (typeof fnOrSel === 'function') {
      for (i = 0; i < this.nodes.length; i++) {
        if (fnOrSel.call(this.nodes[i], i, this.nodes[i])) out.push(this.nodes[i])
      }
    } else {
      var groups = parseSelector(String(fnOrSel))
      for (i = 0; i < this.nodes.length; i++) {
        var n = this.nodes[i]
        if (!isTag(n)) continue
        for (var g = 0; g < groups.length; g++) {
          if (matchChain(n, groups[g], root)) { out.push(n); break }
        }
      }
    }
    return this._wrap(out)
  }

  Cheerio.prototype.not = function (fnOrSel) {
    var excluded = this.filter(fnOrSel).nodes
    var out = []
    for (var i = 0; i < this.nodes.length; i++) {
      if (excluded.indexOf(this.nodes[i]) < 0) out.push(this.nodes[i])
    }
    return this._wrap(out)
  }

  Cheerio.prototype.is = function (fnOrSel) {
    return this.filter(fnOrSel).nodes.length > 0
  }

  Cheerio.prototype.has = function (selOrNode) {
    var root = this._rootNode()
    var out = []
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      if (!isTag(n)) continue
      if (typeof selOrNode === 'string') {
        if (selectWithin(n, selOrNode, root).length > 0) out.push(n)
      } else {
        var t = unwrapContext(selOrNode)
        if (t && isDescendant(t, n)) out.push(n)
      }
    }
    return this._wrap(out)
  }

  Cheerio.prototype.find = function (sel) {
    var root = this._rootNode()
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      if (!isTag(n) && !isRoot(n)) continue
      var found = selectWithin(n, sel, root)
      for (var j = 0; j < found.length; j++) {
        if (seen.indexOf(found[j]) < 0) { seen.push(found[j]); out.push(found[j]) }
      }
    }
    return this._wrap(out)
  }

  Cheerio.prototype.children = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var cs = elementKids(this.nodes[i])
      for (var j = 0; j < cs.length; j++) {
        if (seen.indexOf(cs[j]) < 0) { seen.push(cs[j]); out.push(cs[j]) }
      }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.contents = function () {
    var out = []
    for (var i = 0; i < this.nodes.length; i++) {
      var cs = kids(this.nodes[i])
      for (var j = 0; j < cs.length; j++) out.push(cs[j])
    }
    return this._wrap(out)
  }

  Cheerio.prototype.parent = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var p = this.nodes[i].parent
      if (p && isTag(p) && seen.indexOf(p) < 0) { seen.push(p); out.push(p) }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.parents = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var cur = this.nodes[i].parent
      while (cur && isTag(cur)) {
        if (seen.indexOf(cur) < 0) { seen.push(cur); out.push(cur) }
        cur = cur.parent
      }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.closest = function (sel) {
    var out = []
    var seen = []
    var root = this._rootNode()
    for (var i = 0; i < this.nodes.length; i++) {
      var cur = this.nodes[i]
      while (cur && isTag(cur)) {
        if (matchAnySelector(cur, sel, root)) {
          if (seen.indexOf(cur) < 0) { seen.push(cur); out.push(cur) }
          break
        }
        cur = cur.parent
      }
    }
    return this._wrap(out)
  }

  Cheerio.prototype.siblings = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var p = this.nodes[i].parent
      if (!p) continue
      var cs = elementKids(p)
      for (var j = 0; j < cs.length; j++) {
        if (cs[j] !== this.nodes[i] && seen.indexOf(cs[j]) < 0) { seen.push(cs[j]); out.push(cs[j]) }
      }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.next = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var s = nextElement(this.nodes[i])
      if (s && seen.indexOf(s) < 0) { seen.push(s); out.push(s) }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.nextAll = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var s = nextElement(this.nodes[i])
      while (s) {
        if (seen.indexOf(s) < 0) { seen.push(s); out.push(s) }
        s = nextElement(s)
      }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.nextUntil = function (sel) {
    var out = []
    var seen = []
    var root = this._rootNode()
    for (var i = 0; i < this.nodes.length; i++) {
      var s = nextElement(this.nodes[i])
      while (s) {
        if (sel && matchAnySelector(s, sel, root)) break
        if (seen.indexOf(s) < 0) { seen.push(s); out.push(s) }
        s = nextElement(s)
      }
    }
    return this._wrap(out)
  }

  Cheerio.prototype.prev = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var s = prevElement(this.nodes[i])
      if (s && seen.indexOf(s) < 0) { seen.push(s); out.push(s) }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.prevAll = function (sel) {
    var out = []
    var seen = []
    for (var i = 0; i < this.nodes.length; i++) {
      var s = prevElement(this.nodes[i])
      while (s) {
        if (seen.indexOf(s) < 0) { seen.push(s); out.push(s) }
        s = prevElement(s)
      }
    }
    var wrapped = this._wrap(out)
    return sel ? wrapped.filter(sel) : wrapped
  }

  Cheerio.prototype.index = function (el) {
    if (el === undefined) {
      var n = this.nodes[0]
      if (!n || !n.parent) return -1
      return elementKids(n.parent).indexOf(n)
    }
    var t = unwrapContext(el)
    if (!t) return -1
    return this.nodes.indexOf(t)
  }

  Cheerio.prototype.add = function (selOrNode) {
    var root = this._rootNode()
    var extra = typeof selOrNode === 'string'
      ? selectWithin(root, selOrNode, root)
      : nodeListFrom(selOrNode)
    var out = this.nodes.slice()
    for (var i = 0; i < extra.length; i++) if (out.indexOf(extra[i]) < 0) out.push(extra[i])
    return this._wrap(out)
  }

  Cheerio.prototype.text = function (str) {
    if (str === undefined) {
      var s = ''
      for (var i = 0; i < this.nodes.length; i++) s += textOf(this.nodes[i])
      return s
    }
    for (var j = 0; j < this.nodes.length; j++) {
      var n = this.nodes[j]
      if (!isTag(n)) continue
      n.children = [{ type: 'text', data: String(str), parent: n }]
    }
    return this
  }

  Cheerio.prototype.html = function (str) {
    if (str === undefined) {
      var n = this.nodes[0]
      return n ? innerHTML(n) : null
    }
    for (var i = 0; i < this.nodes.length; i++) {
      var el = this.nodes[i]
      if (!isTag(el)) continue
      var frag = parseHTML(String(str), this.options)
      el.children = []
      for (var j = 0; j < frag.children.length; j++) {
        var c = frag.children[j]
        c.parent = el
        el.children.push(c)
      }
    }
    return this
  }

  Cheerio.prototype.outerHTML = function () {
    var n = this.nodes[0]
    return n ? serialize(n) : null
  }

  Cheerio.prototype.toString = function () {
    var s = ''
    for (var i = 0; i < this.nodes.length; i++) s += serialize(this.nodes[i])
    return s
  }

  Cheerio.prototype.attr = function (name, value) {
    if (name && typeof name === 'object') {
      for (var k in name) if (hasOwn(name, k)) this.attr(k, name[k])
      return this
    }
    if (value === undefined) {
      var n = this.nodes[0]
      if (!isTag(n)) return undefined
      var a = n.attribs || {}
      return hasOwn(a, name) ? a[name] : undefined
    }
    for (var i = 0; i < this.nodes.length; i++) {
      var el = this.nodes[i]
      if (!isTag(el)) continue
      el.attribs = el.attribs || {}
      if (value === null || value === false) delete el.attribs[name]
      else el.attribs[name] = String(value)
    }
    return this
  }

  Cheerio.prototype.removeAttr = function (name) {
    for (var i = 0; i < this.nodes.length; i++) {
      var el = this.nodes[i]
      if (isTag(el) && el.attribs) delete el.attribs[name]
    }
    return this
  }

  Cheerio.prototype.prop = function (name, value) {
    if (value === undefined) {
      var n = this.nodes[0]
      if (!isTag(n)) return undefined
      var a = n.attribs || {}
      switch (name) {
        case 'tagName':
        case 'nodeName': return n.name.toUpperCase()
        case 'outerHTML': return serialize(n)
        case 'innerHTML': return innerHTML(n)
        case 'textContent':
        case 'innerText': return textOf(n)
        case 'className': return a.class || ''
        case 'checked':
        case 'selected':
        case 'disabled':
        case 'multiple':
        case 'readonly': return hasOwn(a, name)
        default: return a[name]
      }
    }
    for (var i = 0; i < this.nodes.length; i++) {
      var el = this.nodes[i]
      if (!isTag(el)) continue
      el.attribs = el.attribs || {}
      if (value === false || value === null) delete el.attribs[name]
      else el.attribs[name] = value === true ? '' : String(value)
    }
    return this
  }

  Cheerio.prototype.data = function (key, value) {
    var dashed = 'data-' + String(key).replace(/[A-Z]/g, function (m) { return '-' + m.toLowerCase() })
    if (value === undefined) {
      var raw = this.attr(dashed)
      if (raw === undefined) return undefined
      var t = String(raw)
      if (t === 'true') return true
      if (t === 'false') return false
      if (t !== '' && !isNaN(Number(t))) return Number(t)
      if (t.charAt(0) === '{' || t.charAt(0) === '[') {
        try { return JSON.parse(t) } catch (e) { return t }
      }
      return t
    }
    return this.attr(dashed, typeof value === 'object' ? JSON.stringify(value) : value)
  }

  Cheerio.prototype.val = function (value) {
    if (value === undefined) {
      var n = this.nodes[0]
      if (!isTag(n)) return undefined
      var a = n.attribs || {}
      if (n.name === 'select') {
        var opts = collectDescendants(n, [])
        var selected = null
        for (var i = 0; i < opts.length; i++) {
          if (opts[i].name === 'option' && hasOwn(opts[i].attribs || {}, 'selected')) {
            selected = (opts[i].attribs || {}).value !== undefined ? opts[i].attribs.value : textOf(opts[i])
          }
        }
        if (selected !== null) return selected
        var first = null
        for (var j = 0; j < opts.length; j++) {
          if (opts[j].name === 'option') {
            first = (opts[j].attribs || {}).value !== undefined ? opts[j].attribs.value : textOf(opts[j])
            break
          }
        }
        return first === null ? undefined : first
      }
      if (n.name === 'textarea') return textOf(n)
      return a.value
    }
    for (var k = 0; k < this.nodes.length; k++) {
      var el = this.nodes[k]
      if (!isTag(el)) continue
      if (el.name === 'textarea') el.children = [{ type: 'text', data: String(value), parent: el }]
      else this._wrap([el]).attr('value', value)
    }
    return this
  }

  Cheerio.prototype.hasClass = function (cls) {
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      if (!isTag(n)) continue
      var v = (n.attribs || {}).class
      if (v && (' ' + v + ' ').indexOf(' ' + cls + ' ') >= 0) return true
    }
    return false
  }

  Cheerio.prototype.addClass = function (cls) {
    var list = String(cls).split(/\s+/)
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      if (!isTag(n)) continue
      n.attribs = n.attribs || {}
      var cur = (n.attribs.class || '').split(/\s+/).filter(function (x) { return x })
      for (var j = 0; j < list.length; j++) {
        if (list[j] && cur.indexOf(list[j]) < 0) cur.push(list[j])
      }
      n.attribs.class = cur.join(' ')
    }
    return this
  }

  Cheerio.prototype.removeClass = function (cls) {
    if (cls === undefined) {
      for (var i = 0; i < this.nodes.length; i++) {
        if (isTag(this.nodes[i]) && this.nodes[i].attribs) delete this.nodes[i].attribs.class
      }
      return this
    }
    var list = String(cls).split(/\s+/)
    for (var k = 0; k < this.nodes.length; k++) {
      var n = this.nodes[k]
      if (!isTag(n)) continue
      var cur = ((n.attribs || {}).class || '').split(/\s+/).filter(function (x) { return x && list.indexOf(x) < 0 })
      if (cur.length) n.attribs.class = cur.join(' ')
      else if (n.attribs) delete n.attribs.class
    }
    return this
  }

  Cheerio.prototype.toggleClass = function (cls) {
    return this.hasClass(cls) ? this.removeClass(cls) : this.addClass(cls)
  }

  Cheerio.prototype.css = function (name, value) {
    var prop = String(name).replace(/[A-Z]/g, function (m) { return '-' + m.toLowerCase() })
    if (value === undefined) {
      var n = this.nodes[0]
      if (!isTag(n)) return undefined
      var style = (n.attribs || {}).style || ''
      var m = new RegExp('(?:^|;)\\s*' + prop + '\\s*:\\s*([^;]*)').exec(style)
      return m ? m[1].trim() : undefined
    }
    for (var i = 0; i < this.nodes.length; i++) {
      var el = this.nodes[i]
      if (!isTag(el)) continue
      el.attribs = el.attribs || {}
      var parts = (el.attribs.style || '').split(';').filter(function (x) { return x.trim() })
      var replaced = false
      for (var j = 0; j < parts.length; j++) {
        if (new RegExp('^\\s*' + prop + '\\s*:').test(parts[j])) {
          parts[j] = prop + ': ' + value
          replaced = true
        }
      }
      if (!replaced) parts.push(prop + ': ' + value)
      el.attribs.style = parts.join('; ')
    }
    return this
  }

  Cheerio.prototype.clone = function () {
    var out = []
    for (var i = 0; i < this.nodes.length; i++) out.push(cloneNode(this.nodes[i]))
    return this._wrap(out)
  }

  Cheerio.prototype.empty = function () {
    for (var i = 0; i < this.nodes.length; i++) {
      if (isTag(this.nodes[i])) this.nodes[i].children = []
    }
    return this
  }

  Cheerio.prototype.remove = function (sel) {
    var targets = sel ? this.filter(sel).nodes : this.nodes
    for (var i = 0; i < targets.length; i++) {
      var n = targets[i]
      var p = n.parent
      if (!p) continue
      var idx = p.children.indexOf(n)
      if (idx >= 0) p.children.splice(idx, 1)
      n.parent = null
    }
    return this
  }

  function toNodes(content, parent) {
    var out = []
    var i
    if (content === null || content === undefined) return out
    if (typeof content === 'string') {
      var frag = parseHTML(content)
      for (i = 0; i < frag.children.length; i++) {
        var c = frag.children[i]
        c.parent = parent
        out.push(c)
      }
      return out
    }
    if (content instanceof Cheerio) {
      for (i = 0; i < content.nodes.length; i++) {
        if (!isTag(content.nodes[i])) continue
        var cc = cloneNode(content.nodes[i])
        cc.parent = parent
        out.push(cc)
      }
      return out
    }
    if (isTag(content)) {
      var cn = cloneNode(content)
      cn.parent = parent
      out.push(cn)
      return out
    }
    if (Array.isArray(content)) {
      for (i = 0; i < content.length; i++) {
        var sub = toNodes(content[i], parent)
        for (var j = 0; j < sub.length; j++) out.push(sub[j])
      }
    }
    return out
  }

  Cheerio.prototype.append = function (content) {
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      if (!isTag(n)) continue
      var nodes = toNodes(content, n)
      for (var j = 0; j < nodes.length; j++) n.children.push(nodes[j])
    }
    return this
  }

  Cheerio.prototype.prepend = function (content) {
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      if (!isTag(n)) continue
      var nodes = toNodes(content, n)
      for (var j = nodes.length - 1; j >= 0; j--) n.children.unshift(nodes[j])
    }
    return this
  }

  Cheerio.prototype.before = function (content) {
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      var p = n.parent
      if (!p) continue
      var nodes = toNodes(content, p)
      var idx = p.children.indexOf(n)
      for (var j = 0; j < nodes.length; j++) p.children.splice(idx + j, 0, nodes[j])
    }
    return this
  }

  Cheerio.prototype.after = function (content) {
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      var p = n.parent
      if (!p) continue
      var nodes = toNodes(content, p)
      var idx = p.children.indexOf(n)
      for (var j = 0; j < nodes.length; j++) p.children.splice(idx + 1 + j, 0, nodes[j])
    }
    return this
  }

  Cheerio.prototype.replaceWith = function (content) {
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      var p = n.parent
      if (!p) continue
      var nodes = toNodes(content, p)
      var idx = p.children.indexOf(n)
      p.children.splice(idx, 1)
      for (var j = 0; j < nodes.length; j++) p.children.splice(idx + j, 0, nodes[j])
      n.parent = null
    }
    return this
  }

  Cheerio.prototype.wrap = function (content) {
    for (var i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i]
      var p = n.parent
      if (!p) continue
      var wrappers = toNodes(content, p)
      if (!wrappers.length) continue
      var wrapper = wrappers[0]
      var idx = p.children.indexOf(n)
      p.children[idx] = wrapper
      wrapper.parent = p
      // 找到 wrapper 最深的第一个元素，把 n 塞进去
      var deep = wrapper
      while (deep.children.length && isTag(deep.children[0])) deep = deep.children[0]
      deep.children.push(n)
      n.parent = deep
    }
    return this
  }

  Cheerio.prototype.end = function () { return this }

  /* ============================================================
   * 6. 入口
   * ============================================================ */
  function nodeListFrom(input) {
    var out = []
    if (input === null || input === undefined) return out
    if (Array.isArray(input)) {
      for (var i = 0; i < input.length; i++) {
        var x = input[i]
        if (isTag(x) || isRoot(x)) out.push(x)
        else if (x instanceof Cheerio) {
          for (var j = 0; j < x.nodes.length; j++) out.push(x.nodes[j])
        }
      }
      return out
    }
    if (isTag(input) || isRoot(input)) return [input]
    if (input instanceof Cheerio) return input.nodes.slice()
    return out
  }

  function unwrapContext(ctx) {
    if (ctx === null || ctx === undefined) return null
    if (isTag(ctx) || isRoot(ctx)) return ctx
    if (ctx instanceof Cheerio) return ctx.nodes.length ? ctx.nodes[0] : null
    if (Array.isArray(ctx)) return ctx.length ? unwrapContext(ctx[0]) : null
    return null
  }

  function load(html, options) {
    var opts = options || {}
    var root = parseHTML(html, opts)

    var $ = function (selector, context) {
      if (typeof selector === 'string' && selector.charAt(0) === '<') {
        // $(html) -> 解析为节点集合
        return new Cheerio(parseHTML(selector, opts).children.slice(), root, opts)
      }
      if (typeof selector === 'string') {
        var ctx = context === undefined || context === null ? root : unwrapContext(context)
        if (!ctx) return new Cheerio([], root, opts)
        return new Cheerio(selectWithin(ctx, selector, root), root, opts)
      }
      return new Cheerio(nodeListFrom(selector), root, opts)
    }

    $.root = function () { return new Cheerio([root], root, opts) }
    $.html = function (sel) {
      if (sel === undefined || sel === null) return innerHTML(root)
      if (typeof sel === 'string') return $(sel).toString()
      if (sel instanceof Cheerio) return sel.toString()
      return serialize(sel)
    }
    $.text = function (sel) {
      return sel === undefined || sel === null ? textOf(root) : $(sel).text()
    }
    $.xml = function (sel) { return $.html(sel) }
    $.parseHTML = function (str) { return parseHTML(str, opts).children.slice() }
    $.contains = function (a, b) { return isDescendant(b, a) }
    $.load = load
    $._root = root
    $.fn = Cheerio.prototype
    $.prototype = Cheerio.prototype
    return $
  }

  var cheerio = {
    load: load,
    parseHTML: function (str, options) { return parseHTML(str, options).children.slice() },
    html: function (selector, options) {
      if (selector === undefined) return ''
      var $ = load('', options)
      return $.html(selector)
    },
    text: function (selector, options) {
      if (selector === undefined) return ''
      return load('', options).text(selector)
    },
    root: function () { return load('').root() },
    contains: function (a, b) { return isDescendant(b, a) },
    decodeEntities: decodeEntities,
    Cheerio: Cheerio
  }
  cheerio.default = cheerio
  cheerio.__esModule = true

  return cheerio
})()
