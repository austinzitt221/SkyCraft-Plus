#!/usr/bin/env python3
"""Builds the pre-made collision model library: one shp_<key>.nif per distinct block collision shape.

Input : shape_catalog.json, written by the Fabric side (ShapeCatalog.java; it is written by itself, once, into
        <Minecraft folder>/skycraft/ the first time SkyCraft's world starts).
Output: shp_<key>.nif files. The model's origin is the block's low corner; boxes are in Skyrim game units
        (1 block = 70 units, Minecraft x/y/z -> Skyrim x/z/-y is applied when the model is placed, not here).

The file layout is a port of skse/src/CollisionNif.h (level 3: BSFadeNode, BSXFlags, collision object, rigid body, list
of convex-vertices boxes). `--selftest` compares it, byte for byte, with that header's output (needs g++).

  python tools/make_collision_library.py shape_catalog.json out_dir
  python tools/make_collision_library.py --selftest
"""
import json, os, struct, subprocess, sys, tempfile

HAVOK = 69.99124
UNITS_PER_BLOCK = 70.0
STONE = 3741512247
MAX_BOXES = 4000
FLAGS_COLL, FLAGS_BSX = 129, 130


def f32(v):
    return struct.unpack("<f", struct.pack("<f", v))[0]


def fnv_key(boxes):
    s = ";".join(",".join(str(v) for v in b) for b in boxes).encode("ascii")
    h = 0x811C9DC5
    for c in s:
        h = ((h ^ c) * 0x01000193) & 0xFFFFFFFF
    return "%08x" % h


def _filter():
    return struct.pack("<BBH", 1, 0, 0)


def _vec4(x, y, z, w):
    return struct.pack("<4f", x, y, z, w)


def _prop(n):
    return struct.pack("<III", 0, n, 0x80000000 | n)


def _rigid_body(shape_ref):
    b = struct.pack("<i", shape_ref) + _filter() + struct.pack("<I", 0) + b"\x01\0\0\0" + _prop(0)
    b += struct.pack("<BBHI", 1, 0, 0xFFFF, 0) + _filter() + bytes(8) + struct.pack("<BBH", 1, 0, 0xFFFF)
    b += _vec4(0, 0, 0, 0) + _vec4(0, 0, 0, 1) + _vec4(0, 0, 0, 0) + _vec4(0, 0, 0, 0) + bytes(48) + _vec4(0, 0, 0, 0)
    b += struct.pack("<11f", 0.0, 0.1, 0.05, 1.0, 1.0, 0.5, 0.0, 0.4, 104.4, 31.57, 0.15)
    b += bytes([5, 1, 1, 0, 0, 0, 3, 0]) + bytes(12) + struct.pack("<IH", 0, 0)
    return b


def build(boxes, material=STONE, radius=0.01):
    """boxes: [(x0,y0,z0,x1,y1,z1)] in game units."""
    boxes = boxes[:MAX_BOXES]
    n = len(boxes)
    # Revision 35: blocks in Skyrim's own order (children before the block that refers to them): 0 root, 1 BSXFlags, 2.. the convex
    # shapes, then the list shape, the rigid body and the collision object. A list shape ahead of its children crashed the model loader.
    list_at, body_at, coll_at = 2 + n, 3 + n, 4 + n
    blocks = []
    ident = (1, 0, 0, 0, 1, 0, 0, 0, 1)
    blocks.append(struct.pack("<iIii", 0, 1, 1, -1) + struct.pack("<I", 14) + struct.pack("<3f", 0, 0, 0)
                  + struct.pack("<9f", *ident) + struct.pack("<f", 1.0) + struct.pack("<iII", coll_at, 0, 0))
    blocks.append(struct.pack("<iI", 1, FLAGS_BSX))
    for x0, y0, z0, x1, y1, z1 in boxes:
        # float32 arithmetic, as the C++ writer does it, so the bytes are identical
        lo = tuple(f32(f32(v) / f32(HAVOK)) for v in (x0, y0, z0))
        hi = tuple(f32(f32(v) / f32(HAVOK)) for v in (x1, y1, z1))
        b = struct.pack("<If", material, radius) + bytes(24) + struct.pack("<I", 8)
        for c in range(8):
            b += _vec4(hi[0] if c & 1 else lo[0], hi[1] if c & 2 else lo[1], hi[2] if c & 4 else lo[2], 0.0)
        b += struct.pack("<I", 6)
        b += _vec4(1, 0, 0, -hi[0]) + _vec4(-1, 0, 0, lo[0]) + _vec4(0, 1, 0, -hi[1]) + _vec4(0, -1, 0, lo[1])
        b += _vec4(0, 0, 1, -hi[2]) + _vec4(0, 0, -1, lo[2])
        blocks.append(b)
    lst = struct.pack("<I", n) + b"".join(struct.pack("<i", 2 + i) for i in range(n)) + struct.pack("<I", material)
    lst += bytes(12) + bytes(12) + struct.pack("<I", n) + _filter() * n
    blocks.append(lst)
    blocks.append(_rigid_body(list_at))
    blocks.append(struct.pack("<iHi", 0, FLAGS_COLL, body_at))
    types = ["BSFadeNode", "BSXFlags", "bhkConvexVerticesShape", "bhkListShape", "bhkRigidBody", "bhkCollisionObject"]
    strings = ["SkyCraftCollision", "BSX"]
    f = b"Gamebryo File Format, Version 20.2.0.7\n" + struct.pack("<IBIII", 0x14020007, 1, 12, len(blocks), 100)
    for t in ("SkyCraft", "", ""):
        f += struct.pack("<B", len(t) + 1) + t.encode() + b"\0"
    f += struct.pack("<H", 6)
    for t in types:
        f += struct.pack("<I", len(t)) + t.encode()
    for i in range(len(blocks)):
        f += struct.pack("<H", 0 if i == 0 else 1 if i == 1 else 2 if i < list_at else i - list_at + 3)
    for b in blocks:
        f += struct.pack("<I", len(b))
    f += struct.pack("<II", 2, len(strings[0]))
    for s in strings:
        f += struct.pack("<I", len(s)) + s.encode()
    f += struct.pack("<I", 0) + b"".join(blocks) + struct.pack("<Ii", 1, 0)
    return f


def sixteenths_to_units(box):
    # Minecraft (x, y up, z) in sixteenths -> the model's own axes (x, y north = -z, z up), game units.
    x0, y0, z0, x1, y1, z1 = (v * UNITS_PER_BLOCK / 16.0 for v in box)
    return (x0, -z1, y0, x1, -z0, y1)


def make(catalog_path, out_dir):
    with open(catalog_path, encoding="utf-8") as fh:
        cat = json.load(fh)
    os.makedirs(out_dir, exist_ok=True)
    count = 0
    for shape in cat["shapes"]:
        boxes = [tuple(b) for b in shape["boxes"]]
        if fnv_key(boxes) != shape["key"]:
            sys.exit("key mismatch for %s: the catalog and this tool disagree on ShapeCatalog.canonical" % shape["key"])
        data = build([sixteenths_to_units(b) for b in boxes])
        with open(os.path.join(out_dir, "shp_%s.nif" % shape["key"]), "wb") as fh:
            fh.write(data)
        count += 1
    print("wrote %d shape models to %s" % (count, out_dir))


def selftest():
    src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "skse", "src")
    cases = [[(0, 0, 0, 70, 70, 70)], [(0, 0, 0, 70, 70, 35), (0, 17.5, 35, 70, 52.5, 70)], [(1.5, 2.25, 3, 8, 9.5, 70)]]
    with tempfile.TemporaryDirectory() as d:
        cpp = os.path.join(d, "t.cpp")
        with open(cpp, "w") as fh:
            fh.write('#include "CollisionNif.h"\n#include <cstdio>\nint main(){using namespace skycraft::collisionnif;\n')
            for i, c in enumerate(cases):
                fh.write("{std::vector<Box> v{%s}; auto b=Build(v); FILE*f=fopen(\"%s/c%d.nif\",\"wb\"); fwrite(b.data(),1,b.size(),f); fclose(f);}\n"
                         % (",".join("{{%g,%g,%g},{%g,%g,%g}}" % (x[0], x[1], x[2], x[3], x[4], x[5]) for x in c), d.replace("\\", "/"), i))
            fh.write("}\n")
        subprocess.check_call(["g++", "-std=c++20", "-I", src, cpp, "-o", os.path.join(d, "t")])
        subprocess.check_call([os.path.join(d, "t")])
        for i, c in enumerate(cases):
            want = open(os.path.join(d, "c%d.nif" % i), "rb").read()
            got = build(c)
            print("case", i, "identical" if want == got else "DIFFERENT (%d vs %d bytes)" % (len(want), len(got)))
            if want != got:
                sys.exit(1)
    print("selftest passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    elif len(sys.argv) == 3:
        make(sys.argv[1], sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
