# This file is part of CPAchecker,
# a tool for configurable software verification:
# https://cpachecker.sosy-lab.org
#
# SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
#
# SPDX-License-Identifier: Apache-2.0


"""
Module with two machines described: 64- and 32-bits (match ``-m64`` and ``-m32`` GCC
target options).
"""


from .misc import Alignment
from .ctypes import CType, Void, Number, Pointer, Array


class Machine:
    """
    A machine model holds sizes and alignments for basic C types and computes sizes and
    alignments for other types.
    """

    def __init__(self, *, name, cpa_option, gcc_option, clang_option, **kwargs):
        self.name = name
        self.cpa_option = cpa_option
        self.gcc_option = gcc_option
        self.clang_option = clang_option

        # types
        self.void = kwargs.pop("void")
        self.align_void = kwargs.pop("align_void", self.void)

        self.bool = kwargs.pop("bool")
        self.align_bool = kwargs.pop("align_bool", self.bool)

        self.pointer = kwargs.pop("pointer")
        self.align_pointer = kwargs.pop("align_pointer", self.pointer)

        # integers
        self.char = kwargs.pop("char")
        self.align_char = kwargs.pop("align_char", self.char)

        self.short = kwargs.pop("short")
        self.align_short = kwargs.pop("align_short", self.short)
        assert self.short >= self.char and self.align_short >= self.align_char

        self.int = kwargs.pop("int")
        self.align_int = kwargs.pop("align_int", self.int)
        assert self.int >= self.short and self.align_int >= self.align_short

        self.long = kwargs.pop("long")
        self.align_long = kwargs.pop("align_long", self.long)
        assert self.long >= self.int and self.align_long >= self.align_int

        self.llong = kwargs.pop("llong")
        self.align_llong = kwargs.pop("align_llong", self.llong)
        assert self.llong >= self.long and self.align_llong >= self.align_long

        # floats
        self.float = kwargs.pop("float")
        self.align_float = kwargs.pop("align_float", self.float)

        self.double = kwargs.pop("double")
        self.align_double = kwargs.pop("align_double", self.double)
        assert self.double >= self.float and self.align_double >= self.align_float

        self.ldouble = kwargs.pop("ldouble")
        self.align_ldouble = kwargs.pop("align_ldouble", self.ldouble)
        assert self.ldouble >= self.double and self.align_ldouble >= self.align_double

        self.align_max = kwargs.pop(
            "align_max", max(self.align_ldouble, self.align_llong, self.align_pointer)
        )
        assert not kwargs

    def align_of(self, a):
        """
        Convert alignment attribute to actual alignment, or return ``None``, if there is
        no alignment forced by attribute.

        :type a: None | str | Alignment
        :rtype: int | None
        """
        if a is None:
            a = Alignment.NoAttr
        if isinstance(a, str):
            a = Alignment.from_attr(a)
        if not isinstance(a, Alignment):
            raise TypeError("a=%s is of type %s" % (a, type(a)))

        if isinstance(a.code, int):
            return a.code
        elif a.code in "eb":
            return self.align_max
        else:
            return None

    def size_align_of(self, t):
        """
        Return pair of (size, alignment) for given type on this machine.

        :type t: CType
        :rtype: (int, int)
        """
        if isinstance(t, Array):
            sb, ab = self.size_align_of(t.ref_type)
            return sb * int(t.size), self.align_of(t.alignment) or ab
        elif isinstance(t, Pointer):
            return self.pointer, self.align_of(t.alignment) or self.align_pointer
        elif isinstance(t, Void):
            return self.void, self.align_void
        elif isinstance(t, Number):
            align = self.align_of(t.alignment) or self.__getattribute__(
                "align_" + t.typenick
            )
            return self.__getattribute__(t.typenick), align
        else:
            raise NotImplementedError("C type %s is unsupported" % t)


# little endian
machine_models = [
    Machine(
        name="linux32",
        cpa_option="-32",
        gcc_option="-m32",
        clang_option="-m32",
        char=1,
        short=2,
        int=4,
        long=4,
        llong=8,
        float=4,
        double=8,
        ldouble=12,
        void=1,
        bool=1,
        pointer=4,
        align_llong=4,
        align_double=4,
        align_ldouble=4,
        align_max=16,
    ),
    Machine(
        name="linux64",
        cpa_option="-64",
        gcc_option="-m64",
        clang_option="-m64",
        char=1,
        short=2,
        int=4,
        long=8,
        llong=8,
        float=4,
        double=8,
        ldouble=16,
        void=1,
        bool=1,
        pointer=8,
        align_max=16,
    ),
]
"""All described machines. Check alignment for each of them."""
