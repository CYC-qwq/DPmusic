'use strict'

/**
 * MusicFree 插件运行时适配层（QuickJS 内执行）。
 *
 * 目标：让 MusicFree 生态的 .js 插件无需改动即可在本应用运行。
 *
 * 与 MusicFree 宿主对齐的部分：
 * - 插件以 `module.exports = { platform, version, author, search, getMediaSource, ... }` 导出；
 * - 挂载方式与 MusicFree 一致：把插件源码作为函数体，
 *   以 (require, __musicfree_require, module, exports, console, env, URL, process) 为形参执行；
 * - 提供 require 子集：axios / qs / he / crypto-js / dayjs / cheerio
 *   （cheerio 由同目录 musicfree-cheerio.js 实现，宿主在求值本文件前注入到 globalThis.__mf_cheerio__）；
 * - 网络请求全部经宿主 OkHttp 代理（__mf_native_call__('request', ...)）。
 *
 * 宿主 → 插件调用协议：
 *   Kotlin 调用 globalThis.mf_call(key, requestKey, method, argsJson)
 *   插件返回后 → __mf_native_call__(key, 'response', {requestKey, ok, result|error})
 */
globalThis.mf_setup = (key, pluginSource, userVariablesJson, appVersion) => {
  delete globalThis.mf_setup

  const _nativeCall = globalThis.__mf_native_call__
  delete globalThis.__mf_native_call__

  // ---------------- 宿主原生能力 ----------------
  const nativeFuncs = {}
  const nativeNames = ['set_timeout', 'str2b64', 'b642buf', 'md5', 'sha1', 'sha256', 'aes_encrypt']
  for (const name of nativeNames) {
    const holder = '__mf_native_call__' + name
    const fn = globalThis[holder]
    if (typeof fn === 'function') {
      delete globalThis[holder]
      nativeFuncs[name] = fn
    }
  }
  const callNative = (action, data) => {
    try {
      _nativeCall(key, action, typeof data === 'string' ? data : JSON.stringify(data))
    } catch (e) {
      // 宿主已销毁：忽略
    }
  }

  const userVariables = (() => {
    try {
      return JSON.parse(userVariablesJson || '{}') || {}
    } catch (e) {
      return {}
    }
  })()

  // ---------------- 定时器 ----------------
  const timerCallbacks = new Map()
  let timerId = 0
  globalThis.setTimeout = (callback, timeout, ...params) => {
    if (typeof callback !== 'function') throw new Error('callback required a function')
    const id = timerId++
    timerCallbacks.set(id, { callback, params })
    if (nativeFuncs.set_timeout) nativeFuncs.set_timeout(id, Math.max(0, parseInt(timeout) || 0))
    return id
  }
  globalThis.clearTimeout = (id) => {
    timerCallbacks.delete(id)
  }
  const fireTimer = (id) => {
    const target = timerCallbacks.get(id)
    if (!target) return
    timerCallbacks.delete(id)
    try {
      target.callback(...target.params)
    } catch (e) {
      logToHost('error', 'setTimeout 回调异常：' + errText(e))
    }
  }

  // ---------------- 文本 / 编码工具 ----------------
  function bytesToString(bytes) {
    let result = ''
    let i = 0
    while (i < bytes.length) {
      const b = bytes[i]
      if (b < 128) {
        result += String.fromCharCode(b)
        i++
      } else if (b >= 192 && b < 224) {
        result += String.fromCharCode(((b & 31) << 6) | (bytes[i + 1] & 63))
        i += 2
      } else {
        result += String.fromCharCode(((b & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63))
        i += 3
      }
    }
    return result
  }
  function stringToBytes(input) {
    const bytes = []
    for (let i = 0; i < input.length; i++) {
      const c = input.charCodeAt(i)
      if (c < 128) bytes.push(c)
      else if (c < 2048) bytes.push((c >> 6) | 192, (c & 63) | 128)
      else bytes.push((c >> 12) | 224, ((c >> 6) & 63) | 128, (c & 63) | 128)
    }
    return bytes
  }
  const errText = (e) => (e && e.message ? e.message : String(e))
  const logToHost = (level, message) => callNative('log', { level, message: String(message).slice(0, 800) })
  const toB64 = (data) => {
    if (typeof data === 'string') return nativeFuncs.str2b64(data)
    if (Array.isArray(data) || ArrayBuffer.isView(data)) {
      return nativeFuncs.str2b64(bytesToString(Array.prototype.slice.call(data)))
    }
    throw new Error('不支持的输入类型：' + typeof data)
  }
  const hexOf = (algo, text) => {
    const fn = nativeFuncs[algo]
    if (!fn) throw new Error('宿主不支持算法：' + algo)
    return fn(typeof text === 'string' ? text : bytesToString(Array.prototype.slice.call(text)))
  }

  // ---------------- qs ----------------
  const qs = {
    stringify(obj, options) {
      if (obj == null) return ''
      const parts = []
      const encode = (v) => encodeURIComponent(v == null ? '' : v)
      for (const k in obj) {
        if (!Object.prototype.hasOwnProperty.call(obj, k)) continue
        const v = obj[k]
        if (v === undefined) continue
        if (Array.isArray(v)) v.forEach((item) => parts.push(encode(k) + '=' + encode(item)))
        else parts.push(encode(k) + '=' + encode(v))
      }
      return parts.join('&')
    },
    parse(str) {
      const out = {}
      if (!str) return out
      String(str)
        .replace(/^\?/, '')
        .split('&')
        .forEach((pair) => {
          if (!pair) return
          const idx = pair.indexOf('=')
          const k = idx < 0 ? pair : pair.slice(0, idx)
          const v = idx < 0 ? '' : pair.slice(idx + 1)
          const key = decodeURIComponent(k)
          const value = decodeURIComponent(v.replace(/\+/g, ' '))
          if (out[key] === undefined) out[key] = value
          else if (Array.isArray(out[key])) out[key].push(value)
          else out[key] = [out[key], value]
        })
      return out
    },
  }

  // ---------------- he ----------------
  const he = {
    decode(text) {
      return String(text == null ? '' : text)
        .replace(/&#(\d+);/g, (m, d) => String.fromCharCode(parseInt(d, 10)))
        .replace(/&#[xX]([0-9a-fA-F]+);/g, (m, h) => String.fromCharCode(parseInt(h, 16)))
        .replace(/"/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/'/g, "'")
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&nbsp;/g, ' ')
        .replace(/&amp;/g, '&')
    },
    encode(text) {
      return String(text == null ? '' : text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '"')
        .replace(/'/g, '&#39;')
    },
  }

  // ---------------- crypto-js ----------------
  const hashResult = (hex) => ({
    toString: () => hex,
    __hex: hex,
  })
  const CryptoJS = {
    MD5: (text) => hashResult(hexOf('md5', text)),
    SHA1: (text) => hashResult(hexOf('sha1', text)),
    SHA256: (text) => hashResult(hexOf('sha256', text)),
    HmacSHA256: (text, keyText) => hashResult(hexOf('sha256', String(keyText) + String(text))),
    enc: {
      Utf8: {
        parse: (text) => ({ __text: String(text) }),
        stringify: (word) => (word && word.__text != null ? word.__text : String(word)),
      },
      Base64: {
        parse: (text) => ({ __bytes: JSON.parse(nativeFuncs.b642buf(String(text))) }),
        stringify: (word) => {
          if (word && word.__bytes) return nativeFuncs.str2b64(bytesToString(word.__bytes))
          if (word && word.__text != null) return nativeFuncs.str2b64(word.__text)
          return nativeFuncs.str2b64(String(word))
        },
      },
      Hex: {
        parse: (text) => ({ __hex: String(text) }),
        stringify: (word) => (word && word.__hex ? word.__hex : String(word)),
      },
    },
    AES: {
      encrypt(text, key, options) {
        const iv = (options && options.iv && options.iv.__text) || (options && options.iv && options.iv.__hex) || ''
        const cipher = nativeFuncs.aes_encrypt(
          toB64(typeof text === 'string' ? text : text.__text || ''),
          toB64(key && key.__text != null ? key.__text : key),
          iv ? toB64(iv) : '',
          'AES/CBC/PKCS7Padding'
        )
        return { toString: () => cipher, __b64: cipher }
      },
    },
  }

  // ---------------- 网络（宿主代理） ----------------
  let requestSeq = 0
  const pendingRequests = new Map()
  const sendRequest = (url, options) =>
    new Promise((resolve, reject) => {
      const requestKey = 'mf' + requestSeq++
      pendingRequests.set(requestKey, { resolve, reject })
      callNative('request', { requestKey, url, options: options || {} })
    })
  const handleNativeResponse = (payload) => {
    const target = pendingRequests.get(payload.requestKey)
    if (!target) return
    pendingRequests.delete(payload.requestKey)
    if (payload.error == null) target.resolve(payload.response)
    else target.reject(new Error(payload.error))
  }

  const axiosInstance = (config) => {
    const cfg = config || {}
    const method = String(cfg.method || 'get').toLowerCase()
    let url = cfg.url || ''
    const headers = Object.assign({}, cfg.headers || {})
    if (cfg.params) url += (url.indexOf('?') >= 0 ? '&' : '?') + qs.stringify(cfg.params)
    let body = cfg.data
    if (body != null && typeof body === 'object' && !Array.isArray(body) && !ArrayBuffer.isView(body)) {
      const ctKey = Object.keys(headers).find((k) => k.toLowerCase() === 'content-type')
      const ct = ctKey ? headers[ctKey] : ''
      if (ct.indexOf('x-www-form-urlencoded') >= 0) body = qs.stringify(body)
      else {
        body = JSON.stringify(body)
        if (!ctKey) headers['Content-Type'] = 'application/json'
      }
    }
    return sendRequest(url, {
      method,
      headers,
      body: body == null ? null : body,
      timeout: cfg.timeout,
      binary: cfg.responseType === 'arraybuffer',
    }).then((resp) => {
      const result = {
        data: resp.body,
        status: resp.statusCode,
        statusText: resp.statusMessage,
        headers: resp.headers,
        config: cfg,
        request: {},
      }
      if (result.status < 200 || result.status >= 300) {
        const error = new Error('Request failed with status code ' + result.status)
        error.response = result
        error.isAxiosError = true
        throw error
      }
      return result
    })
  }
  const makeAxios = (defaults) => {
    const instance = (config) => axiosInstance(Object.assign({}, defaults, config))
    instance.request = (config) => axiosInstance(Object.assign({}, defaults, config))
    instance.get = (url, config) => axiosInstance(Object.assign({}, defaults, config, { url, method: 'get' }))
    instance.delete = (url, config) => axiosInstance(Object.assign({}, defaults, config, { url, method: 'delete' }))
    instance.head = (url, config) => axiosInstance(Object.assign({}, defaults, config, { url, method: 'head' }))
    instance.post = (url, data, config) =>
      axiosInstance(Object.assign({}, defaults, config, { url, data, method: 'post' }))
    instance.put = (url, data, config) =>
      axiosInstance(Object.assign({}, defaults, config, { url, data, method: 'put' }))
    instance.patch = (url, data, config) =>
      axiosInstance(Object.assign({}, defaults, config, { url, data, method: 'patch' }))
    instance.create = (config) => makeAxios(Object.assign({}, defaults, config))
    instance.defaults = { headers: { common: {} } }
    instance.interceptors = {
      request: { use: () => 0, eject: () => {} },
      response: { use: () => 0, eject: () => {} },
    }
    instance.isAxiosError = (e) => !!(e && e.isAxiosError)
    instance.CancelToken = { source: () => ({ token: {}, cancel: () => {} }) }
    instance.all = (list) => Promise.all(list)
    return instance
  }
  const axios = makeAxios({})

  // ---------------- URL / dayjs ----------------
  function URLPolyfill(href) {
    const text = String(href)
    const qIndex = text.indexOf('?')
    this.href = text
    this.origin = text.replace(/^(https?:\/\/[^/?#]+).*$/, '$1')
    this.search = qIndex < 0 ? '' : text.slice(qIndex).split('#')[0]
    const sp = qs.parse(this.search)
    this.searchParams = {
      get: (k) => (sp[k] === undefined ? null : sp[k]),
      has: (k) => sp[k] !== undefined,
      toString: () => qs.stringify(sp),
      toJSON: () => sp,
      forEach: (fn) => Object.keys(sp).forEach((k) => fn(sp[k], k)),
    }
  }
  const dayjs = (input) => {
    const date = input ? new Date(input) : new Date()
    const pad = (n, len) => String(n).padStart(len || 2, '0')
    return {
      format(pattern) {
        const p = pattern || 'YYYY-MM-DD HH:mm:ss'
        return p
          .replace(/YYYY/g, String(date.getFullYear()))
          .replace(/MM/g, pad(date.getMonth() + 1))
          .replace(/DD/g, pad(date.getDate()))
          .replace(/HH/g, pad(date.getHours()))
          .replace(/mm/g, pad(date.getMinutes()))
          .replace(/ss/g, pad(date.getSeconds()))
      },
      valueOf: () => date.getTime(),
      unix: () => Math.floor(date.getTime() / 1000),
      toDate: () => date,
    }
  }

  // ---------------- require 子集 ----------------
  const modules = {
    axios,
    qs,
    he,
    'crypto-js': CryptoJS,
    crypto: CryptoJS,
    dayjs,
  }
  // cheerio：宿主在求值本文件前注入的纯 JS 兼容层（assets/script/musicfree-cheerio.js）
  const cheerioLib = globalThis.__mf_cheerio__
  if (cheerioLib) modules.cheerio = cheerioLib

  // 常见 npm 名 → 已实现模块的别名（插件生态里这几种写法等价）
  const moduleAlias = {
    'node-fetch': 'axios',
    request: 'axios',
    'request-promise': 'axios',
    'request-promise-native': 'axios',
    superagent: 'axios',
  }

  const requireShim = (name) => {
    const key = String(name)
    if (Object.prototype.hasOwnProperty.call(modules, key)) return modules[key]
    const alias = moduleAlias[key]
    if (alias && Object.prototype.hasOwnProperty.call(modules, alias)) return modules[alias]
    throw new Error(
      '插件依赖了当前运行时不支持的模块：' + key +
        '（当前已支持：' + Object.keys(modules).join(' / ') + '）'
    )
  }

  // ---------------- 挂载插件 ----------------
  const env = {
    getUserVariables: () => Object.assign({}, userVariables),
    get userVariables() {
      return Object.assign({}, userVariables)
    },
    appVersion: appVersion || '',
    os: 'android',
    lang: 'zh-CN',
  }
  const processShim = { platform: 'android', version: appVersion || '', env }
  const consoleShim = {
    log: (...args) => logToHost('debug', args.join(' ')),
    info: (...args) => logToHost('info', args.join(' ')),
    warn: (...args) => logToHost('warn', args.join(' ')),
    error: (...args) => logToHost('error', args.join(' ')),
    debug: (...args) => logToHost('debug', args.join(' ')),
  }

  let instance = null
  let setupError = null
  try {
    const moduleObj = { exports: {} }
    const factory = Function(
      "'use strict';\n" +
        'return function(require, __musicfree_require, module, exports, console, env, URL, process) {\n' +
        String(pluginSource) +
        '\n}'
    )()
    factory(requireShim, requireShim, moduleObj, moduleObj.exports, consoleShim, env, URLPolyfill, processShim)
    instance = moduleObj.exports && moduleObj.exports.default ? moduleObj.exports.default : moduleObj.exports
  } catch (e) {
    setupError = errText(e)
  }

  if (setupError || !instance || typeof instance !== 'object') {
    callNative('init', {
      status: false,
      errorMessage: setupError || '插件未导出有效对象（需要 module.exports = {...}）',
    })
    return
  }

  const methods = Object.keys(instance).filter((k) => typeof instance[k] === 'function')
  callNative('init', {
    status: true,
    meta: {
      platform: instance.platform || '',
      version: instance.version || '',
      author: instance.author || '',
      description: instance.description || '',
      supportedSearchType: instance.supportedSearchType || [],
      userVariables: Array.isArray(instance.userVariables) ? instance.userVariables : [],
      methods,
    },
  })

  // ---------------- 宿主 → 插件 调用入口 ----------------
  const pendingCalls = new Map()
  globalThis.mf_call = (callKey, requestKey, method, argsJson) => {
    if (callKey !== key) return 'invalid key'
    let args = []
    try {
      args = JSON.parse(argsJson || '[]')
    } catch (e) {
      args = []
    }
    Promise.resolve()
      .then(() => {
        if (method === '__ping__') return 'pong'
        const fn = instance[method]
        if (typeof fn !== 'function') throw new Error('插件未实现该方法：' + method)
        return fn.apply(instance, args)
      })
      .then((result) => {
        callNative('response', {
          requestKey,
          ok: true,
          result: result === undefined ? null : result,
        })
      })
      .catch((e) => {
        callNative('response', { requestKey, ok: false, error: errText(e) })
      })
    return null
  }

  globalThis.mf_native = (callKey, action, dataJson) => {
    if (callKey !== key) return 'invalid key'
    try {
      const data = dataJson == null ? null : JSON.parse(dataJson)
      if (action === 'response') {
        handleNativeResponse(data)
        return null
      }
      if (action === '__set_timeout__') {
        fireTimer(data)
        return null
      }
    } catch (e) {
      logToHost('error', '宿主回调处理失败：' + errText(e))
    }
    return null
  }

  void pendingCalls
}
