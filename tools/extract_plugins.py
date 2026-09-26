#!/usr/bin/env python3
"""从设备 DataStore 的 preferences_pb 中提取 MusicFree 插件源码（离线分析用）

用法：
    # 1) 先把 DataStore 导成 base64（需要设备已连接 adb / Shizuku）
    run-as com.dpmusic.app sh -c 'base64 files/datastore/dpmusic_store.preferences_pb' > ds_b64.txt
    # 2) 提取插件
    python3 tools/extract_plugins.py ds_b64.txt ./plugins

    # 3) 逐个验证能否挂载
    for f in ./plugins/*.js; do node tools/tests/plugin-mount.test.js "$f"; done

⚠️ ds_b64.txt 含全部应用设置（可能有 API Key），分析完立即删除。
"""
import base64
import io
import os
import sys


def read_varint(buf, i):
    result = 0
    shift = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, i


def parse_fields(buf, start, end):
    """解析 protobuf 字段序列：返回 [(field, wire, value)]"""
    out = []
    i = start
    while i < end:
        tag = buf[i]
        i += 1
        field = tag >> 3
        wire = tag & 7
        if wire == 0:
            v, i = read_varint(buf, i)
            out.append((field, wire, v))
        elif wire == 2:
            ln, i = read_varint(buf, i)
            out.append((field, wire, buf[i:i + ln]))
            i += ln
        elif wire == 5:
            out.append((field, wire, buf[i:i + 4]))
            i += 4
        elif wire == 1:
            out.append((field, wire, buf[i:i + 8]))
            i += 8
        else:
            raise ValueError('unknown wire type %d at offset %d' % (wire, i))
    return out


def load_prefs(path):
    """Preferences protobuf -> {key: bytes|int|bool|float}"""
    raw = base64.b64decode(io.open(path, 'rb').read())
    prefs = {}
    for field, wire, value in parse_fields(raw, 0, len(raw)):
        if field != 1 or wire != 2:
            continue
        key = None
        val = None
        for f2, w2, v2 in parse_fields(value, 0, len(value)):
            if f2 == 1 and w2 == 2:
                key = v2.decode('utf-8', 'replace')
            elif f2 == 2 and w2 == 2:
                for f3, w3, v3 in parse_fields(v2, 0, len(v2)):
                    if f3 == 5 and w3 == 2:      # Value.string
                        val = v3
                    elif f3 == 1 and w3 == 0:    # Value.boolean
                        val = bool(v3)
                    elif f3 == 4 and w3 == 0:    # Value.long
                        val = v3
        if key is not None:
            prefs[key] = val
    return prefs


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    src = sys.argv[1]
    outdir = sys.argv[2] if len(sys.argv) > 2 else './plugins'
    os.makedirs(outdir, exist_ok=True)

    prefs = load_prefs(src)
    print('prefs keys: %d' % len(prefs))

    n = 0
    for k, v in sorted(prefs.items()):
        if not isinstance(v, bytes) or b'module.exports' not in v:
            continue
        name = k.replace('mf_plugin_source_', '').replace('/', '_') + '.js'
        io.open(os.path.join(outdir, name), 'wb').write(v)
        n += 1
        print('  %-52s %7d bytes' % (name, len(v)))

    print('---')
    print('导出插件数：%d -> %s' % (n, outdir))
    return 0


if __name__ == '__main__':
    sys.exit(main())