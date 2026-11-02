#!/usr/bin/env python3
"""Make the EV mod's in-memory recipe from the Creta's stock libautoreceiver_infobank.so.

usage: mk_ev_recipe.py <stock lib> <output recipe>

The same three edits as patch_library.py, as an HMG-API memory recipe: hashes of the
stock bytes and the bytes written, no manufacturer code.
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from patch_library import PATCHES

CONTEXT = 16


def main():
    stock = open(sys.argv[1], 'rb').read()
    h = hashlib.md5(stock).hexdigest()
    assert h == '48ee793af8b86052503b8c952b034731', h
    done = bytearray(stock)
    for off, old, new, _ in PATCHES:
        assert stock[off:off + len(old)] == old, hex(off)
        done[off:off + len(new)] = new
    out = ['# Android Auto library, Creta Electric India stock build: accept sensor type 23 and send one',
           '# whole raw message (egmp-aa-ev-feat docs/RESEARCH.md section 5d). No overlap with the Wireless',
           '# mod\'s recipe. Holds only hashes of the stock bytes and the new bytes written.',
           'id ev-sensors-' + h[:8], 'file /system/lib/libautoreceiver_infobank.so']
    for off, old, new, _ in PATCHES:
        a, n = off - CONTEXT, len(old) + 2 * CONTEXT
        out.append(f'verify {a:#x} {n} {hashlib.md5(stock[a:a + n]).hexdigest()} {hashlib.md5(bytes(done[a:a + n])).hexdigest()}')
    for off, old, new, _ in PATCHES:
        out.append(f'patch {off:#x} {new.hex()}')
    open(sys.argv[2], 'w').write('\n'.join(out) + '\n')


if __name__ == '__main__':
    main()
