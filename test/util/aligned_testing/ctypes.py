# This file is part of CPAchecker,
# a tool for configurable software verification:
# https://cpachecker.sosy-lab.org
#
# SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
#
# SPDX-License-Identifier: Apache-2.0


"""Module with C number, pointer and array types."""

from typing import NamedTuple

from .misc import Alignment, NonScalarTypeException, Variable


class Typedef(NamedTuple):
    typeid: str
    align: Alignment
    declaration: str


class CType:
    """
    A C Type. All C types can have typeid (long long, struct s, typedef name; pointer
    and array types, anon structs and unions don't have one unless typedef'd).

    Const and volatile qualifiers are not considered.

    All C types that can be declared can have alignment specified in declaration.
    To attach alignment attribute to a type, use `add_typedef` with such alignment.
    This works with number types too.

    All declarations end with ``';\\n'``.
    """

    def __init__(self, *, typeid=None, align=Alignment.NoAttr, declaration=""):
        """
        :param str typeid: use the string to declare variables
            (e.g. 'char', 'long int', 'struct s'; char* has no typeid itself)
        :param Alignment align: alignment attached to the type in its declaration
        :param declaration: use the string to declare the type itself in a program
        """
        self.default_typeid = typeid
        self._typedecl = [Typedef(typeid, align, declaration)]

    @property
    def declaration(self):
        """Full declaration of the type. Use in program text."""
        return "".join(td.declaration for td in self._typedecl)

    @property
    def alignment(self):
        """Last alignment attribute in effect."""
        for td in self._typedecl[::-1]:
            if td.align != Alignment.NoAttr:
                return td.align
        return Alignment.NoAttr

    def as_scalar(self):
        """
        Return number, pointer or array as pointer type. If it is some other type, raise
        Exception.

        :rtype: Number | Pointer
        """
        raise NonScalarTypeException(self)

    def declare(self, name, align, as_string=False):
        """
        Declare a variable of this C type. Return a string of the declaration or the
        declared variable.

        :param str name: name of the variable
        :param Alignment align: alignment attribute in the declaration
        :param bool as_string: if to return just a declaration string, not a Variable
        :rtype: str | Variable
        """
        d = " ".join((self._typedecl[-1].typeid, name, align.attr)).strip() + ";\n"
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d)

    def add_typedef(self, *, typeid="t", align=Alignment.NoAttr):
        """
        Typedef the type using ``align`` attribute.

        :param str typeid: new typedef name
        :param Alignment align: alignment attribute to add to the typedef
        """
        assert typeid not in (td.typeid for td in self._typedecl)
        self._typedecl.append(
            Typedef(
                typeid, align, "typedef " + self.declare(typeid, align, as_string=True)
            )
        )

    def remove_typedef(self):
        """Remove last typedef of the type."""
        assert len(self._typedecl) > 1
        self._typedecl.pop()


class Void(CType):
    """C void type. Only can be used as part of pointer or function type."""

    def __init__(self):
        super().__init__(typeid="void")

    def declare(self, name, align, as_string=False):
        raise TypeError("cannot declare variables of void C type")


class Number(CType):
    """Any C type with bare number domain: bool, int, size_t"""

    def __init__(self, typeid: str):
        super().__init__(typeid=typeid)
        self.is_scalar = True

    def as_scalar(self):
        return self


standard_types = {
    "VOID": Void(),
    "_T": Number("t"),
    "BOOL": Number("_Bool"),
    "SIZE": Number("size_t"),
    "PTRDIFF": Number("ptrdiff_t"),
}
"""Usual numeric (and void) types that C program can have."""

for rank in "char", "short", "int", "long", "long long":
    _name = rank.replace("long ", "l").upper()
    standard_types[_name] = Number(rank)
    standard_types["U" + _name] = Number("unsigned " + rank)

for rank in "float", "double", "long double":
    _name = rank.replace("long ", "l").upper()
    standard_types[_name] = Number(rank)
    standard_types["I" + _name] = Number(rank + " _Imaginary")
    standard_types["C" + _name] = Number(rank + " _Complex")


class Pointer(CType):
    """A pointer type, referencing some C type"""

    def __init__(self, ref_type, align=Alignment.NoAttr):
        """
        :param CType ref_type: the type pointer will reference
        :param Alignment align: alignment attribute, can be specified after asterisk
        """
        super().__init__(align=align)
        self.ref_type = ref_type
        self.is_scalar = True

    def declare(self, name, align, as_string=False):
        if len(self._typedecl) > 1:
            # just a typedef variable
            d = " ".join((self._typedecl[-1].typeid, name, align.attr)).strip() + ";\n"

        else:
            declarator = name
            if self._typedecl[0].align != Alignment.NoAttr:
                # alignment between asterisk and name
                declarator = self._typedecl[0].align.attr + " " + declarator

            # no typedef, so there is asterisk
            if declarator == "" or declarator[0] == "*":
                declarator = "*" + declarator
            else:
                declarator = "* " + declarator

            if isinstance(self.ref_type, Array):
                # add parenthesis
                d = self.ref_type.declare("(%s)" % declarator, align, as_string=True)
            elif isinstance(self.ref_type, Void):
                # cant use ref_type.declare
                d = (
                    " ".join(
                        (self.ref_type._typedecl[-1].typeid, declarator, align.attr)
                    ).strip()
                    + ";\n"
                )
            else:
                d = self.ref_type.declare(declarator, align, as_string=True)
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d)

    def as_scalar(self):
        return self


class Array(Pointer):
    """An array type. Multidimensional arrays are just arrays of arrays."""

    def __init__(self, ref_type, size):
        """
        :param CType ref_type: the type of the elements
        :param Expression size: the size of this array
        """
        super().__init__(ref_type)
        self.size = size

    def declare(self, name, align, as_string=False):
        if len(self._typedecl) > 1:
            d = " ".join((self._typedecl[-1].typeid, name, align.attr)).strip() + ";\n"
        else:
            d = self.ref_type.declare(name + "[%s]" % self.size, align, as_string=True)
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d)

    def as_scalar(self):
        """Convert array type to pointer type and return it."""
        return Pointer(self.ref_type)
