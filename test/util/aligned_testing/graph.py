#  This file is part of CPAchecker,
#  a tool for configurable software verification:
#  https://cpachecker.sosy-lab.org
#
#  SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
#
#  SPDX-License-Identifier: Apache-2.0


"""
Generate expressions and text to check their alignment.

1. No assigns and ++/--, as CPAchecker simplifies it to lhs.
2. TODO expressions of two+ varibles?
"""

from .misc import Alignment, Variable
from .ctypes import CType, Pointer, standard_types
from .expressions import (
    Expression,
    Operator,
    BinaryExpression,
    LiteralExpression,
    VariableNameExpression,
)


class PartialExpression:
    """A partially substituted binary expression"""

    def __init__(self, *, op, left=None, right=None):
        """
        :type op: Operator
        :type left: Expression
        :type right: Expression
        """
        self.op = op
        self.left = left
        self.right = right

    def __call__(self, expr: Expression) -> BinaryExpression:
        if self.left is None and self.right is None:
            return self.op.operation(expr)
        return self.op.operation(self.left or expr, self.right or expr)


class Node:
    """
    A node in the graph (see ``ExpressionGenerator``) represents all expressions with
    same alignment. It can be viewed as node class for a graph where each expression was
    its own node. On the other hand, an expression can be viewed as a path in the graph,
    starting always from node for the variable, and ending in node with right alignment.
    """

    variable = lambda v: v  # noqa E731
    a_pointer = lambda v: Pointer(None)  # noqa E731
    typeof = lambda v: v.ctype  # noqa E731
    ref_type = lambda v: v.ctype.ref_type  # noqa E731
    pointer_to_ref_type = lambda v: Pointer(v.ctype.ref_type)  # noqa E731

    def __init__(self, align_class, loops, loop_depth=2):
        """
        :param align_class: maps declared variable to variable or type representing size
        and alignment with respect to expressions of this node :type align_class:
        (Variable | CType) -> Variable | CType :param loops: (pseudo-) unary operators
        for loops on every node of this class :type loops: list[(Expression) ->
        Expression] :param loop-depth: how many ops from loop operators to apply at the
        same time at max
        """
        self.align_class = align_class
        self.loops = [lambda x: x] + loops
        self.loop_depth = loop_depth
        self.expressions = []

    def extend(self, vs):
        """
        Extend node's expressions with ``vs`` using loops. Return newly added
        expressions.

        :type vs: list[Expression] :rtype: list[Expression]
        """
        result = []
        for v in vs:
            loop_vs = [v]
            for _ in range(self.loop_depth):
                loop_vs = [op(v) for v in loop_vs for op in self.loops]
            result.extend(loop_vs)
        self.expressions.extend(result)
        return result


class ExpressionGenerator:
    """
    Generate expressions for variable v to check alignment rules.

    A node is all expressions that have the same alignment rules. So ``&v`` and ``&*&v``
    are in the same nodes, but ``&v`` and ``&v+zero`` can be in different nodes if
    ``*&v`` and ``*(&v+zero)`` have different alignment.

    An edge is applying some (pseudo-)operators to a node, e.g.

    ( v )--[ +0, +z ]->( v+0, v+z )

    ( p, p+0 )--[ *, [0] ]->( *p, *(p+0), p[0], (p+0)[0] ).

    Operators are coded with one character, so `ov` means ``v+0``, `Ov` means ``v[0]``,
    `zv` means ``v+zero``, `Zv` means ``v[zero]``.
    """

    __l0 = LiteralExpression(0)
    __l1 = LiteralExpression(1)
    __zero = VariableNameExpression(
        standard_types["INT"].declare("zero", Alignment.NoAttr, __l0)
    )
    __unit = VariableNameExpression(
        standard_types["INT"].declare("unit", Alignment.NoAttr, __l1)
    )

    __add0 = PartialExpression(op=Operator.add, right=__l0)
    __addz = PartialExpression(op=Operator.add, right=__zero)
    __add1 = PartialExpression(op=Operator.add, right=__l1)
    __addu = PartialExpression(op=Operator.add, right=__unit)

    __get0 = PartialExpression(op=Operator.subscript, right=__l0)
    __getz = PartialExpression(op=Operator.subscript, right=__zero)
    __get1 = PartialExpression(op=Operator.subscript, right=__l1)
    __getu = PartialExpression(op=Operator.subscript, right=__unit)

    def __init__(self, cycle_depth=1, loop_depth=1):
        self.__node = {}
        """:type: dict[str, Node]"""
        self.__graph = None
        self.cycle_depth = cycle_depth
        self.loop_depth = loop_depth

    def __cycle2(self, from_, to_, ops1, ops2, depth=None):
        """
        Add two edges in cycle ``from_`` -- ``to_`` -- ``from_``. Nodes with cycles can
        be populated infinitely, so cap this process using ``depth``.

        :param str from_: title of a node
        :param str to_: title of another node
        :param ops1: (pseudo-) unary operations on first edge
        :type ops1: list[(Expression) -> Expression]
        :param ops2: (pseudo-) unary operations on second edge
        :type ops2: list[(Expression) -> Expression]
        :param int depth: how many times traverse the full cycle
        :rtype: None
        """
        if depth is None:
            depth = self.cycle_depth
        n1s = from_
        for _ in range(depth):
            n2s = self.__edge(n1s, to_, ops1)
            n1s = self.__edge(n2s, from_, ops2)

    def __edge(self, from_, to_, ops):
        """
        Add expressions applying ``ops`` to expressions in ``from_``.

        :param from_: title of a node or newest expressions in the node
        :type from_: str | list[Expression]
        :param str to_: title of a node
        :param ops: Apply (pseudo-) unary operations to source expressions to get
            destination expressions.
        :type ops: list[(Expression) -> Expression]
        :return: new expressions in ``to_`` node
        :rtype: list[Expression]
        """
        if not isinstance(from_, list):
            from_ = self.__node[from_].expressions
        n2s = []
        for n1 in from_:
            for op1 in ops:
                if op1 is Operator.addressof.operation and not n1.is_lvalue:
                    # dont apply & to (v+0) even if it has same alignment rules as v
                    continue
                n2 = op1(n1)
                if n2 not in self.__node[to_].expressions:
                    n2s.append(n2)
        return self.__node[to_].extend(n2s)

    def graph_ta_va(self):
        """
        Generate expressions to check a variable `v` of arbitrary type. Expressions
        include taking address of `v` and dereferencing it in different ways.

        Type can be aligned with attribute in type declaration for struct or union, in
        typedef declaration, and after ``*`` in declarator. (CDT ignores the last case.)
        """
        if self.__graph == "ta va":
            return
        elif self.__graph is not None:
            raise Exception("Already filled graph for '%s' case" % self.__graph)
        self.__graph = "ta va"

        # operator sets for edges and loops
        plus0 = [self.__add0, self.__addz]
        deref0 = [Operator.pointer.operation, self.__get0]
        derefz = deref0 + [self.__getz]

        # setup nodes
        self.__node["v"] = Node(Node.variable, [], loop_depth=self.loop_depth)
        self.__node["&v"] = Node(
            Node.a_pointer, [self.__add0], loop_depth=self.loop_depth
        )
        self.__node["&v+z"] = Node(Node.a_pointer, plus0, loop_depth=self.loop_depth)
        self.__node["(&v)[z]"] = Node(Node.typeof, plus0, loop_depth=self.loop_depth)

        # all expressions derive from v
        self.__node["v"].extend(
            [
                VariableNameExpression(
                    standard_types["_T"].declare("v", Alignment.NoAttr)
                )
            ]
        )
        # add edges to &v: &v; *&v, O&v; &*&v...
        self.__cycle2("v", "&v", [Operator.addressof.operation], deref0)
        # add 1 edge: &v --> (&v)[zero]
        self.__edge("&v", "(&v)[z]", [self.__getz])
        # add 1 edge: &v --> &v + zero
        self.__edge("&v", "&v+z", [self.__addz])
        # add edges between: &v+z and (&v)[z]
        self.__cycle2("(&v)[z]", "&v+z", [Operator.addressof.operation], derefz)

        # add edges to v+0
        self.__edge("v", "(&v)[z]", plus0)

    def graph_pa_va(self):
        """
        Generate expressions to check a variable `v` of arbitrary pointer type.
        Expressions include taking address of `v` and dereferencing it in different
        ways, as in ``graph_ta_va``, but also dereferencing `v` itself.

        Type can be aligned with attribute in type declaration for struct or union, in
        typedef declaration, and after ``*`` in declarator. (CDT ignores the last case.)
        """
        if self.__graph == "pa va":
            return
        elif self.__graph is not None:
            raise Exception("Already filled graph for '%s' case" % self.__graph)
        self.__graph = "pa va"

        # operator sets for edges and loops
        plus0 = [self.__add0, self.__addz]
        deref0 = [Operator.pointer.operation, self.__get0]
        derefz = deref0 + [self.__getz]

        # setup nodes
        self.__node["v"] = Node(Node.variable, [], loop_depth=self.loop_depth)
        self.__node["&v"] = Node(
            Node.a_pointer,
            [self.__add0],
            loop_depth=self.loop_depth,
        )
        self.__node["&v+z"] = Node(Node.a_pointer, plus0, loop_depth=self.loop_depth)
        self.__node["(&v)[z]"] = Node(Node.typeof, plus0, loop_depth=self.loop_depth)
        self.__node["*v"] = Node(Node.ref_type, [], loop_depth=self.loop_depth)

        # all expressions derive from v
        self.__node["v"].extend(
            [
                VariableNameExpression(
                    Pointer(standard_types["_T"]).declare("v", Alignment.NoAttr)
                )
            ]
        )
        # add edges to &v: &v; *&v, O&v; &*&v...
        self.__cycle2("v", "&v", [Operator.addressof.operation], deref0)
        # add 1 edge: &v --> (&v)[zero]
        self.__edge("&v", "(&v)[z]", [self.__getz])
        # add 1 edge: &v --> &v + zero
        self.__edge("&v", "&v+z", [self.__addz])
        # add edges between: &v+z and (&v)[z]
        self.__cycle2("(&v)[z]", "&v+z", [Operator.addressof.operation], derefz)
        # add edges to v+0
        self.__edge("v", "(&v)[z]", plus0)
        # add edges to *v
        self.__edge("v", "*v", derefz)
        # edge from *v to &*v, &*&*v, ...
        self.__cycle2("*v", "(&v)[z]", [Operator.addressof.operation], derefz)

    def text_graph(self, *, mode, variable, machine):
        """
        Compose a program that checks previously generated expressions using asserts or
        prints.

        :param str mode: 'static asserts', 'asserts', or 'prints'
        :param Variable variable: variable all expressions will be derived from
        :param Machine machine: machine model to get expected alignment numbers
        :return: text of program
        """

        if self.__graph is None:
            raise Exception("no expressions to write, call self.graph... inbefore")

        text = "extern void abort( void );\n"
        if mode == "prints":
            # program is filled with prints only
            text += "extern int printf( const char *restrict format, ... );\n"
        elif mode == "asserts":
            text += "#include <assert.h>\n"
        elif mode != "static asserts":
            raise ValueError("wrong mode " + mode)

        td = variable.ctype.declaration + ";\n" if variable.ctype.declaration else ""
        text += (
            td + variable.declaration + ";\n"
            "int main() {\n"
            "int zero = 0;\n"
            "int unit = zero + 1;\n"
        )
        for title, node in self.__node.items():
            title = node.expressions[0]

            x = node.align_class(variable)
            if isinstance(x, Variable):
                v = x
                size, align = machine.size_align_of(v.ctype)
                align = machine.align_of(v.align) or align
            elif isinstance(x, CType):
                t = x
                size, align = machine.size_align_of(t)
            else:
                raise TypeError("unexpected type of x=%s: %s" % (x, type(x)))

            for expr in node.expressions:
                asserts = [
                    (
                        "sizeof(%s) == sizeof(%s)" % (expr, title),
                        "%s differs from %s by size" % (expr, title),
                    ),
                    (
                        "_Alignof(%s) == _Alignof(%s)" % (expr, title),
                        "%s differs from %s by align" % (expr, title),
                    ),
                    (
                        "_Alignof(%s) == %s" % (expr, align),
                        "align of %s differs from expected" % expr,
                    ),
                    (
                        "sizeof(%s) == %s" % (expr, size),
                        "size of %s differs from expected" % expr,
                    ),
                ]
                if mode == "prints":
                    text += (
                        'printf("%s\\ta:%%ld, s:%%ld\\n", _Alignof(%s), sizeof(%s));\n'
                        % (expr, expr, expr)
                    )
                elif mode == "static asserts":
                    text += ";\n".join(
                        '_Static_assert(%s, "%s")' % (check, message)
                        for check, message in asserts
                    )
                    text += ";\n"
                elif mode == "asserts":
                    text += ";\n".join(
                        "assert(%s)" % check for check, message in asserts
                    )
                    text += ";\n"
                else:
                    raise ValueError("unrecognised mode " + mode)

        text += "return unit - 1;\n}\n"
        return text
