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


import logging
from .misc import Alignment, Variable
from .ctypes import CType, Pointer, standard_types, Number, Void
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

    @staticmethod
    def variable(v):
        return v

    @staticmethod
    def a_pointer(v):
        return Pointer(None)

    @staticmethod
    def typeof(v):
        return v.ctype

    @staticmethod
    def ref_type(v):
        return v.ctype.ref_type

    @staticmethod
    def pointer_to_ref_type(v):
        return Pointer(v.ctype.ref_type)

    def __init__(self, align_class, loops, loop_depth=2):
        """
        :param align_class: maps declared variable to variable or type representing size
            and alignment with respect to expressions of this node
        :type align_class: (Variable | CType) -> Variable | CType
        :param loops: (pseudo-) unary operators for loops on every node of this class
        :type loops: list[(Expression) -> Expression]
        :param loop-depth: how many ops from loop operators to apply at the same time at max
        """
        self.align_class = align_class
        self.loops = [lambda x: x] + loops
        self.loop_depth = loop_depth
        self.expressions = []

    def extend(self, vs):
        """
        Extend node's expressions with ``vs`` using loops. Return newly added
        expressions.

        :type vs: list[Expression]
        :rtype: list[Expression]
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

    v --{ e+0, e+zero }--> v+0, v+zero

    p, p+0 --{ *e, e[0] }--> *p, *(p+0), p[0], (p+0)[0]
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

    def __init__(
        self, *, cycle_depth, loop_depth, pointer_arithmetic, number_arithmetic
    ):
        self.__node = {}
        """:type: dict[str, Node]"""
        self.__edges = []
        """:type: list[tuple[str,str,Expression]]"""
        self.__graph = None
        self.cycle_depth = cycle_depth
        self.loop_depth = loop_depth
        self.pointer_arithmetic = pointer_arithmetic
        self.number_arithmetic = number_arithmetic

    def __graph_kind(self, variable: Variable) -> str:
        """
        Describe the graph needed for the variable in a short string. Use it to check if the
        graph is already constructed and can be used for another variable.
        """
        ctype = variable.ctype
        result = ""
        while isinstance(ctype, Pointer):
            result += "P"
            ctype = ctype.ref_type
        if isinstance(ctype, Number):
            result += "number" if self.number_arithmetic else "void"
        elif isinstance(ctype, Void):
            result += "void"
        else:
            raise ValueError(
                "variable %s of unexpected C type %s (%s occured)"
                % (variable, variable.ctype, ctype)
            )
        return result

    def __cycle2(self, from_, to_, ops1, ops2, depth=None):
        """
        Add two edges in cycle ``from_`` -- ``to_`` -- ``from_``. Nodes with cycles can
        be populated infinitely, so cap this process using ``depth``.

        This is enough to populate graph with reference--dereference cycles: even if two cycles
        have a common node, path with two address-of operators in a row is illegal, as ``&(&...)``
        expression is illegal in C.

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

        e = VariableNameExpression(
            Pointer(standard_types["INT"]).declare("e", Alignment.NoAttr)
        )
        exprs = ", ".join(str(op(e)) for op in ops2)
        self.__edges.append((to_, from_, exprs))

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
        if isinstance(from_, str):
            e = VariableNameExpression(
                Pointer(standard_types["INT"]).declare("e", Alignment.NoAttr)
            )
            exprs = ", ".join(str(op(e)) for op in ops)
            logging.debug("edge %s --{ %s }--> %s", from_, exprs, to_)
            self.__edges.append((from_, to_, exprs))
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
        result = self.__node[to_].extend(n2s)
        first_exprs = ", ".join(
            str(e) for e in (result[:3] + ["..."] if len(result) > 3 else result)
        )
        logging.debug("added %i expressions to %s: %s", len(result), to_, first_exprs)
        return result

    def __graph_variable(self, variable: VariableNameExpression):
        """
        Generate expressions to check a variable `v` of arbitrary type. Expressions
        include taking address of `v` and dereferencing it in different ways.

        See `__graph_pointer` for expressions that will added for pointer variables.
        """
        if self.__graph is not None:
            raise Exception("Already filled graph for '%s' case" % self.__graph)
        # operator sets for edges and loops
        plus0 = [self.__add0, self.__addz]
        deref0 = [Operator.pointer.operation, self.__get0]
        derefz = deref0 + [self.__getz]

        v_arithmetic = self.__do_arithmetics(variable.ctype)

        # setup nodes
        self.__node["v"] = Node(Node.variable, [], loop_depth=self.loop_depth)
        self.__node["&v"] = Node(
            Node.a_pointer,
            [self.__add0] if self.pointer_arithmetic else [],
            loop_depth=self.loop_depth,
        )
        self.__node["&v+z"] = Node(
            Node.a_pointer,
            plus0 if self.pointer_arithmetic else [],
            loop_depth=self.loop_depth,
        )
        self.__node["(&v)[z]"] = Node(
            Node.typeof,
            plus0 if v_arithmetic else [],
            loop_depth=self.loop_depth,
        )

        # all expressions derive from v
        self.__node["v"].extend([variable])
        # add edges to &v: &v; *&v, O&v; &*&v...
        self.__cycle2("v", "&v", [Operator.addressof.operation], deref0)
        # add 1 edge: &v --> (&v)[zero]
        self.__edge("&v", "(&v)[z]", [self.__getz])
        # add 1 edge: &v --> &v + zero
        self.__edge("&v", "&v+z", [self.__addz])
        # add edges between: &v+z and (&v)[z]
        self.__cycle2("(&v)[z]", "&v+z", [Operator.addressof.operation], derefz)

        # add edges to v+0
        if v_arithmetic:
            self.__edge("v", "(&v)[z]", plus0)

        if isinstance(variable.ctype, Pointer):
            self.__graph_pointer(variable, "(&v)[z]")

    def __do_arithmetics(self, ctype: CType):
        """Return whether to do arithmetics (plus) on the type."""
        if isinstance(ctype, Void):
            return False
        if isinstance(ctype, Number):
            return self.number_arithmetic
        if isinstance(ctype, Pointer):
            return self.pointer_arithmetic
        raise ValueError("unexpected C type %s" % ctype)

    def graph(self, variable):
        """
        Generate expressions to check a variable `v` of arbitrary type. Expressions
        include taking address of `v` and dereferencing it in different ways, and dereferencing
        `v` itself if it is of a pointer type. Expressions include `...+0` and `...+zero` where
        possible if appropriate ``--arithmetics`` options were specified.

        Type can be aligned with attribute in type declaration for struct or union, in
        typedef declaration, and after ``*`` in declarator. (CDT ignores the last case.)
        """
        if self.__graph == self.__graph_kind(variable):
            return
        elif self.__graph is not None:
            raise Exception("Already filled graph for '%s' case" % self.__graph)
        self.__graph_variable(VariableNameExpression(variable))
        self.__graph = self.__graph_kind(variable)

    def __graph_pointer(self, pointer, other_title=None):
        """
        Generate expressions to check dereference of a pointer.

        :param Expression pointer: an expression to be dereferenced
        :param str other_title: the title of existing node to add &*pointer to, in case it is not
            the same node as pointer
        """
        # operator sets for edges and loops
        plus0 = [self.__add0, self.__addz]
        deref0 = [Operator.pointer.operation, self.__get0]
        derefz = deref0 + [self.__getz]

        def referenced_type(pointer):
            return pointer.ctype.ref_type

        pointed = None * pointer
        self.__node[str(pointed)] = Node(
            referenced_type,
            plus0 if self.__do_arithmetics(pointed.ctype) else [],
            loop_depth=self.loop_depth,
        )

        if other_title:
            # add edge p -> *p in case p is different from &*p
            self.__edge(str(pointer), str(pointed), derefz)
            # cycle *p -> &*p -> *p
            self.__cycle2(
                str(pointed), other_title, [Operator.addressof.operation], derefz
            )
        else:
            # cycle p -> *p -> p in case p is same as &*p
            self.__cycle2(
                str(pointer), str(pointed), derefz, [Operator.addressof.operation]
            )

        # dereference pointed if possible...
        if isinstance(pointed.ctype, Pointer):
            self.__graph_pointer(pointed)  # &** p is *p

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

    def print_stats(self):
        """Print nodes and how many expressions they have."""
        print(self.__graph)
        for title, node in self.__node.items():
            print(title, node.align_class, len(node.expressions))

    def print_dot(self):
        """Print constructed graph in Graphviz .dot format."""
        print("digraph", self.__graph.replace(" ", "_"), "{")
        print('in [shape=none, label=""]')
        dot_node = {}
        for i_node, (title, node) in enumerate(self.__node.items()):
            dot_node[title] = "n" + str(i_node)
            align_class = str(node.align_class)
            align_class = align_class[
                align_class.rfind(".") + 1 : align_class.find(" at 0x")
            ]
            print(dot_node[title], '[label="%s\\n%s"]' % (title, align_class))
        print("in ->", dot_node["v"])
        for from_, to_, exprs in self.__edges:
            print(dot_node[from_], "->", dot_node[to_], '[label="%s"]' % exprs)
        print("}")
