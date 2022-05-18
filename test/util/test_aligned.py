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


def run(command, output=None):
    """
    Execute the given command.

    :param output: file to output to, or None to just capture stdout
    :param List[str] command: list of words that describe the command line
    :return subprocess.CompletedProcess: information about the execution
    """
    logger.debug(" ".join(command))
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


def run_cpachecker(command, filename, has_cfa_c=False):
    """
    Run CPAchecker with command and check the result is TRUE.

    :param list[str] command: full command except for filename
    :param str filename: C file to verify
    :param has_cfa_c: check there is output/cfa.c after CPAchecker run
    """
    verification_completed = run(command + [filename])
    if "Verification result:" not in verification_completed.stdout:
        logger.error("No verification verdict for file %s", filename)
        sys.exit(1)
    if "Verification result: TRUE." not in verification_completed.stdout:
        logger.error("Verification verdict is not TRUE for file %s", filename)
        sys.exit(1)
    if has_cfa_c and not os.path.isfile("output/cfa.c"):
        logger.error("No cfa.c file found")
        sys.exit(1)


def compile_and_run(command, filename, outfilename):
    """
    Compile ``filename`` using ``command`` to ``a.out``. Run it and write output
    to ``outfilename``.
    """
    run(command + [filename])
    with open(outfilename, "w", encoding="utf8") as output:
        run(["./a.out"], output=output)


def check_numbers(args):
    """
    Make expressions for an arbitrary number type and check them on char, short, int,
    long double.
    """
    eg = ExpressionGenerator(
        loop_depth=args.loop_depth,
        cycle_depth=args.cycle_depth,
        pointer_arithmetic=args.pointer_arithmetic,
        number_arithmetic=args.number_arithmetic,
    )
    eg.graph_ta_va()
    for typekey in "CHAR", "SHORT", "INT", "LDOUBLE":
        ctype = standard_types[typekey]
        __check_type(args, ALIGNED_DIR + "/numbers_as_tava", ctype, eg)


def check_pointers(args):
    """
    Make expressions for a pointer to an arbitrary number type and check them on char,
    short, int, long double.
    """
    eg = ExpressionGenerator(
        loop_depth=args.loop_depth,
        cycle_depth=args.cycle_depth,
        pointer_arithmetic=args.pointer_arithmetic,
        number_arithmetic=args.number_arithmetic,
    )
    eg.graph_pa_va()
    for typekey in "CHAR", "SHORT", "INT", "LDOUBLE":
        ctype = Pointer(standard_types[typekey])
        __check_type(args, ALIGNED_DIR + "/pointers_as_pava", ctype, eg)
    if args.number_arithmetic:
        args.number_arithmetic = False
        eg = ExpressionGenerator(
            loop_depth=args.loop_depth,
            cycle_depth=args.cycle_depth,
            pointer_arithmetic=args.pointer_arithmetic,
            number_arithmetic=False,
        )
    eg.graph_pa_va()
    ctype = Pointer(standard_types["VOID"])
    __check_type(args, ALIGNED_DIR + "/pointers_as_pava", ctype, eg)


def __check_type(args, subdir: str, ctype: CType, eg: ExpressionGenerator):
    """
    Check ``ctype`` using given ``eg`` graph. Generates and runs programs for matching
    GCC and testing CPAchecker.
    """

    def write_cfile(mode):
        text = eg.text_graph(mode=mode.strip(), variable=v, machine=machine)
        filename = fprefix + mode.replace(" ", "-") + machine.name
        with open(filename + ".c", "w", encoding="utf8") as output:
            output.write(text)
        return filename

    def check_prints(filename):
        ccc = args.cc_command + [machine.gcc_option]
        # Check that compiled program and CPAchecker print same numbers
        compile_and_run(ccc, filename + ".c", filename + ".cc_out")
        #  1. Run CPAchecker and write ``cfa.c``
        run_cpachecker(
            CPA_PRINTS + [machine.cpa_option], filename + ".c", has_cfa_c=True
        )
        #  2. Compile ``cfa.c``
        compile_and_run(ccc, "output/cfa.c", filename + ".cpa_out")
        #  3. Compare results
        run(["diff", filename + ".cc_out", filename + ".cpa_out"])

    logger.info("checking type " + __nick(ctype))
    fdir = subdir + os.path.sep + __nick(ctype)
    os.makedirs(fdir, exist_ok=True)
    old_typeid = ctype.typeid
    old_ctype_decl = ctype.declaration + ";\n" if ctype.declaration else ""

    for machine in machine_models:
        logger.info("\tchecking machine " + machine.name)
        if args.all_alignments:
            alignments_to_check = Alignment.__members__.values()
        else:
            default_align = machine.size_align_of(ctype)[1]
            a1, a2 = Alignment.get_two_nearest(default_align)
            alignments_to_check = [Alignment.NoAttr, a1, a2]

        for ta in alignments_to_check:
            logger.info("\t\tchecking type align " + str(ta.code))
            ctype.declaration = old_ctype_decl
            ctype.typeid = old_typeid
            if ta != Alignment.NoAttr:
                ctype.declaration += "typedef " + ctype.declare("t", ta, as_string=True)
                ctype.typeid = "t"
            ctype.align = ta

            for va in alignments_to_check:
                logger.info("\t\t\tchecking var align " + str(va.code))
                v = ctype.declare(name="v", align=va)

                logger.debug("generating programs for %s of type %s", v, v.ctype)
                fprefix = fdir + "/" + str(ta.code) + "v" + str(va.code)

                if args.do_prints:
                    filename = write_cfile(" prints ")
                    if not args.only_gen:
                        check_prints(filename)
                    continue

                if args.cc_command:
                    filename = write_cfile(" static asserts ")
                    if not args.only_gen:
                        # Check computed alignments match compiler alignments
                        run(args.cc_command + [machine.gcc_option, filename + ".c"])

                filename = write_cfile(" asserts ")
                if not args.only_gen:
                    # Check CPAchecker alignments match computed alignments
                    run_cpachecker(CPA_COMMAND + [machine.cpa_option], filename + ".c")


ALIGNED_DIR = "test/programs/c_attributes/aligned"
CPA_COMMAND = (
    "scripts/cpa.sh -preprocess -default -benchmark -heap 1200M -nolog -noout ".split()
)
CPA_PRINTS = (
    "scripts/cpa.sh -preprocess -default -heap 1200M -nolog "
    "-setprop cfa.callgraph.export=false "
    "-setprop cfa.export=false "
    "-setprop cfa.exportPerFunction=false "
    "-setprop cfa.exportToC=true ".split()
)


def main():
    # cd CPAchecker root directory
    if os.path.isfile("../scripts/cpa.sh"):
        os.chdir("..")
    elif os.path.isfile("../../scripts/cpa.sh"):
        os.chdir("../..")

    if not os.path.isdir("test/programs"):
        raise Exception("directory test/programs not found or not a directory")
    if not os.path.isfile("scripts/cpa.sh") or not os.access("scripts/cpa.sh", os.X_OK):
        raise Exception("CPAchecker not found or not executable")

    args = parse_arguments()

    numeric_level = getattr(logging, args.log.upper(), None)
    if not isinstance(numeric_level, int):
        print("Invalid log level: %s" % args.log)
        sys.exit(1)
    logging.basicConfig(level=numeric_level)

    if args.print_nodes or args.print_graphs:
        tava = ExpressionGenerator(
            loop_depth=args.loop_depth,
            cycle_depth=args.cycle_depth,
            pointer_arithmetic=args.pointer_arithmetic,
            number_arithmetic=args.number_arithmetic,
        )
        tava.graph_ta_va()
        if args.print_nodes:
            tava.print_stats()
        else:
            tava.print_dot()
        pava = ExpressionGenerator(
            loop_depth=args.loop_depth,
            cycle_depth=args.cycle_depth,
            pointer_arithmetic=args.pointer_arithmetic,
            number_arithmetic=args.number_arithmetic,
        )
        pava.graph_pa_va()
        if args.print_nodes:
            pava.print_stats()
        else:
            pava.print_dot()
        sys.exit(0)

    if not args.only_gen and args.do_prints and args.cc_command is None:
        print("Programs with prints require a compiler to compare results.")
        sys.exit(1)
    if not args.main:
        print(
            "To generate (and check) programs for some number types specify --numbers. "
            "To generate (and check) programs for some pointer types specify --pointers."
        )
        sys.exit(1)
    args.main(args)


def parse_arguments():
    parser = argparse.ArgumentParser(
        description="Generate and check programs to test alignment attributes "
        "in CPAchecker."
    )
    parser.add_argument(
        "--log",
        dest="log",
        action="store",
        default="INFO",
        help="logging level for this script",
    )

    modes = parser.add_argument_group(title="modes of operation")
    modes = modes.add_mutually_exclusive_group()
    modes.add_argument(
        "--default",
        action="store_const",
        const=None,
        help="Generate programs, and run checks. If a compiler was specified, generate "
        "programs with static asserts and compile them to check the calculated "
        "alignments. Generate programs with asserts and run CPAchecker analysis "
        "to check that CPAchecker calculates same alignments.",
    )
    modes.add_argument(
        "-g",
        "--just-generate",
        dest="only_gen",
        action="store_true",
        help="Generate programs, but do not run anything (do not compile or analyze "
        "generated programs).",
    )
    modes.add_argument(
        "--print-nodes",
        dest="print_nodes",
        action="store_true",
        help="Construct graphs for --numbers and --pointers and print how many expressions each "
        "node has. Do not generate programs.",
    )
    modes.add_argument(
        "--print-graphs",
        "--dot",
        dest="print_graphs",
        action="store_true",
        help="Construct graphs for --numbers and --pointers and print them in .dot Graphviz "
        "format. Do not generate programs.",
    )

    progs = parser.add_argument_group(title="program generation options")
    parse_compiler_args(progs)
    progs.add_argument(
        "--prints",
        "--use-prints",
        dest="do_prints",
        action="store_true",
        help="Instead of asserts, generate prints and compare output of compiler "
        "and CPAchecker.",
    )
    progs.add_argument(
        "--all-alignments",
        dest="all_alignments",
        action="store_true",
        help="Check types with no __aligned__() attribute, attribute with no clause, "
        "and with 1, 2, 4, 8, 16, 32, 64, and __BIGGEST_ALIGNMENT__ as clause. "
        "Default is to check type with no attribute, and two nearest "
        "alignments.",
    )
    types = progs.add_mutually_exclusive_group()
    types.add_argument(
        "--numbers",
        dest="main",
        action="store_const",
        const=check_numbers,
        help="Check alignments for some number types.",
    )
    types.add_argument(
        "--pointers",
        dest="main",
        action="store_const",
        const=check_pointers,
        help="Check alignments for some pointer types.",
    )

    parse_graph_options(parser)

    return parser.parse_args()


def parse_graph_options(parser):
    graph_opts = parser.add_argument_group(title="graph options")
    graph_opts.add_argument(
        "--loop-depth",
        dest="loop_depth",
        type=int,
        action="store",
        default=2,
        help="How many times to apply operators that make expressions of same "
        "alignment, e.g. +0 for some pointer p: 2 means that 'p+0' and 'p+0+0' "
        "will be added when 'p' occurs.",
    )
    graph_opts.add_argument(
        "--cycle-depth",
        dest="cycle_depth",
        type=int,
        action="store",
        default=2,
        help="How many times to traverse cycle on a graph. For cycle of two edges, "
        "address-of and pointer dereference, depth 1 means that 'v', '&v', '*&v' "
        "will be added, and depth 2 means '&*&v' and '*&*&v' will be added too.",
    )
    graph_opts.add_argument(
        "--pointer-arithmetic",
        dest="pointer_arithmetic",
        action="store_true",
        help="For pointer expressions p add loops 'p+0', 'p+zero' where possible.",
    )
    graph_opts.add_argument(
        "--number-arithmetic",
        dest="number_arithmetic",
        action="store_true",
        help="For number expressions v add loops and edges for 'v+0', 'v+zero' "
        "where possible.",
    )


def parse_compiler_args(parser):
    compilers = parser.add_mutually_exclusive_group()
    # sana = (
    #     "-fsanitize=address -fsanitize=pointer-compare "
    #     "-fsanitize=pointer-subtract -fsanitize=leak "
    # )
    sans = "-fsanitize=shadow-call-stack "
    # sant = "-fsanitize=thread "
    # sanu = "-fsanitize=undefined "
    strict = "-std=c11 -Wall -Werror -Wno-unused-value -Wno-format "  # + sana + sanu
    strict2 = (
        sans + "-Wno-gnu-alignof-expression "
        "-Wno-sizeof-array-decay "
        "-Wno-address-of-packed-member "
    )
    compilers.add_argument(
        "--gcc",
        dest="cc_command",
        action="store_const",
        const=("gcc " + strict).split(),
        help="Use GCC to check testing model. Generate program with static asserts and "
        "try to compile it with GCC.",
    )
    compilers.add_argument(
        "--clang",
        dest="cc_command",
        action="store_const",
        const=("clang " + strict + strict2).split(),
        help="Use Clang to check testing model. Generate program with static asserts "
        "and try to compile it with Clang.",
    )


if __name__ == "__main__":
    main()
