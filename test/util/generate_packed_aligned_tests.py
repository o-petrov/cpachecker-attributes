#!/usr/bin/env python3

# This file is part of CPAchecker,
# a tool for configurable software verification:
# https://cpachecker.sosy-lab.org
#
# SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
#
# SPDX-License-Identifier: Apache-2.0

import sys
import os
import subprocess

sys.dont_write_bytecode = True  # prevent creation of .pyc files


class Counter:
    def __init__(self, start=0):
        self.count = start - 1

    def next(self, prefix=''):
        self.count += 1
        return f'{prefix}{self.count}'

    def cur(self, prefix=''):
        return f'{prefix}{self.count}'


class Test:
    """
    For a given typename generate variables with all possible alignments, with no/[3]/[3][3] array modifier,
    then get all sizes and aligns, and instead of v generate typedefs. Then do the same with generated typedefs,
    then do the same with typedef'ed typedefs one more time.
    """
    # TODO pointers
    aligns = [''] + [
        f'__attribute__ ((__aligned__({align})))'
        for align in map(lambda x: 2 ** x, range(7))
    ] + ['__attribute__ ((__aligned__))']

    arrays = ['', '[3]', '[3][3]']

    def __init__(self, path):
        self.path = os.path.abspath(path)
        self.counter = Counter()
        self.head = []
        self.info = []
        self.body = []
        self.tdef = []

    def declare_var_with(self, typename, align, array):
        # typename vXXX [] __aligned__;
        idx = self.counter.next()
        self.head.append(
            typename + ' v' + idx + array + (' ' if array or align else '') + align + ';',
        )
        self.tdef.append(
            f'typedef {typename} type{idx}{array} {align};'
        )
        for i in range(1 + array.count('[')):
            self.info += [
                f'    printf("#define SIZEV{idx}_{i} %ld\\n", sizeof(v{idx}{"[0]"*i}));',
                f'    printf("#define ALIGNV{idx}_{i} %ld\\n", _Alignof(v{idx}{"[0]"*i}));'
            ]
            self.body += [
                f'    assert(SIZEV{idx}_{i} == sizeof(v{idx}{"[0]"*i}));',
                f'    assert(ALIGNV{idx}_{i} == _Alignof(v{idx}{"[0]"*i}));'
            ]

    def declare_all(self, typename):
        for array in self.arrays:
            for align in self.aligns:
                self.declare_var_with(typename, align, array)

    def declare_aligns(self, typename):
        for align in self.aligns:
            self.declare_var_with(typename, align, '')


class TestSet:
    def __init__(self, command):
        self.command = command

    def write_test_body(self, body, filepath, header):
        with open(filepath, 'w', encoding='utf8') as fp:
            fp.write(
                '#include <assert.h>\n'
                f'#include "{header}"\n'
                'int main() {\n'
                + '\n'.join(body)
                + '\n    return 0;\n}\n'
            )
        print('file', filepath, 'is ready for testing')

    def prepare_test(self, test, lvl):
        remaining_body = test.body
        part = 0
        header = f'v{lvl}.h'
        while len(remaining_body) > 9000:
            part += 1
            body, remaining_body = remaining_body[:7000], remaining_body[7000:]
            filepath = test.path + f'/v{lvl}-part{part}.c'
            self.write_test_body(body, filepath, header)
        if remaining_body:
            if part > 0:  # splited in parts
                filepath = test.path + f'/v{lvl}-part{part+1}.c'
            else:
                filepath = test.path + f'/v{lvl}.c'
            self.write_test_body(remaining_body, filepath, header)
        test.body = []

    def generate_arithmetic_type(self, typename):
        print('generating files for arithmetic type', typename)
        test = Test(os.path.abspath(f'../../test_attributes/{typename}/'))
        os.makedirs(test.path, 0o777, exist_ok=True)
        test.declare_all(typename)
        infoprinter = self.write_headers(test, 0)
        self.prepare_test(test, 0)
        print('generated headers without typedefs')
        try:
            self.add_sizes_and_typedefs(test, infoprinter, 0)
            infoprinter = self.write_headers(test, 1)
            self.prepare_test(test, 1)
            print('generated headers with first level typedefs')
            self.add_sizes_and_typedefs(test, infoprinter, 1)
            infoprinter = self.write_headers(test, 2, gen_next_tdef=False)
            self.prepare_test(test, 2)
            print('generated headers with second level typedefs')
            self.add_sizes_and_typedefs(test, infoprinter, 2)
        except Exception as e:
            print(e)

    def add_sizes_and_typedefs(self, test, infoprinter, lvl):
        # TODO output file?
        compilation = subprocess.run([self.command + f' "{infoprinter}"'], shell=True)
        if compilation.returncode != 0:
            raise Exception('compilation failed (code %d)' % compilation.returncode)
        printing = subprocess.run(['./a.out'], capture_output=True)
        if printing.returncode != 0:
            raise Exception('printing failed (code %d)' % compilation.returncode)

        size_idx = align_idx = size = align = last_type = -1
        with open(test.path + f'/v{lvl}.h', 'ab') as fp:
            fp.write(printing.stdout)

        for line in printing.stdout.splitlines():
            try:
                define, typestat, value = line.split()
                typestat, idx_ar = typestat.split(b'V', 1)
                if typestat == b'SIZE':
                    size_idx = int(idx_ar.split(b'_')[0])
                    if size_idx == last_type:
                        continue
                    size = int(value)
                elif typestat == b'ALIGN':
                    align_idx = int(idx_ar.split(b'_')[0])
                    if align_idx == last_type:
                        continue
                    align = int(value)
                else:
                    raise Exception('Line "%s" was not expected '
                                    'while parsing types\' sizes and alignments' % line)
            except ValueError:
                raise Exception('Line "%s" was not expected '
                                'while parsing types\' sizes and alignments' % line)

            if size_idx != align_idx:
                continue
            last_type = size_idx
            if size >= align:
                # can make array of typedef
                test.declare_all(f'type{last_type}')
            else:
                # can make typedef but not array off of it
                test.declare_aligns(f'type{last_type}')

    def write_headers(self, test, tdef_lvl, gen_next_tdef=True):
        include_tdef = f'#include "td{tdef_lvl}.h"\n' if tdef_lvl > 0 else ''

        vheader = test.path + f'/v{tdef_lvl}.h'
        with open(vheader, 'w', encoding='utf8') as fp:
            fp.write(include_tdef + '\n'.join(test.head) + '\n')

        infoprinter = test.path + f'/v{tdef_lvl}-typeinfo-printer.c'
        with open(infoprinter, 'w', encoding='utf8') as fp:
            fp.write(
                f'#include <stdio.h>\n'
                f'#include "v{tdef_lvl}.h"\n'
                f'int main() {{\n'
                + '\n'.join(test.info)
                + '\n    return 0;\n}\n'
            )

        if gen_next_tdef:
            # next header has all typedefs
            tdheader = test.path + f'/td{tdef_lvl+1}.h'
            with open(tdheader, 'w', encoding='utf8') as fp:
                fp.write(include_tdef + '\n'.join(test.tdef) + '\n')
        test.head = []
        test.info = []
        test.tdef = []
        return infoprinter

    def generate_all_arithmetic_types(self):
        self.generate_arithmetic_type('_Bool')

        types = ['short', 'long', 'long long']
        types += [x + ' int' for x in types]
        types = ['char', 'int'] + types
        for typename in types:
            for sign in ['', 'signed', 'unsigned']:
                self.generate_arithmetic_type(sign + (' ' if sign else '') + typename)
        for typename in ['float', 'double', 'long double']:
            for cmplx in ['', '_Complex', '_Imaginary']:
                self.generate_arithmetic_type(typename + (' ' if cmplx else '') + cmplx)


if __name__ == "__main__":
    t = TestSet('gcc -fmax-errors=3')
    # t.generate_all_arithmetic_types()
    t.generate_arithmetic_type('_Bool')
