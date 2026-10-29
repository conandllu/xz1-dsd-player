#!/usr/bin/env python3
"""生成合成 DSF / DFF 文件，用于主机侧验证 dsd_native.cpp 的解析与读取。

用法: python3 gen_dsd_fixtures.py <输出目录>
会写出 dsf64.dsf / dff64.dff / dsf_meta.dsf，并打印期望值（JSON）。
"""
import json
import os
import struct
import sys

BLOCK = 4096
RATE = 2822400
FRAMES = 20000          # 每声道字节数 = 20000，跨越 4 个 4096 块


def bitrev(b: int) -> int:
    r = 0
    for i in range(8):
        r = (r << 1) | ((b >> i) & 1)
    return r


def patterns(n: int):
    """返回 (左声道字节, 右声道字节)：L 可预测，R 与之不同。"""
    left = bytes(((i * 7 + 3) & 0xFF) for i in range(n))
    right = bytes(((i * 11 + 5) & 0xFF) for i in range(n))
    return left, right


def write_dsf(path, left, right, meta=False):
    n = len(left)
    data = bytearray()
    for blk in range(0, n, BLOCK):
        data += left[blk:blk + BLOCK]
        data += right[blk:blk + BLOCK]
    fmt = struct.pack('<IIIIIIQI', 1, 0, 2, 2, RATE, 1, n * 8, BLOCK)
    fmt += struct.pack('<I', 0)
    assert len(fmt) == 40, len(fmt)
    fmt_chunk = b'fmt ' + struct.pack('<Q', 52) + fmt
    data_chunk = b'data' + struct.pack('<Q', 12 + len(data)) + data
    body = fmt_chunk + data_chunk
    total = 28 + len(body)
    ptr = 0
    if meta:
        ptr = total
        id3 = b'ID3\x04\x00\x00\x00\x00\x00\x00'
        body += id3
        total = 28 + len(body)
    hdr = b'DSD ' + struct.pack('<QQQ', 28, total, ptr)
    with open(path, 'wb') as f:
        f.write(hdr + body)
    return total


def _chunk(cid, payload):
    """DSDIFF 子块：id(4) + u64be size + payload + pad(偶数对齐)"""
    if len(payload) % 2:
        payload += b'\x00'
    return cid + struct.pack('>Q', len(payload)) + payload


def write_dff(path, left, right):
    n = len(left)
    inter = bytearray()
    for i in range(n):
        inter.append(left[i])
        inter.append(right[i])
    prop = b'SND '
    prop += _chunk(b'FS  ', struct.pack('>I', RATE))
    prop += _chunk(b'CHNL', struct.pack('>H', 2) + b'SLFT' + b'SRGT')
    prop += _chunk(b'CMPR', b'DSD ' + bytes([14]) + b'not compressed')
    prop = _chunk(b'PROP', prop)
    dsd = b'DSD ' + struct.pack('>Q', len(inter)) + bytes(inter)
    fver = _chunk(b'FVER', struct.pack('>I', 0x01050000))
    body = b'DSD ' + fver + prop + dsd   # FRM8 的 formType 是 4 字节 "DSD "
    with open(path, 'wb') as f:
        f.write(b'FRM8' + struct.pack('>Q', len(body)) + body)
    return 92, len(inter)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '/tmp/dsd_fixtures'
    os.makedirs(out, exist_ok=True)
    left, right = patterns(FRAMES)
    dsf = os.path.join(out, 'dsf64.dsf')
    write_dsf(dsf, left, right)
    dsf_meta = os.path.join(out, 'dsf_meta.dsf')
    write_dsf(dsf_meta, left, right, meta=True)
    dff = os.path.join(out, 'dff64.dff')
    write_dff(dff, left, right)
    info = {
        'dsf': dsf, 'dsf_meta': dsf_meta, 'dff': dff,
        'rate': RATE, 'frames': FRAMES,
        'interleaved_bytes': FRAMES * 2,
        'expect_dsf': {
            # DSF 存储 MSB-first → 读出的应是位反转后的字节
            'left_first8': [bitrev(left[i]) for i in range(8)],
            'right_first8': [bitrev(right[i]) for i in range(8)],
            'left_around_block1': [bitrev(left[i]) for i in range(BLOCK - 3, BLOCK + 3)],
        },
        'expect_dff': {
            'left_first8': [left[i] for i in range(8)],
            'right_first8': [right[i] for i in range(8)],
        },
        'left': list(left), 'right': list(right),
    }
    with open(os.path.join(out, 'expect.json'), 'w') as f:
        json.dump(info, f)
    print(json.dumps({k: v for k, v in info.items() if k not in ('left', 'right')}, indent=2))


if __name__ == '__main__':
    main()
