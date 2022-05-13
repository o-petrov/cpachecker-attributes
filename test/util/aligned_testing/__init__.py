# This file is part of CPAchecker,
# a tool for configurable software verification:
# https://cpachecker.sosy-lab.org
#
# SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
#
# SPDX-License-Identifier: Apache-2.0


"""
Package for generating programs to test CPAchecker alignment calculation. Programs
consist of asserts or prints for exressions, that are generated for a single variable,
see ``graph`` module.

Expressions and operators are defined in ``expressions`` module, Number, pointer and
array C types are defined in ``ctypes``, two machine models (64- and 32-bit) are defined
in ``machines``.
"""


# import glob
# import os
import sys

sys.dont_write_bytecode = True  # prevent creation of .pyc files
# for egg in glob.glob(
#         os.path.join(
#             os.path.dirname(__file__), os.pardir, "lib", "python-benchmark", "*.whl"
#         )
# ):
#     sys.path.insert(0, egg)
