# MusicFree 插件运行时：模块支持与扩展指南

> 相关文件
> - `core/script/MusicFreeEngine.kt` —— QuickJS 宿主，负责注入适配层、桥接宿主能力
> - `assets/script/musicfree-preload.js` —— 适配层（插件协议 / require 表 / 网络与加密桥）
> - `assets/script/musicfree-cheerio.js` —— cheerio 兼容层（纯 JS，1450 行）
> - `tools/tests/cheerio-compat.test.js` —— cheerio 回归测试（54 项）
> - `tools/tests/plugin-mount.test.js` —— 插件挂载端到端验证

## 1. 为什么需要"模块兼容层"

MusicFree 插件跑在 **Node（nodejs-mobile）** 上，可以直接 `require('cheerio')` 这类 npm 包。
DPmusic 的插件引擎是 **QuickJS**（`com.whl.quickjs`）——只有一个干净的 JS 引擎，**没有 Node 的模块系统、没有 npm 包**。

因此插件一 `require('cheerio')` 就会抛：

```
插件依赖了当前运行时不支持的模块：cheerio
```

→ 插件挂载失败，UI 显示「失败」。**这不是插件的 bug，是宿主缺少模块兼容层。**

## 2. 当前支持的 require 模块

| 模块 | 实现方式 | 说明 |
| --- | --- | --- |
| `axios` | 适配层实现 | 请求全部经宿主 OkHttp（`__mf_native_call__('request', ...)`） |
| `qs` | 适配层实现 | query string 解析 / 序列化 |
| `he` | 适配层实现 | HTML 实体编解码 |
| `crypto-js` / `crypto` | 适配层实现 | 宿主提供 md5 / sha1 / sha256 / aes_encrypt |
| `dayjs` | 适配层实现 | 常用格式化与计算子集 |
| **`cheerio`** | **`musicfree-cheerio.js`（纯 JS）** | **HTML 解析 + CSS 选择器 + DOM 读写** |

**别名**：`node-fetch` / `request` / `request-promise` / `request-promise-native` / `superagent` → `axios`。

未实现的模块会在报错里列出**已支持清单**，方便定位：

```
插件依赖了当前运行时不支持的模块：iconv-lite（当前已支持：axios / qs / he / crypto-js / crypto / dayjs / cheerio）
```

## 3. cheerio 兼容层覆盖范围

| 类别 | 支持 |
| --- | --- |
| 入口 | `cheerio.load(html[, {xmlMode, decodeEntities}])`、`$(selector/node/cheerioObj/array/html字符串)` |
| 遍历 | `find / children / contents / parent / parents / closest / siblings / next / nextAll / prev / prevAll / nextUntil / filter / not / is / has / eq / first / last / slice / each / map / toArray / get / index / add` |
| 读写 | `text / html / outerHTML / attr / removeAttr / prop / data / val / css / hasClass / addClass / removeClass / toggleClass / clone / remove / empty / append / prepend / before / after / replaceWith / wrap` |
| 静态 | `$.root / $.html / $.text / $.parseHTML / $.contains / $.decodeEntities` |
| 选择器 | `*` `tag` `#id` `.class` `[attr]` `[attr=v]` `^= $= *= ~= \|= !=`、组合（空格 `>` `+` `~`）、分组（`,`） |
| 伪类 | `:first-child` `:last-child` `:only-child` `:nth-child()` `:first-of-type` `:last-of-type` `:nth-of-type()` `:only-of-type` `:not()` `:is()` `:has()` `:contains()` `:empty` `:root` `:parent` `:checked` `:selected` `:disabled` `:enabled` `:required` `:header` `:input` `:hidden` `:visible` |
| 容错 | 未闭合 `<p>/<li>/<tr>/<td>/<option>` 隐式闭合、void 元素、自闭合、原始文本元素（`script`/`style`/`textarea`/`title`）、注释、多余闭合标签忽略、`<` 后非字母当文本、实体解码（命名 + 十进制 + 十六进制） |

节点结构对齐 **domhandler**（`{type:'tag'|'text'|'comment'|'root', name, attribs, children, parent}`），
所以插件里 `el.type === 'tag'`、`el.attribs` 这类直接访问也能工作。

## 4. 新增一个模块要改哪里

1. 在 `assets/script/musicfree-cheerio.js` 同级新增 `musicfree-<name>.js`，
   形如 `globalThis.__mf_<name>__ = (function () { ... return api })()`；
2. `MusicFreeEngine.doLoad` 里按顺序求值（**必须在 preload 之前**）：

```kotlin
readAsset(CHEERIO_ASSET)?.let { runCatching { ctx.evaluate(it) } }
```

3. `musicfree-preload.js` 的 `modules` 表里注册（用 `if (lib) modules.<name> = lib` 做可选注入）；
4. 写离线测试（见下）→ 构建 → 装机。

## 5. 离线测试（不用真机，秒级）

```bash
# cheerio 兼容层：54 项断言
node tools/tests/cheerio-compat.test.js

# 插件挂载端到端：mock 宿主后跑真实插件源码
node tools/tests/plugin-mount.test.js /path/to/plugin.js
```

`plugin-mount.test.js` 会按真机顺序求值 `cheerio.js → preload.js → mf_setup(插件源码)`，
然后检查插件是否成功上报元信息，并打印平台 / 版本 / 方法列表 —— **装机前就能判断模块是否齐备**。

从设备提取插件源码（插件原文存在 DataStore 里）：

```bash
run-as com.dpmusic.app sh -c 'base64 files/datastore/dpmusic_store.preferences_pb' | tee /sdcard/Download/ds_b64.txt
python3 tools/extract_plugins.py /sdcard/Download/ds_b64.txt /sdcard/Download/plugins
```

> ⚠️ 提取出的 `ds_b64.txt` 含**全部应用设置**（可能有 API Key 等），用完立即删除。

## 6. 踩过的坑

1. **工具层的实体转换**：往 JS 文件里写 `"` 会被当成 HTML 实体解码成 `"`（`&amp;` / `&lt;` / `&gt;` 不受影响）。
   → 涉及实体的字符串一律用 `String.fromCharCode(38) + 'quot;'` 拼接。
2. **选择器 `+` 被空格吃掉**：`tr + tr` 里 `+` 后面的空格会把组合器覆盖成「后代」。
   → 引入 `explicitComb` 标志：显式组合器只在解析完下一个 compound 后才清除。
3. **IIFE 忘赋值**：`;(function(){...})()` 不会挂到 `globalThis`。
   → 必须写成 `globalThis.__mf_xxx__ = (function () { ... return api })()`。
4. **测试期望 ≠ 实现错误**：`.text()` 合并**全部**匹配元素（cheerio 语义）、`cheerio.load()` 返回的函数没有 `.find`
   （要用 `$('sel').find()`）—— 写测试时先确认对齐的是 cheerio 行为，而不是自己的直觉。

## 7. 后续可补的模块（按需）

| 模块 | 用途 | 成本 |
| --- | --- | --- |
| `lodash` | 插件高频工具库 | 中（纯 JS 子集） |
| `iconv-lite` | GBK 页面解码（酷狗 / 5sing 等中文站） | 低（宿主暴露 `decode(bytes, charset)`） |
| `pako` | gzip / deflate | 低（宿主暴露 Inflater） |
| `big-integer` | 部分网易云加密插件 | 中（纯 JS） |
| `xml2js` / `node-html-parser` | 其它解析风格 | 中 |
