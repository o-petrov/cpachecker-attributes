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
    For a given typename generate variables with all possible alignments,
    with no/[3]/[3][3] array modifier, then get all sizes and aligns,
    and instead of v generate typedefs. Then do the same with generated typedefs,
    then do the same with typedef'ed typedefs one more time.
    """
    # TODO pointers
    aligns = [''] + [
        f'__attribute__ ((__aligned__({align})))'
        for align in map(lambda x: 2 ** x, range(7))
    ] + ['__attribute__ ((__aligned__))']

    arrays = ['', '[3]', '[3][3]']

    def __init__(self, path):
        self.path = os.path.abspath(path) + '/'
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
    def filepath(self, name, as_include):
        """Absolute path or C #include"""
        return self.test.path + name if as_include else f'#include "{name}"\n'

    def tdef_header(self, *, previous=False, as_include=False):
        """(Previous) header with typedefs."""
        lvl = self.tdef_lvl - 1 if previous else self.tdef_lvl
        return self.filepath(f'td{lvl}.h', as_include) if lvl > 0 else ''

    def vars_header(self, as_include=False):
        """Variable declarations"""
        return self.filepath(f'v{self.tdef_lvl}.h', as_include)

    def vars_body(self, *, part=None, as_include=False):
        """Test body, with -part{part} suffix if specified"""
        return self.filepath(f'v{self.tdef_lvl}.c' if part is None else f'v{self.tdef_lvl}-part{part}.c', as_include)

    def tinfo_printer(self):
        # .c will never be included
        return self.filepath(f'print-typeinfo{self.tdef_lvl}.c', as_include=False)

    def __init__(self, command, dirpath):
        self.command = command
        self.dirpath = os.path.abspath(dirpath) + '/'
        self.test = None
        self.tdef_lvl = None
        print(f"Creating tests for compiler '{self.command}' in '{self.dirpath}' directory")

    def generate_all_arithmetic_types(self):
        """Generate tests for all possible arithmetic types."""
        # TODO check if _Imaginary etc is used by compiler?

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

    def generate_arithmetic_type(self, typename):
        """Generate tests for given arithmetic type."""
        print('Generating files for arithmetic type', typename)
        self.test = Test(typename)
        os.makedirs(self.test.path, 0o777, exist_ok=True)

        # variables of given type
        self.tdef_lvl = 0
        self.test.declare_all(typename)
        self.write_headers()
        typeinfo = self.add_typeinfo()
        self.write_bodies()
        print('generated target-independent files: without typedefs')

        # variables of generated typedefs
        self.tdef_lvl = 1
        self.declare_typedef_vars(typeinfo)
        self.write_headers()
        typeinfo = self.add_typeinfo()
        self.write_bodies()
        print('generated headers with first level typedefs')

        # variables of second-level typedefs
        self.tdef_lvl = 2
        self.declare_typedef_vars(typeinfo)
        self.write_headers(write_next_tdef=False)
        self.add_typeinfo()
        self.write_bodies()
        print('generated headers with second level typedefs')

    def write_headers(self, write_next_tdef=True):
        """Write variable declarations into one header, type info prints into a .c file,
        and possibly generated typedefs into another header."""

        with open(self.vars_header(), 'w', encoding='utf8') as fp:
            fp.write(self.tdef_header(as_include=True) + '\n'.join(self.test.head) + '\n')
        self.test.head = []

        with open(self.tinfo_printer(), 'w', encoding='utf8') as fp:
            fp.write(
                f'#include <stdio.h>\n'
                + self.vars_header(True)
                + f'int main() {{\n'
                + '\n'.join(self.test.info)
                + '\n    return 0;\n}\n'
            )
        self.test.info = []

        if write_next_tdef:
            with open(self.tdef_header(), 'w', encoding='utf8') as fp:
                fp.write(
                    self.tdef_header(previous=True, as_include=True)
                    + '\n'.join(self.test.tdef)
                    + '\n'
                )
        self.test.tdef = []

    def add_typeinfo(self):
        """Add alignment and size numbers for declared variables.

        This information may be needed for next stages, so return the file.
        """
        # TODO output file?

        compilation = subprocess.run([self.command + f' "{self.tinfo_printer()}"'], shell=True)
        if compilation.returncode != 0:
            raise Exception(f'Compilation of {self.tinfo_printer()} failed (code {compilation.returncode})')

        printing = subprocess.run(['./a.out'], capture_output=True)
        if printing.returncode != 0:
            print(printing.stderr, file=sys.stderr)
            raise Exception(f'Printing type info (file {self.tinfo_printer()}) failed (code {compilation.returncode})')

        with open(self.vars_header(), 'ab') as fp:
            fp.write(printing.stdout)

        return printing.stdout

    def write_bodies(self):
        """Split test body in smaller parts and write into files."""

        def write_test_body(body, part):
            with open(self.vars_body(part=part), 'w', encoding='utf8') as fp:
                fp.write(
                    '#include <assert.h>\n'
                    f'#include "{self.vars_header()}"\n'
                    'int main() {\n'
                    + '\n'.join(body)
                    + '\n    return 0;\n}\n'
                )

        remaining_body = self.test.body
        self.test.body = []
        part = 0
        while len(remaining_body) > 9000:
            part += 1
            body, remaining_body = remaining_body[:7000], remaining_body[7000:]
            write_test_body(body, part)
        if remaining_body:
            if part > 0:  # splited in parts
                write_test_body(remaining_body, part + 1)
                print(f'Test files {self.test.path}v{self.tdef_lvl}-part1..{part + 1}.c are ready.')
            else:
                write_test_body(remaining_body, None)
                print(f'Test file {self.test.path}v{self.tdef_lvl}.c is ready.')

    def declare_typedef_vars(self, typeinfo):
        """Declare variables for defined typedefs.

        If type alignment is greater then its size, arrays can not be declared,
        so typeinfo given by compiler is parsed.
        """
        size_idx = align_idx = size = align = last_type = -1

        for line in typeinfo.splitlines():
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
                self.test.declare_all(f'type{last_type}')
            else:
                # can make typedef but not array off of it
                self.test.declare_aligns(f'type{last_type}')


if __name__ == "__main__":
    t = TestSet('gcc -fmax-errors=3', '../../test_attributes')
    # t.generate_all_arithmetic_types()
    t.generate_arithmetic_type('_Bool')
