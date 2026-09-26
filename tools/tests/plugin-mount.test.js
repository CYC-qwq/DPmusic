/**
 * MusicFree 插件挂载端到端验证（Node 运行，不需要真机）
 *
 *   node tools/tests/plugin-mount.test.js [插件源码路径]
 *
 * 做法：mock 宿主的 __mf_native_call__ 与原生能力，然后按真机顺序求值
 *       musicfree-cheerio.js → musicfree-preload.js → mf_setup(插件源码)，
 *       最后检查插件是否成功上报元信息（status=true）。
 * 这能在装机前就回答「插件需要的模块是否齐备、适配层能否挂载」。
 */
var fs = require('fs')
var path = require('path')
var crypto = require('crypto')

var ROOT = '/storage/emulated/0/AndroidIDEProjects/DPmusic'
var PLUGIN = process.argv[2] || '/sdcard/Download/plugin.js'

var calls = []
var logs = []

globalThis.__mf_native_call__ = function (key, action, dataJson) {
  calls.push({ key: key, action: action, data: dataJson })
  if (action === 'log') {
    try { logs.push(JSON.parse(dataJson)) } catch (e) { /* ignore */ }
  }
}
// preload 会探测这些原生能力是否存在（缺了就退化，不影响挂载）
globalThis.__mf_native_call__set_timeout = function (id, ms) { /* no-op */ }
globalThis.__mf_native_call__str2b64 = function (s) {
  return Buffer.from(String(s), 'binary').toString('base64')
}
globalThis.__mf_native_call__b642buf = function (s) {
  return Array.from(Buffer.from(String(s), 'base64'))
}
globalThis.__mf_native_call__md5 = function (s) {
  return crypto.createHash('md5').update(String(s), 'utf8').digest('hex')
}
globalThis.__mf_native_call__sha1 = function (s) {
  return crypto.createHash('sha1').update(String(s), 'utf8').digest('hex')
}
globalThis.__mf_native_call__sha256 = function (s) {
  return crypto.createHash('sha256').update(String(s), 'utf8').digest('hex')
}
globalThis.__mf_native_call__aes_encrypt = function (s) { return '' }

// ---- 按真机顺序求值 ----
require(path.join(ROOT, 'app/src/main/assets/script/musicfree-cheerio.js'))
require(path.join(ROOT, 'app/src/main/assets/script/musicfree-preload.js'))

console.log('cheerio 注入: ' + (globalThis.__mf_cheerio__ ? 'OK' : '缺失'))
console.log('mf_setup 存在: ' + (typeof globalThis.mf_setup === 'function'))

var src = fs.readFileSync(PLUGIN, 'utf8')
console.log('插件源码: ' + PLUGIN + '（' + src.length + ' 字节）\n')

var t0 = Date.now()
globalThis.mf_setup('test-key', src, '{}', '1.0.0')
var cost = Date.now() - t0

var init = calls.filter(function (c) { return c.action === 'init' })[0]
if (!init) {
  console.log('\u2717 插件未上报元信息（挂载失败）')
  logs.forEach(function (l) { console.log('  [' + l.level + '] ' + l.message) })
  process.exit(1)
}

var payload = JSON.parse(init.data)
console.log('\n----------------------------------------')
console.log('挂载结果 : ' + (payload.status ? '\u2713 成功' : '\u2717 失败'))
console.log('耗时     : ' + cost + ' ms')
if (payload.errorMessage) console.log('错误     : ' + payload.errorMessage)
if (payload.meta) {
  var m = payload.meta
  console.log('平台     : ' + m.platform)
  console.log('版本     : ' + m.version)
  console.log('作者     : ' + m.author)
  console.log('支持搜索 : ' + JSON.stringify(m.supportedSearchType))
  console.log('方法     : ' + (m.methods || []).join(', '))
}
if (logs.length) {
  console.log('\n插件日志:')
  logs.forEach(function (l) { console.log('  [' + l.level + '] ' + l.message) })
}
console.log('----------------------------------------')
process.exit(payload.status ? 0 : 1)