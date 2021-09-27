#  This file is part of CPAchecker,
#  a tool for configurable software verification:
#  https://cpachecker.sosy-lab.org
#
#  SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
#
#  SPDX-License-Identifier: Apache-2.0

import sys
import os
import subprocess
from itertools import combinations_with_replacement

sys.dont_write_bytecode = True  # prevent creation of .pyc files


compile = 'gcc -fmax-errors=3'
analyse = '../scripts/cpa.sh -64 -preprocess -default ' \
          '-setprop cfa.simplifyCfa=true ' \
          '-setprop log.consoleLevel=WARNING ' \
          '-setprop output.disable=true'


class Counter:
    def __init__(self, prefix='', start=0):
        self.count = start - 1
        self.prefix = prefix

    def next(self, prefix=None):
        self.count += 1
        return f'{prefix or self.prefix}{self.count}'

    def cur(self, prefix=None):
        return f'{prefix or self.prefix}{self.count}'


# for every variable of every type
printf_var = '    printf("' \
              '#define SIZE{nick} %zu\\n' \
              '#define ALIGN{nick} %zu\\n' \
              '", sizeof({expr}), _Alignof({expr}));'
assert_var = '    assert(sizeof({expr}) == SIZE{nick} && _Alignof({expr}) == ALIGN{nick});'

# for every member in every composite type
printf_offset = '    printf("#define OFFSET{nick}M{m} %zu\\n", offsetof({t}, m{m}));'
assert_offset = '    assert(offsetof({t}, m{m}) == OFFSET{nick}M{m});'

# for every member of every variable of every composite type (but not for ptrs or arrs of them)
printf_member = '    printf("' \
                '#define SIZE{nick} %zu\\n' \
                '#define ALIGN{nick} %zu\\n' \
                '#define ADIFF{nick} %td\\n' \
                '", ' \
                'sizeof({expr}), ' \
                '_Alignof({expr}), ' \
                '(void *) &({expr}) - (void *) &({expr_nom})' \
                ');'
assert_member = '    assert(sizeof({expr}) == SIZE{nick} ' \
                '&& _Alignof({expr}) == ALIGN{nick}' \
                '&& (void *) &({expr}) - (void *) &({expr_nom}) == ADIFF{nick});'


def var_mods():
    """Generates all possible array-pointer sequences.
    In sequence 'p' stands for pointer, a digit stands for array dimension.
    """
    yield ''
    for m in var_mods():
        yield m + 'p'
        yield m + '3'


def all_mods_var_decl(typename='int', members_mods=(), depth=2):
    """Generates proper declarations with type and alignment along with typeinfo prints and asserts.
    :param members_mods: for each member in composite type has its array-pointer sequence."""
    for i, ms in enumerate(var_mods()):
        if i == 2 ** (depth + 1) - 1:
            break
        # generate declaration deep info and tests
        var = 'v'
        decl = var
        expr = var
        nick = 'V'

        info = [printf_var.format(nick=nick, expr=expr)]
        body = [assert_var.format(nick=nick, expr=expr)]

        for mod in ms:
            if mod == 'p':
                decl = '*' + (' ' if not decl.startswith('*') else '') + decl
                nick = 'P' + nick
                expr = '*' + (' ' if not expr.startswith('*') else '') + expr
                info.append(printf_var.format(nick=nick, expr=expr))
                body.append(printf_var.format(nick=nick, expr=expr))
            elif mod.isdigit():
                if decl.startswith('*'):
                    decl = f'({decl})[{mod}]'
                    expr = f'({expr})[0]'
                else:
                    decl = decl + f'[{mod}]'
                    expr = expr + '[0]'
                nick = 'A' + nick
                info.append(printf_var.format(nick=nick, expr=expr))
                body.append(printf_var.format(nick=nick, expr=expr))
        expr_nom = expr
        for member, mods in enumerate(members_mods):
            nick = nick + f'M{member}'
            if expr.startswith('*'):
                expr = f'({expr})'
            expr += f'.m{member}'
            info.append(printf_member.format(nick=nick, expr=expr, expr_nom=expr_nom))
            body.append(assert_member.format(nick=nick, expr=expr, expr_nom=expr_nom))
            for mod in mods:
                if mod == 'p':
                    nick += 'P'
                    expr = '*' + (' ' if not expr.startswith('*') else '') + expr
                    info.append(printf_member.format(nick=nick, expr=expr, expr_nom=expr_nom))
                    body.append(printf_member.format(nick=nick, expr=expr, expr_nom=expr_nom))
                elif mod.isdigit():
                    if expr.startswith('*'):
                        expr = f'({expr})'
                    expr += '[0]'
                    nick += 'A'
                    info.append(printf_member.format(nick=nick, expr=expr, expr_nom=expr_nom))
                    body.append(printf_member.format(nick=nick, expr=expr, expr_nom=expr_nom))
        # now yield the var, info, test with different alignments
        yield typename + ' ' + decl + ';', info, body


def get_size_and_gen_aligned(typename='int', members_mods=(), depth=2):
    for var_decl, info, body in all_mods_var_decl(typename, members_mods, depth):
        pass


def gen_simple_structs():
    member_types = [
        typename + (' ' if aligned else '') + aligned
        for typename in ['char', 'int', 'long double']
        for aligned in [''] + [
            f'__attribute__((__aligned__({x})))' for x in [1, 2, 4, 8, 16, 32]
        ]
    ]
    counter = Counter()
    w_counter = Counter()
    info = []
    body = []
    decl = []
    lines = 0
    for count in range(1, 4):
        print('Structs with', count, 'member' if count == 1 else 'members')
        for members in combinations_with_replacement(member_types, count):
            for paligned in [' ', ' __attribute__((__packed__)) '] + [
                f' __attribute__((__aligned__({x}))) ' for x in [1, 2, 4, 8, 16, 32]
            ] + [
                f' __attribute__((__packed__, __aligned__({x}))) ' for x in [1, 2, 4, 8, 16, 32]
            ]:
                cur = counter.next()
                curv = 'v' + cur
                curS = 'S' + cur
                curst = 'struct s' + cur
                decl += [curst + ' {'] + [
                    f'    {member_type} m{i};' for i, member_type in enumerate(members)
                ] + ['}' + paligned + curv + ';\n']
                info += [printf_var.format(nick=curS, expr=curst)]
                body += [assert_var.format(nick=curS, expr=curst)]
                info += [printf_offset.format(nick=curS, t=curst, m=i) for i in range(count)]
                body += [assert_offset.format(nick=curS, t=curst, m=i) for i in range(count)]
                info += [printf_member.format(nick='V' + cur + f'M{i}', expr=curv + f'.m{i}', expr_nom=curv) for i in range(count)]
                body += [assert_member.format(nick='V' + cur + f'M{i}', expr=curv + f'.m{i}', expr_nom=curv) for i in range(count)]

                lines += 2 + count  # decl lines
                lines += 2 + count + 1 + count  # sizeof type, align of type, offsets
                lines += 2 + count + 1 + count  # size, align, adiff for var
                if lines > 1111:  # part the file so its not too long
                    write_files(decl, info, body)
                    decl = []
                    info = []
                    body = []
                    lines = 0
    write_files(decl, info, body)


struct_files = Counter()


def write_files(decls, prints, asserts):
    struct_files.next()
    file = struct_files.count
    dir = file // 200
    file %= 200

    print(f'writing files for {dir}/{file}')
    if not os.path.isdir(f'{dir}'):
        os.mkdir(f'{dir}')

    printer = f'{dir}/structs{file}printer.c'
    with open(printer, 'w', encoding='utf8') as fp:
        fp.write(
            '#include <stddef.h>\n#include <stdio.h>\n'
            + '\n'.join(decls)
            + '\nint main() {\n'
            + '\n'.join(prints) +
            '\n    return 0;\n}\n'
        )

    this_compile = f'{compile} "{printer}"'
    print(this_compile)
    compilation = subprocess.run([this_compile], shell=True)
    if compilation.returncode != 0:
        raise Exception(
            f'Compilation of {printer} failed (code {compilation.returncode})')

    with open(f'{dir}/structs{file}.h', 'ab') as fp:
        printing = subprocess.run(['./a.out'], stdout=fp)
        if printing.returncode != 0:
            raise Exception(
                f'Printing type info ({printer}) failed (code {printing.returncode})')

    test_file = f'{dir}/structs{file}.c'
    with open(test_file, 'w', encoding='utf8') as fp:
        fp.write(
            '#include <stddef.h>\n#include <assert.h>\n#include <stdio.h>\n'
            f'#include "structs{file}.h"\n'
            + '\n'.join(decls)
            + '\nint main() {\n'
            + '\n'.join(asserts) +
            '\n    return 0;\n}\n'
        )

    this_analyse = analyse + f' "{test_file}"'
    print(this_analyse, end='\n\n')
    analysis = subprocess.run([this_analyse], shell=True, capture_output=True)
    if analysis.returncode != 0:
        print(analysis.stdout.decode('ascii'))
        print(analysis.stderr.decode('ascii'))
        raise Exception(f'Analysis of {test_file} failed (code {analysis.returncode})')
    else:
        r = analysis.stdout.rfind(b'Verification result: ') + len(b'Verification result: ')
        result = analysis.stdout[r:].split(b' ', 1)[0] if r > 20 else b'ERROR.'
        print(analysis.stdout.decode('ascii'))
        if result == b'ERROR.':
            print(analysis.stderr.decode('ascii'))
        if result != b'TRUE.':
            raise Exception(f'Analysis of {test_file} failed (result is {result})')


if __name__ == '__main__':
    os.chdir(os.path.dirname(__file__) + '/../../test_attributes3')
    gen_simple_structs()
