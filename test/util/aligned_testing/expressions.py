from enum import Enum, IntEnum
from .misc import RValueException
from .ctypes import Pointer, Number, Array, standard_types


class Expression:
    """Some C expression

    Every expression has an operator and a type.
    Kind of expression determines if it is lvalue."""

    def __init__(self, *, op, ctype, is_lvalue=False):
        """
        :type op: Operator
        :type ctype: CType
        :type is_lvalue: bool
        """
        self.op = op
        self.ctype = ctype
        self.is_lvalue = is_lvalue

    def __getitem__(self, item):
        """
        Array subscript expression

        :type item: Expression
        :return: ArrayExpression
        """
        if not isinstance(item.ctype, Number):
            raise TypeError(
                f"{item=} cannot be a subscript because its type is {item.ctype}"
            )
        if not isinstance(self.ctype, Pointer):
            raise TypeError(
                f"{self} cannot be subscripted because its type is {self.ctype}"
            )
        return ArrayExpression(primary=self, item=item, ctype=self.ctype.ref_type)

    def __rmul__(self, other):
        """
        Pointer dereference expression

        :type other: None
        """
        if not isinstance(self.ctype, Pointer):
            raise TypeError(f"not a pointer: {self}")
        return UnaryExpression(op=Operator.pointer, arg=self, ctype=self.ctype.ref_type)

    def __rand__(self, other):
        """
        Address of expression

        :type other: None
        """
        if not self.is_lvalue:
            raise RValueException(self)
        return UnaryExpression(
            op=Operator.addressof, arg=self, ctype=Pointer(self.ctype)
        )

    def __add__(self, other):
        """
        Sum expression, for any scalars.

        :type other: Expression
        """
        left = self.ctype.as_scalar()
        right = other.ctype.as_scalar()
        if isinstance(left, Pointer) and isinstance(right, Pointer):
            raise ValueError(
                f"{self} + {other} is wrong as both operands of + are pointers"
            )
        ctype = right if isinstance(right, Pointer) else left
        return BinaryExpression(op=Operator.add, left=self, right=other, ctype=ctype)


# Note that the associativity is meaningful for member access operators,
# even though they are grouped with unary postfix operators:
# a.b++ is parsed (a.b)++ and not a.(b++).
class OperatorKind(IntEnum):
    """Groups of operators by precedence. See
    https://en.cppreference.com/w/c/language/operator_precedence."""

    nop = (16,)
    postfix = (15,)
    postpar = (15,)
    prefix = (14,)
    prepar = (14,)
    multiplicative = (13,)
    additive = (12,)
    bit_shift = (11,)
    ordinal = (10,)
    equality = (9,)
    bit_and = (8,)
    bit_xor = (7,)
    bit_or = (6,)
    logic_and = (5,)
    logic_or = (4,)
    ternary = (3,)
    assign = (2,)
    comma = 1


class Operator(Enum):
    """Operator that can be used in C expression."""

    def __init__(self, operator, precedence, operation):
        """
        :type operator: str
        :type precedence: OperatorKind
        """
        self.operator = operator
        self.precedence = precedence
        self.associativity = (
            "right"
            if precedence
            in (OperatorKind.prefix, OperatorKind.ternary, OperatorKind.assign)
            else "left"
        )
        self.operation = operation

    def __str__(self):
        return self.operator

    nop = (None, OperatorKind.nop, lambda x: x)

    # post_inc = ('++', OperatorKind.postfix, Expression.post_inc)
    # post_dec = ('--', OperatorKind.postfix, Expression.post_dec)
    # call = ('()', OperatorKind.postpar, Expression.__call__)
    subscript = ("[]", OperatorKind.postpar, Expression.__getitem__)
    # dot = ('.', OperatorKind.postfix, Expression.dot)
    # arrow = ('->', OperatorKind.postfix, Expression.arrow)
    # compound_literal = ('{}', OperatorKind.postpar, None)  # TODO

    # pre_inc = ('++', OperatorKind.prefix, Expression.pre_inc)  # cannot be of type cast
    # pre_dec = ('--', OperatorKind.prefix, Expression.pre_dec)  # cannot be of type cast
    # pos = ('+', OperatorKind.prefix, Expression.__pos__)
    # neg = ('-', OperatorKind.prefix, Expression.__neg__)
    # logic_not = ('!', OperatorKind.prefix, Expression.logic_not)
    # bit_not = ('~', OperatorKind.prefix, Expression.__invert__)
    # cast = ('()', OperatorKind.prepar, Expression.cast)  # TODO
    pointer = ("*", OperatorKind.prefix, lambda x: Expression.__rmul__(x, None))
    addressof = ("&", OperatorKind.prefix, lambda x: Expression.__rand__(x, None))
    sizeof = ("sizeof", OperatorKind.prefix, None)  # cant be of type cast
    alignof = ("_Alignof", OperatorKind.prefix, None)

    # mul = ('*', OperatorKind.multiplicative, Expression.__mul__)
    # div = ('/', OperatorKind.multiplicative, Expression.__truediv__)
    # mod = ('%', OperatorKind.multiplicative, Expression.__mod__)
    add = ("+", OperatorKind.additive, Expression.__add__)
    # sub = ('-', OperatorKind.additive, Expression.__sub__)
    # bit_left = ('<<', OperatorKind.bit_shift, Expression.__lshift__)
    # bit_right = ('<<', OperatorKind.bit_shift, Expression.__rshift__)

    # lt = ('<', OperatorKind.ordinal, Expression.__lt__)
    # le = ('<=', OperatorKind.ordinal, Expression.__le__)
    # gt = ('>', OperatorKind.ordinal, Expression.__gt__)
    # ge = ('>=', OperatorKind.ordinal, Expression.__ge__)
    # eq = ('==', OperatorKind.equality, Expression.__eq__)
    # ne = ('!=', OperatorKind.equality, Expression.__ne__)

    # bit_and = ('&', OperatorKind.bit_and, Expression.__and__)
    # bit_xor = ('^', OperatorKind.bit_xor, Expression.__xor__)
    # bit_or = ('|', OperatorKind.bit_or, Expression.__or__)
    # logic_and = ('&&', OperatorKind.logic_and, Expression.logic_and)
    # logic_or = ('||', OperatorKind.logic_or, Expression.logic_or)

    # ternary = ('?:', OperatorKind.ternary, None)
    # assign = ('=', OperatorKind.assign, Expression.assign)

    # add_assign = ('+=', OperatorKind.assign, Expression.__iadd__)
    # sub_assign = ('-=', OperatorKind.assign, Expression.__isub__)
    # mul_assign = ('*=', OperatorKind.assign, Expression.__imul__)
    # div_assign = ('/=', OperatorKind.assign, Expression.__idiv__)
    # mod_assign = ('%=', OperatorKind.assign, Expression.__imod__)

    # bls_assign = ('<<=', OperatorKind.assign, Expression.__ilshift__)
    # brs_assign = ('>>=', OperatorKind.assign, Expression.__irshift__)
    # band_assign = ('&=', OperatorKind.assign, Expression.__iand__)
    # bxor_assign = ('^=', OperatorKind.assign, Expression.__ixor__)
    # bor_assign = ('|=', OperatorKind.assign, Expression.__ior__)

    # comma = (',', OperatorKind.comma, Experssion.comma)


class BinaryExpression(Expression):
    """Binary arithmetic or bit arithmetic expression"""

    def __init__(self, *, op, ctype, left, right):
        """
        :type op: Operator
        :type ctype: CType
        :type left: Expression
        :type right: Expression
        """
        super().__init__(op=op, ctype=ctype)
        self.left = left
        self.right = right

    def __str__(self):
        left = str(self.left)
        if (
            self.op.precedence > self.left.op.precedence
            or self.op.precedence == self.left.op.precedence
            and self.op.associativity == "right"
        ):
            left = "(" + left + ")"
        right = str(self.right)
        if (
            self.op.precedence > self.right.op.precedence
            or self.op.precedence == self.right.op.precedence
            and self.op.associativity == "left"
        ):
            right = "(" + right + ")"
        return left + f" {self.op} " + right


class UnaryExpression(Expression):
    """Unary expression, as address-of"""

    def __init__(self, *, op, arg, ctype):
        """
        :type op: Operator
        :type arg: Expression
        :type ctype: CType
        """
        super().__init__(op=op, ctype=ctype, is_lvalue=op == Operator.pointer)
        self.arg = arg

    def __str__(self):
        arg = str(self.arg)
        if self.op.precedence > self.arg.op.precedence or self.op in (
            Operator.sizeof,
            Operator.alignof,
        ):
            arg = "(" + arg + ")"
        if self.op.precedence == OperatorKind.prefix:
            return str(self.op) + arg
        if self.op.precedence == OperatorKind.postfix:
            return arg + str(self.op)
        raise ValueError(self.op)


class ArrayExpression(Expression):
    def __init__(self, *, primary, item, ctype):
        """
        :type primary: Expression
        :type item: Expression
        :type ctype: CType
        """
        super().__init__(op=Operator.subscript, ctype=ctype, is_lvalue=True)
        self.primary = primary
        self.item = item

    def __str__(self):
        primary = str(self.primary)
        if Operator.subscript.precedence > self.primary.op.precedence:
            primary = "(" + primary + ")"

        item = str(self.item)
        return primary + self.op.operator[0] + item + self.op.operator[1]


class LiteralExpression(Expression):
    def __init__(self, value):
        """
        :type value: int
        """
        if isinstance(value, int):
            t = standard_types["INT"]
        elif isinstance(value, str):
            t = Array(standard_types["CHAR"], LiteralExpression(len(value) + 1))
        else:
            raise TypeError(f"unknown type of literal {value}: {type(value)}")
        super().__init__(op=Operator.nop, ctype=t, is_lvalue=isinstance(t, Array))
        self.value = value

    def __str__(self):
        return str(self.value)


class IdentifierExpression(Expression):
    """Any name-expression"""

    def __init__(self, name: str, ctype=None, is_lvalue=False):
        """:type ctype: CType"""
        super().__init__(op=Operator.nop, ctype=ctype, is_lvalue=is_lvalue)
        self.name = name

    def __str__(self):
        return self.name


class VariableNameExpression(IdentifierExpression):
    """Expression that is a name of a variable."""

    def __init__(self, var):
        """:type var: Variable"""
        super().__init__(var.name, var.ctype, True)
        self.var = var


class CTypeIdExpression(IdentifierExpression):
    """Expression that is a type name (e.g. in sizeof expression)."""

    def __init__(self, ctype):
        """:type ctype: CType"""
        super().__init__(ctype.typeid, ctype)
