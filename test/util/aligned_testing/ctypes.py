from .misc import Alignment, NonScalarTypeException, Variable


class CType:
    """Some C Type. All C types can have typeid (long long, struct s, typedef name;
    pointer and array types, anon structs and unions don't have one unless typedef'd).
    All C types can have alignment specified in declaration."""

    def __init__(self, typeid: str, align: Alignment = Alignment.NoAttr):
        self.typeid = typeid
        self.align = align
        self.declaration = None

    def as_scalar(self):
        """Return number, pointer or array as pointer type.
        If it is some other type, raise Exception.

        :rtype: Number | Pointer"""
        raise NonScalarTypeException(self)

    def declare(self, name, align, init=None, as_string=False):
        """
        Declare a variable of this C type.
        Return a string of the declaration or the declared variable.

        :type name: str
        :type align: Alignment
        :type init: Expression
        :type as_string: bool
        :return: Union[str, Variable]
        """
        d = " ".join((self.typeid, name, align.attr))
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d, init=init)


class Void(CType):
    def __init__(self):
        super().__init__("void")

    def declare(self, name, align, init=None, as_typedef=False):
        raise TypeError("cannot declare variables of void C type")


class Number(CType):
    """Any C type with bare number domain: bool, int, size_t"""

    def __init__(self, typeid, *, align=Alignment.NoAttr):
        """
        :type typeid: str
        :type kind: NumberKind | None
        :type rank: NumberRank | None
        :type align: Alignment
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

for rank in "char", "short", "int", "long", "long long":
    name = rank.replace("long ", "l").upper()
    standard_types[name] = Number(rank)
    standard_types["U" + name] = Number("unsigned " + rank)

for rank in "float", "double", "long double":
    name = rank.replace("long ", "l").upper()
    standard_types[name] = Number(rank)
    standard_types["I" + name] = Number(rank + " _Imaginary")
    standard_types["C" + name] = Number(rank + " _Complex")


class Pointer(CType):
    """A pointer type, referencing some C type"""

    def __init__(
        self, ref_type: CType, typeid: str = None, align: Alignment = Alignment.NoAttr
    ):
        super().__init__(typeid, align)
        self.ref_type = ref_type
        self.is_scalar = True

    def declare(self, name: str, align: Alignment, init=None, as_string=False):
        if self.typeid:
            d = " ".join((self.typeid, name, align.attr))
        elif isinstance(self.ref_type, Array):
            d = self.ref_type.declare(f"(* {name})", align, as_string=True)
        elif isinstance(self.ref_type, Void):
            d = " ".join((self.ref_type.typeid, "*", name, align.attr))
        else:
            d = self.ref_type.declare(f"* {name}", align, as_string=True)
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d, init=init)

    def as_scalar(self):
        return self


class Array(Pointer):
    """An array type"""

    def __init__(self, ref_type, size):
        """
        :type ref_type: CType
        :type size: Expression
        """
        super().__init__(ref_type)
        self.size = size

    def declare(self, name, align, init=None, as_string=False):
        if self.typeid:
            d = " ".join((self.typeid, name, align.attr))
        else:
            d = self.ref_type.declare(f"{name}[{self.size}]", align, as_string=True)
        if as_string:
            return d
        return Variable(name=name, align=align, ctype=self, declaration=d, init=init)

    def as_scalar(self):
        return Pointer(self.ref_type)
