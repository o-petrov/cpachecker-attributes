#  This file is part of CPAchecker,
#  a tool for configurable software verification:
#  https://cpachecker.sosy-lab.org
#
#  SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
#
#  SPDX-License-Identifier: Apache-2.0


"""Alignment, Variable and some exceptions."""


from enum import Enum


class NonScalarTypeException(TypeError):
    def __init__(self, ctype):
        """:type ctype: CType"""
        super().__init__(f"C type {ctype} is not a scalar type")


class RValueException(ValueError):
    def __init__(self, expr):
        """:type expr: Expression"""
        super().__init__(f"expression {expr} is not an lvalue")


class Alignment(Enum):
    """
    C alignment attribute.

    ``attr`` is the attribute string, ``code`` is a shorthand mark.
    """

    def __init__(self, code, attr):
        self.code = code
        self.attr = attr

    NoAttr = ("n", "")
    EmptyClause = "e", "__attribute__((__aligned__))"
    Biggest = "b", "__attribute__((__aligned__(__BIGGEST_ALIGNMENT__)))"
    One = 1, "__attribute__((__aligned__(1)))"
    Two = 2, "__attribute__((__aligned__(2)))"
    Four = 4, "__attribute__((__aligned__(4)))"
    Eight = 8, "__attribute__((__aligned__(8)))"
    Sixteen = 16, "__attribute__((__aligned__(16)))"
    ThirtyTwo = 32, "__attribute__((__aligned__(32)))"
    SixtyFour = 64, "__attribute__((__aligned__(64)))"

    @staticmethod
    def from_attr(attr: str):
        if not attr:
            return Alignment.NoAttr

        attr = attr.replace(" ", "")
        if not attr:
            return Alignment.NoAttr

        attr = attr.replace("__", "")
        assert attr.startswith("attribute((aligned") and attr[-2:] == "))"
        attr = attr[len("attribute((aligned") : -2]

        if attr == "" or attr == "()":
            return Alignment.EmptyClause

        assert attr[0] == "(" and attr[-1] == ")"
        attr = attr[1:-1]

        if attr == "BIGGEST_ALIGNMENT":
            return Alignment.Biggest

        alignment = int(attr)
        for a in Alignment.__members__.values():
            if alignment == a.code:
                return a
        raise ValueError("cant parse attr=" + str(attr))

    @classmethod
    def get_two_nearest(cls, number: int):
        """Return two nearest (but not equal) to the ``number`` alignments."""
        ints = [a for a in Alignment.__members__.values() if isinstance(a.code, int)]
        less = [a for a in ints if a.code < number]
        greater = [a for a in ints if a.code > number]
        if not less:  # no aligns less than number
            return Alignment.Two, Alignment.Four
        a1 = max(less, key=lambda a: a.code)
        if not greater:
            a2 = a1
            a1 = max((a for a in ints if a.code < a2.code), key=lambda a: a.code)
            return a1, a2
        a2 = min(greater, key=lambda a: a.code)
        return a1, a2


class Variable:
    """Some C variable."""

    def __init__(self, *, name, align=Alignment.NoAttr, ctype, declaration, init=None):
        """
        :type name: str :type align: Alignment :type ctype: CType :type declaration: str
        :type init: Expression
        """
        assert name and ctype.typeid != "void"
        self.name = name
        self.align = align
        self.ctype = ctype
        self.declaration = declaration
        self.value = init
