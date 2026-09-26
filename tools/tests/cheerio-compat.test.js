/**
 * cheerio 兼容层回归测试（Node 运行，离线验证解析器 / 选择器 / API）
 *
 *   node tools/tests/cheerio-compat.test.js
 *
 * 覆盖：MusicFree 插件真实调用链（find/children/filter/map/first/attr/text/toArray）、
 *       HTML 容错（隐式闭合 / void / 原始文本元素）、实体解码、选择器（组合 / 属性 / 伪类）、
 *       DOM 读写、序列化。
 * 注意：源码里的实体一律用 \u0026 拼接，避免被工具层的实体转换吃掉。
 */
var SCRIPT = '/storage/emulated/0/AndroidIDEProjects/DPmusic/app/src/main/assets/script/musicfree-cheerio.js'

require(SCRIPT)
var cheerio = globalThis.__mf_cheerio__

var pass = 0
var fail = 0
function eq(actual, expected, name) {
  if (actual === expected) {
    pass++
    console.log('  \u2713 ' + name)
  } else {
    fail++
    console.log('  \u2717 ' + name)
    console.log('      期望: ' + JSON.stringify(expected))
    console.log('      实际: ' + JSON.stringify(actual))
  }
}
function section(t) { console.log('\n' + t) }

var AMP = String.fromCharCode(38)

// ---------------------------------------------------------------- 样例 HTML
var H = [
  '<html><head><title>榜单</title></head><body>',
  '<div id="main" class="rank_view">',
  '  <table><tbody>',
  '    <tr class="item" data-id="1001">',
  '      <td class="r_td_1"><a href="http://x/1001">晴天</a></td>',
  '      <td class="r_td_3"> 周杰伦 </td>',
  '      <td class="r_td_6"><a href="http://x/1001">播放</a></td>',
  '    </tr>',
  '    <tr class="item" data-id="1002">',
  '      <td class="r_td_1"><a href="http://x/1002">七里香</a></td>',
  '      <td class="r_td_3">林俊杰</td>',
  '      <td class="r_td_6"><a href="http://x/1002">播放</a></td>',
  '    </tr>',
  '  </tbody></table>',
  '</div>',
  '<p id="ent">A ' + AMP + 'amp; B ' + AMP + 'lt; C</p>',
  '<p>第二段<img src="a.jpg">',
  '<ul><li>一<li>二<li>三</ul>',
  '<script>var a = 1 < 2</script>',
  '</body></html>'
].join('\n')

var $ = cheerio.load(H)

// ---------------------------------------------------------------- A. 基本
section('A. 解析与基本选择')
eq($('div.rank_view tbody').length, 1, '后代选择器 div.rank_view tbody')
eq($('td.r_td_1 a').length, 2, 'tag+class 后代 td.r_td_1 a')
eq($('a').length, 4, '全 tag a（两行 × 2 个）')
eq($('div#main').length, 1, '#id')
eq($('title').text(), '榜单', 'head 里的 title')

// ---------------------------------------------------------------- B. 插件真实链
section('B. 插件真实调用链（酷狗榜单场景）')
var rows = $('div.rank_view tbody').children('tr')
eq(rows.length, 2, 'children("tr")')
var list = rows.map(function (i, el) {
  var $el = $(el)
  return {
    name: $el.find('td.r_td_1 a').text(),
    artist: $el.find('td.r_td_3').text().trim(),
    href: $el.find('td.r_td_6').children().first().attr('href')
  }
}).get()
eq(list.length, 2, 'map().get() 长度')
eq(list[0].name, '晴天', '取歌名')
eq(list[0].artist, '周杰伦', '取歌手（.text().trim()）')
eq(list[1].name, '七里香', '第二首歌名')
eq(list[1].href, 'http://x/1002', 'children().first().attr("href")')
eq($('td.r_td_6').children().first().attr('href'), 'http://x/1001', 'children() 无参 + first()')
eq($('a').attr('href'), 'http://x/1001', 'attr() 返回第一个匹配')
eq(rows.toArray().length, 2, 'toArray()')
eq($('td.r_td_3').first().text().trim(), '周杰伦', 'td.r_td_3 文本（first）')
eq($('td.r_td_3').text(), ' 周杰伦 林俊杰', '.text() 合并全部匹配（cheerio 语义）')
eq($('tr').filter('.item').length, 2, 'filter(选择器)')
eq(rows.filter(function (i, el) { return $(el).hasClass('item') }).length, 2, 'filter(函数)')
eq(rows.eq(1).find('td.r_td_3').text(), '林俊杰', 'eq(1)')
eq(rows.last().find('td.r_td_1 a').text(), '七里香', 'last()')
eq(rows.first().find('td.r_td_1 a').text(), '晴天', 'first()')

// ---------------------------------------------------------------- C. 容错
section('C. HTML 容错')
eq($('p').length, 2, '未闭合 <p> 隐式闭合')
eq($('li').length, 3, '未闭合 <li> 隐式闭合')
eq($('img').length, 1, 'void 元素 img')
eq($('script').text().trim(), 'var a = 1 < 2', 'script 内 < 不当标签')
eq($('script').length, 1, 'script 元素存在')
eq($('tbody tr').length, 2, 'tbody > tr')

// ---------------------------------------------------------------- D. 实体
section('D. 实体解码')
eq($('#ent').text(), 'A ' + AMP + ' B < C', '元素内实体解码')
eq(cheerio.load('x ' + AMP + 'amp; y').text(), 'x ' + AMP + ' y', '纯文本实体（cheerio.load(文本).text()）')
eq(cheerio.load('<i title="a' + AMP + 'quot;b">t</i>')('i').attr('title'), 'a"b', '属性内实体解码')

// ---------------------------------------------------------------- E. 选择器
section('E. 选择器')
eq($('td:first-child').length, 2, ':first-child')
eq($('tr:nth-child(2) td.r_td_3').text(), '林俊杰', ':nth-child(2)')
eq($('td:not(.r_td_3)').length, 4, ':not(.r_td_3)')
eq($('td:contains(晴天)').length, 1, ':contains()')
eq($('[data-id="1002"]').length, 1, '[attr="v"]')
eq($('[href^="http://x/100"]').length, 4, '[attr^="v"]')
eq($('[class~="item"]').length, 2, '[attr~="v"]')
eq($('tr + tr').length, 1, '相邻兄弟 +')
eq($('td.r_td_1 > a').length, 2, '子代 >')
eq($('div.rank_view, ul').length, 2, '分组 ,')
eq($('*').length > 10, true, '通配 *')
eq($('tr:has(a)').length, 2, ':has()')

// ---------------------------------------------------------------- F. DOM 读写
section('F. DOM 读写与序列化')
var $2 = cheerio.load('<div id="a"><span>x</span></div>')
$2('#a').append('<b>y</b>')
eq($2('#a b').length, 1, 'append(html)')
$2('#a').find('span').text('z')
eq($2('#a').text(), 'zy', 'text(值) 设置')
$2('#a').attr('data-k', 'v')
eq($2('#a').attr('data-k'), 'v', 'attr 设置')
eq($2('#a').html(), '<span>z</span><b>y</b>', 'html() 读取')
eq($('<div class="n">hi</div>').text(), 'hi', '$(html) 创建元素')
eq($2('#a').data('k'), 'v', 'data() 读取')
eq(cheerio.load('<br>').html(), '<br>', 'void 元素序列化')
eq(cheerio.load('<p>a')('p').outerHTML(), '<p>a</p>', 'outerHTML 补齐闭合')
eq($('div.rank_view').find('tr').length, 2, 'find 在子树上')
eq($('div.rank_view').find('tr').parent().length, 1, 'parent() 去重')
eq($2('#a').clone().text(), 'zy', 'clone()')
$2('#a b').remove()
eq($2('#a b').length, 0, 'remove()')

// ---------------------------------------------------------------- 结果
console.log('\n========================================')
console.log('通过 ' + pass + ' / 失败 ' + fail)
console.log('========================================')
process.exit(fail === 0 ? 0 : 1)