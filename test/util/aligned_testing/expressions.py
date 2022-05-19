# This file is part of CPAchecker,
# a tool for configurable software verification:
# https://cpachecker.sosy-lab.org
#
# SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
#
# SPDX-License-Identifier: Apache-2.0


"""
Module with C operators and expressions for address-of, pointer dereference, array
subscript and sum.
"""


from enum import Enum, IntEnum
from .misc import RValueException
from .ctypes import CType, Pointer, Number, Array, standard_types


class Expression:
    """
    Some C expression

    Every expression has an operator and a type. Kind of expression determines if it is
    lvalue. Every expression is printable (can be converted to ``str``).
    """

    def __init__(self, *, op, ctype, is_lvalue=False):
        """
        :param Operator op: operator of this expression
        :param CType ctype: type of this expression
        :param bool is_lvalue:
        """
        self.op = op
        self.ctype = ctype
        self.is_lvalue = is_lvalue

    def __getitem__(self, item):
        """
        Array subscript expression

        :type item: Expression
        """
        if not isinstance(item.ctype, Number):
            raise TypeError(
                "%s cannot be a subscript because its type is %s" % (item, item.ctype)
            )
        if not isinstance(self.ctype, Pointer):
            raise TypeError(
                "%s cannot be subscripted because its type is %s" % (self, self.ctype)
            )
        return ArrayExpression(primary=self, item=item, ctype=self.ctype.ref_type)

    def __rmul__(self, other):
        """
        Pointer dereference expression

        :type other: None
        """
        if not isinstance(self.ctype, Pointer):
            raise TypeError("not a pointer: %s" % self)
        return UnaryExpression(op=Operator.pointer, arg=self, ctype=self.ctype.ref_type)

    def __rand__(self, other):
        """
        Address-of expression

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
                "%s + %s is wrong as both operands of + are pointers" % (self, other)
            )
        ctype = right if isinstance(right, Pointer) else left
        return BinaryExpression(op=Operator.add, left=self, right=other, ctype=ctype)


# Note that the associativity is meaningful for member access operators,
# even though they are grouped with unary postfix operators:
# a.b++ is parsed (a.b)++ and not a.(b++).
class OperatorKind(IntEnum):
    """
    Groups of operators by precedence. See
    https://en.cppreference.com/w/c/language/operator_precedence.
    """

    nop = 16
    postfix = 15
    postpar = 15
    prefix = 14
    prepar = 14
    multiplicative = 13
    additive = 12
    bit_shift = 11
    ordinal = 10
    equality = 9
    bit_and = 8
    bit_xor = 7
    bit_or = 6
    logic_and = 5
    logic_or = 4
    ternary = 3
    assign = 2
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
    # compound_literal = ('{}', OperatorKind.postpar, None)

    # pre_inc = ('++', OperatorKind.prefix, Expression.pre_inc)  # cant be of type cast
    # pre_dec = ('--', OperatorKind.prefix, Expression.pre_dec)  # cant be of type cast
    # pos = ('+', OperatorKind.prefix, Expression.__pos__)
    # neg = ('-', OperatorKind.prefix, Expression.__neg__)
    # logic_not = ('!', OperatorKind.prefix, Expression.logic_not)
    # bit_not = ('~', OperatorKind.prefix, Expression.__invert__)
    # cast = ('()', OperatorKind.prepar, Expression.cast)
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
    """Binary arithmetic expression"""

    def __init__(self, *, op, ctype, left, right):
        """
        :param Operator op: binary arithmetic operator of the expression
        :param CType ctype: type of the expression
        :param Expression left: left operand
        :param Expression right: right operand
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
        return left + " " + self.op.operator + " " + right


class UnaryExpression(Expression):
    """Unary expression, as address-of or pointer dereference."""

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
    """Array subscript expression"""

    def __init__(self, *, primary, item, ctype):
        """
        :param Expression primary: the array to subscript, before brackets
        :param Expression item: the index, inside brackets
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
    """Number or string literal"""

    def __init__(self, value):
        """:type value: int"""
        if isinstance(value, int):
            t = standard_types["INT"]
        elif isinstance(value, str):
            t = Array(standard_types["CHAR"], LiteralExpression(len(value) + 1))
        else:
            raise TypeError("unknown type of literal %s: %s" % (value, type(value)))
        super().__init__(op=Operator.nop, ctype=t, is_lvalue=isinstance(t, Array))
        self.value = value

    def __str__(self):
        return str(self.value)

    def __int__(self):
        if isinstance(self.value, int):
            return self.value
        raise TypeError("literal %s can not be converted to int" % self.value)


class IdentifierExpression(Expression):
    """Any name-expression, a variable name or a type id."""

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
