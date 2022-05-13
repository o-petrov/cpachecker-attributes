# This file is part of CPAchecker,
# a tool for configurable software verification:
# https://cpachecker.sosy-lab.org
#
# SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
#
# SPDX-License-Identifier: Apache-2.0


"""Module with C number, pointer and array types."""


from .misc import Alignment, NonScalarTypeException, Variable


class CType:
    """
    Some C Type. All C types can have typeid (long long, struct s, typedef name; pointer
    and array types, anon structs and unions don't have one unless typedef'd). All C
    types can have alignment specified in declaration.

    Const and volatile qualifiers are not considered. If type was typedef'd, it has new
    typeid and declaration field with the string of according declaration included.
    """

    def __init__(self, typeid: str, align: Alignment = Alignment.NoAttr):
        self.typeid = typeid
        self.align = align
        self.declaration = None

    def as_scalar(self):
        """
        Return number, pointer or array as pointer type. If it is some other type, raise
        Exception.

        :rtype: Number | Pointer
        """
        raise NonScalarTypeException(self)

    def declare(self, name, align, init=None, as_string=False):
        """
        Declare a variable of this C type. Return a string of the declaration or the
        declared variable.

        :param str name: name of the variable
        :param Alignment align: alignment attribute in the declaration
        :param Expression init: initializer of the variable
        :param bool as_string: if to return just a declaration string, not a Variable
        :rtype: str | Variable
        """
        d = " ".join((self.typeid, name, align.attr))
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d, init=init)


class Void(CType):
    """C void type. Only can be used as part of pointer or function type."""

    def __init__(self):
        super().__init__("void")

    def declare(self, name, align, init=None, as_typedef=False):
        raise TypeError("cannot declare variables of void C type")


class Number(CType):
    """Any C type with bare number domain: bool, int, size_t."""

    def __init__(self, typeid, *, align=Alignment.NoAttr):
        """
        :param str typeid: name or typedef alias for the type
        :param Alignment align: alignment attribute (can be specified for numbers only
            in typedefs)
        """
        super().__init__(typeid, align)
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

    def __init__(self, ref_type, typeid=None, align=Alignment.NoAttr):
        """
        :param CType ref_type: the type pointer will reference
        :param str typeid: name of pointer type, none if was not typedef'd
        :param Alignment align: alignment attribute, can be specified in typedef or
            after asterisk
        """
        super().__init__(typeid, align)
        self.ref_type = ref_type
        self.is_scalar = True

    def declare(self, name, align, init=None, as_string=False):
        if self.typeid:
            d = " ".join((self.typeid, name, align.attr))
        elif isinstance(self.ref_type, Array):
            d = self.ref_type.declare("(* %s)" % name, align, as_string=True)
        elif isinstance(self.ref_type, Void):
            d = " ".join((self.ref_type.typeid, "*", name, align.attr))
        else:
            d = self.ref_type.declare("* " + name, align, as_string=True)
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d, init=init)

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

    def declare(self, name, align, init=None, as_string=False):
        if self.typeid:
            d = " ".join((self.typeid, name, align.attr))
        else:
            d = self.ref_type.declare(name + "[%s]" % self.size, align, as_string=True)
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d, init=init)

    def as_scalar(self):
        """Convert array type to pointer type and return it."""
        return Pointer(self.ref_type)
