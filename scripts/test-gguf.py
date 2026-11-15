#!/usr/bin/env python3
"""Check packed weight decoding and reject malformed GGUF files without a model download."""
import ctypes as c
import math
import pathlib
import struct
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent

def string(value):
    raw = value.encode()
    return struct.pack('<Q', len(raw)) + raw

def model(kind, width, payload):
    header = b'GGUF' + struct.pack('<IQQ', 3, 1, 1)
    header += string('general.architecture') + struct.pack('<I', 8) + string('qwen3asr')
    header += string('weight') + struct.pack('<IQIQ', 1, width, kind, 0)
    return header + bytes((-len(header)) % 32) + payload

class Tensor(c.Structure):
    _fields_ = [('name', c.c_char * 128), ('shape', c.c_uint64 * 4),
                ('offset', c.c_uint64), ('dims', c.c_uint32), ('type', c.c_uint32),
                ('data', c.c_void_p), ('bytes', c.c_size_t)]

class Weights(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='myrec-gguf-')
        cls.root = pathlib.Path(cls.temp.name)
        library = cls.root / 'gguf.so'
        subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-fPIC', '-shared',
                        str(ROOT / 'app/src/main/c/gguf.c'), '-lm', '-o', str(library)], check=True)
        cls.lib = c.CDLL(str(library))
        cls.lib.myrec_gguf_open.argtypes = [c.c_char_p]
        cls.lib.myrec_gguf_open.restype = c.c_void_p
        cls.lib.myrec_gguf_close.argtypes = [c.c_void_p]
        cls.lib.myrec_gguf_find.argtypes = [c.c_void_p, c.c_char_p]
        cls.lib.myrec_gguf_find.restype = c.POINTER(Tensor)
        cls.lib.myrec_gguf_row.argtypes = [c.POINTER(Tensor), c.c_size_t, c.POINTER(c.c_float)]

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def open(self, data):
        path = self.root / 'fixture.gguf'
        path.write_bytes(data)
        return self.lib.myrec_gguf_open(str(path).encode())

    def decode(self, data, width):
        handle = self.open(data)
        self.assertTrue(handle)
        try:
            tensor = self.lib.myrec_gguf_find(handle, b'weight')
            out = (c.c_float * width)()
            self.lib.myrec_gguf_row(tensor, 0, out)
            return list(out)
        finally:
            self.lib.myrec_gguf_close(handle)

    def test_f32(self):
        values = [0., -1., 1.25, 1000.]
        self.assertEqual(self.decode(model(0, 4, struct.pack('<4f', *values)), 4), values)

    def test_f16(self):
        values = [0., -0., -1., 0.25, 65504., 2**-24, float('inf'), float('nan')]
        out = self.decode(model(1, len(values), struct.pack('<8e', *values)), len(values))
        self.assertEqual(out[:-1], values[:-1])
        self.assertTrue(math.isnan(out[-1]))

    def test_q8_signed_and_multiple_blocks(self):
        q = list(range(-16, 16))
        data = struct.pack('<e32b', .25, *q) + struct.pack('<e32b', -2, *q)
        self.assertEqual(self.decode(model(8, 64, data), 64), [x*.25 for x in q] + [x*-2 for x in q])

    def test_q4_six_bit_scales_and_minima(self):
        scales = [1, 12, 32, 63, 17, 31, 48, 62]
        minima = [63, 32, 12, 1, 62, 48, 31, 17]
        packed = bytes([scales[i] | (scales[i+4] >> 4) << 6 for i in range(4)] +
                       [minima[i] | (minima[i+4] >> 4) << 6 for i in range(4)] +
                       [(scales[i+4] & 15) | (minima[i+4] & 15) << 4 for i in range(4)])
        quants = [(i*7+3) % 16 for i in range(256)]
        raw = bytes(quants[g*64+i] | quants[g*64+32+i] << 4 for g in range(4) for i in range(32))
        block = struct.pack('<2e', .5, .25) + packed + raw
        expected = [.5*scales[i//32]*q - .25*minima[i//32] for i, q in enumerate(quants)]
        self.assertEqual(self.decode(model(12, 512, block*2), 512), expected*2)

    def test_truncated_file_at_every_header_position(self):
        valid = model(12, 256, bytes(144))
        for size in range(len(valid)):
            with self.subTest(size=size):
                handle = self.open(valid[:size])
                if handle: self.lib.myrec_gguf_close(handle)
                self.assertFalse(handle)

    def test_wrong_format_type_shape_and_architecture(self):
        valid = model(0, 1, bytes(4))
        fixtures = [b'NOPE'+valid[4:], valid[:4]+struct.pack('<I', 2)+valid[8:],
                    model(99, 256, bytes(1024)), model(12, 255, bytes(144)),
                    model(0, 0, bytes(4)), valid.replace(b'qwen3asr', b'not-qwen')]
        for data in fixtures:
            with self.subTest(data=data[:24]):
                handle = self.open(data)
                if handle: self.lib.myrec_gguf_close(handle)
                self.assertFalse(handle)

if __name__ == '__main__': unittest.main()
