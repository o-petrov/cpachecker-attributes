#!/usr/bin/env python3

#  This file is part of CPAchecker,
#  a tool for configurable software verification:
#  https://cpachecker.sosy-lab.org
#
#  SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
#
#  SPDX-License-Identifier: Apache-2.0


"""
This script uses ``aligned_testing`` package to generate and execute test programs with
``__aligned__`` C attribute.
"""


import argparse
import logging
import os
import subprocess
import sys

from aligned_testing.misc import Alignment
from aligned_testing.ctypes import CType, Pointer, standard_types
from aligned_testing.graph import ExpressionGenerator
from aligned_testing.machines import machine_models


sys.dont_write_bytecode = True  # prevent creation of .pyc files
# os.environ['PYTHONPATH'] += (
#     os.pathsep + os.path.abspath('.')
#     + os.pathsep + os.path.abspath('../../scripts'))
logger = logging.getLogger(__name__)


def __nick(ctype: CType):
    if ctype.typeid:
        return ctype.typeid
    if isinstance(ctype, Pointer):
        return __nick(ctype.ref_type) + "P"
    raise NotImplementedError(ctype)


def run(command, quiet=False, output=None):
    """
    Execute the given command.

    :param output: file to output to, or None to just capture stdout
    :param List[str] command: list of words that describe the command line
    :param Bool quiet: whether to log the executed command line as INFO
    :return subprocess.CompletedProcess: information about the execution
    """
    if not quiet:
        logger.info(" ".join(command))
    result = subprocess.run(
        command,
        stdout=output or subprocess.PIPE,
        stderr=subprocess.PIPE,
        encoding="utf8",
    )
    if result.stderr:
        logger.warning(result.stderr)
    result.check_returncode()
    return result


def check_numbers():
    """
    Make expressions for an arbitrary number type and check them on char, short, int,
    long double.
    """
    eg = ExpressionGenerator()
    eg.graph_ta_va()
    for typekey in "CHAR", "SHORT", "INT", "LDOUBLE":
        ctype = standard_types[typekey]
        __check_type(ALIGNED_DIR + "/numbers_as_tava", ctype, eg)


def check_pointers():
    """
    Make expressions for a pointer to an arbitrary number type and check them on char,
    short, int, long double.
    """
    eg = ExpressionGenerator()
    eg.graph_pa_va()
    # TODO void* v but without *v+0
    for typekey in "CHAR", "SHORT", "INT", "LDOUBLE":
        ctype = Pointer(standard_types[typekey])
        __check_type(ALIGNED_DIR + "/pointers_as_pava", ctype, eg)


def __check_type(subdir: str, ctype: CType, eg: ExpressionGenerator):
    """
    Check ``ctype`` using given ``eg`` graph. Generates and runs programs for matching
    GCC and testing CPAchecker.
    """
    print("checking type", __nick(ctype))
    fdir = subdir + os.path.sep + __nick(ctype)
    os.makedirs(fdir, exist_ok=True)
    old_typeid = ctype.typeid
    old_ctype_decl = ctype.declaration + ";\n" if ctype.declaration else ""

    for ta in Alignment.__members__.values():
        print(
            "\t checking type aligned",
            ta.code,
            "\tVar aligns checked: ",
            end="",
            flush=True,
        )
        for va in Alignment.__members__.values():
            print(va.code, end=" ", flush=True)
            ctype.declaration = old_ctype_decl
            ctype.typeid = old_typeid
            if ta != Alignment.NoAttr:
                ctype.declaration += "typedef " + ctype.declare("t", ta, as_string=True)
                ctype.typeid = "t"
            ctype.align = ta
            v = ctype.declare(name="v", align=va)

            logger.debug("generating programs for %s of type %s", v, v.ctype)
            fprefix = fdir + "/" + str(ta.code) + "v" + str(va.code)

            if ONLY_PRINT:
                assert isinstance(CC_COMMAND, list) and len(CC_COMMAND) > 0
                for machine in machine_models:
                    text = eg.text_graph(mode="prints", variable=v, machine=machine)
                    fname = fprefix + "-prints-" + machine.name + ".c"
                    with open(fname, "w", encoding="utf8") as prints_file:
                        prints_file.write(text)
                    run(CC_COMMAND + [machine.gcc_option, fname])
                    with open(
                        fname.replace(".c", ".cc_out"), "w", encoding="utf8"
                    ) as output:
                        run(["./a.out"], output=output)
                    # TODO
                    #  1. Run CPAchecker and write ``cfa.c``
                    #  2. Compile ``cfa.c`` and compare prints
                continue

            if CC_COMMAND:
                for machine in machine_models:
                    text = eg.text_graph(
                        mode="static asserts", variable=v, machine=machine
                    )
                    fname = fprefix + "-static-asserts-" + machine.name + ".c"
                    with open(fname, "w", encoding="utf8") as fp:
                        fp.write(text)
                    # check model with compiler
                    run(CC_COMMAND + [machine.gcc_option, fname])

            for machine in machine_models:
                text = eg.text_graph(mode="asserts", variable=v, machine=machine)
                fname = fprefix + "-asserts-" + machine.name + ".c"
                with open(fname, "w", encoding="utf8") as fp:
                    fp.write(text)
                # check CPAchecker with model
                verification_completed = run(CPA_COMMAND + [machine.cpa_option, fname])
                if "Verification result:" not in verification_completed.stdout:
                    logger.error("No verification verdict for file %s", fname)
                    sys.exit(1)
                if "Verification result: TRUE." not in verification_completed.stdout:
                    logger.error("Verification verdict is not TRUE for file %s", fname)
                    sys.exit(1)
        print(flush=True)


ALIGNED_DIR = "test/programs/c_attributes/aligned"
SANA = "-fsanitize=address -fsanitize=pointer-compare -fsanitize=pointer-subtract -fsanitize=leak".split()
SANS = "-fsanitize=shadow-call-stack".split()
SANT = "-fsanitize=thread".split()
SANU = "-fsanitize=undefined".split()
STRICT = "-std=c11 -Wall -Werror -Wno-unused-value -Wno-format".split()  # + SANA + SANU
STRICT2 = SANS + "-Wno-gnu-alignof-expression -Wno-sizeof-array-decay -Wno-address-of-packed-member".split()

CC_COMMAND = None
ONLY_PRINT = False
CPA_COMMAND = "scripts/cpa.sh -preprocess -default -benchmark -heap 1200M -nolog -noout".split()
# '-setprop cfa.callgraph.export=false -setprop cfa.export=false ' \
# '-setprop cfa.exportPerFunction=false -setprop cfa.exportToC=true'.split()


def main():
    # logging.basicConfig(level=logging.INFO)

    # cd CPAchecker root directory
    if os.path.isfile("../scripts/cpa.sh"):
        os.chdir("..")
    elif os.path.isfile("../../scripts/cpa.sh"):
        os.chdir("../..")

    if not os.path.isdir("test/programs"):
        raise Exception("directory test/programs not found or not a directory")
    if not os.path.isfile("scripts/cpa.sh") or not os.access("scripts/cpa.sh", os.X_OK):
        raise Exception("CPAchecker not found or not executable")

    parser = argparse.ArgumentParser(
        description="Generate and check programs to test alignment attributes in CPAchecker."
    )
    compilers = parser.add_mutually_exclusive_group()
    compilers.add_argument(
        "--gcc",
        dest="compiler",
        action="store_const",
        const=["gcc"] + STRICT,
        help="Use GCC to check testing model.",
    )
    compilers.add_argument(
        "--clang",
        dest="compiler",
        action="store_const",
        const=["clang"] + STRICT + STRICT2,
        help="Use Clang to check testing model.",
    )
    parser.add_argument(
        "--print",
        dest="print",
        action="store_true",
        help="Instead of asserts, generate prints and compare output of compiler and CPAchecker.",
    )

    args = parser.parse_args()
    global CC_COMMAND, ONLY_PRINT
    CC_COMMAND = args.compiler or None
    ONLY_PRINT = args.print
    if ONLY_PRINT and CC_COMMAND is None:
        print("Print mode requires some compiler specified.")
        exit(1)

    check_pointers()


if __name__ == "__main__":
    main()
