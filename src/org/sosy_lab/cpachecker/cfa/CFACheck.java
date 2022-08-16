// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa;

import static com.google.common.base.Verify.verify;
import static org.sosy_lab.cpachecker.util.CFAUtils.enteringEdges;
import static org.sosy_lab.cpachecker.util.CFAUtils.leavingEdges;

import com.google.common.base.Joiner;
import com.google.common.base.VerifyException;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AStatement;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CArraySubscriptExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CBinaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CCastExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CCharLiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CComplexCastExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpressionStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CFieldReference;
import org.sosy_lab.cpachecker.cfa.ast.c.CImaginaryLiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIntegerLiteralExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CPointerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CUnaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.DefaultCExpressionVisitor;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.CFATerminationNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionReturnEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CAssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionSummaryStatementEdge;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.util.CFAUtils;

public class CFACheck {

  /**
   * Traverse the CFA and run a series of checks at each node
   *
   * @param pEntry Node to start traversal from (function entry)
   * @param pNodes Optional set of all nodes in the function CFA
   * @param pMachineModel model to get the size of types
   * @return true if all checks succeed
   * @throws VerifyException if not all checks succeed
   */
  public static boolean check(
      FunctionEntryNode pEntry, Set<CFANode> pNodes, MachineModel pMachineModel)
      throws VerifyException {

    for (CFANode n : pNodes) {
      verify(
          n.getFunction().equals(pEntry.getFunction()),
          "Node %s is not from same function as entry node %s",
          debugFormat(n),
          debugFormat(pEntry));
    }

    Set<CFANode> visitedNodes = new TreeSet<>();
    Deque<CFANode> waitingNodeList = new ArrayDeque<>();

    waitingNodeList.add(pEntry);
    while (!waitingNodeList.isEmpty()) {
      CFANode node = waitingNodeList.poll();

      if (visitedNodes.add(node)) {
        Iterables.addAll(
            waitingNodeList,
            CFAUtils.successorsOf(node)
                .filter(succ -> succ.getFunction().equals(node.getFunction())));
        // just to be sure to get ALL nodes.
        Iterables.addAll(
            waitingNodeList,
            CFAUtils.predecessorsOf(node)
                .filter(succ -> succ.getFunction().equals(node.getFunction())));

        // The actual checks
        isConsistentAsGraphNode(node);
        isConsistentAsCFANode(node, pMachineModel);
      }
    }

    verify(
        visitedNodes.equals(pNodes),
        "\n"
            + "Nodes in CFA but not reachable through traversal: %s\n"
            + "Nodes reached that are not in CFA: %s",
        Iterables.transform(Sets.difference(pNodes, visitedNodes), CFACheck::debugFormat),
        Iterables.transform(Sets.difference(visitedNodes, pNodes), CFACheck::debugFormat));
    return true;
  }

  /**
   * This method returns a lazy object where {@link Object#toString} can be called. In most cases we
   * do not need to build the String, thus we can avoid some overhead here.
   */
  private static Object debugFormat(CFANode node) {
    return new Object() {
      @Override
      public String toString() {
        // try to get some information about location from node
        FileLocation location = FileLocation.DUMMY;
        if (node.getNumEnteringEdges() > 0) {
          location = node.getEnteringEdge(0).getFileLocation();
        } else if (node.getNumLeavingEdges() > 0) {
          location = node.getLeavingEdge(0).getFileLocation();
        }
        return node.getFunctionName()
            + ":"
            + node
            + " ("
            + location
            + ") with edges\n"
            + Joiner.on('\n').join(CFAUtils.allEnteringEdges(node).transform(CFAEdge::toString))
            + "\n"
            + Joiner.on('\n').join(CFAUtils.allLeavingEdges(node).transform(CFAEdge::toString));
      }
    };
  }

  /**
   * Verify that the number of edges and their types match.
   *
   * @param pNode Node to be checked
   */
  private static void isConsistentAsCFANode(CFANode pNode, MachineModel pMachineModel) {

    if (pNode instanceof FunctionEntryNode) {
      verify(
          CFAUtils.enteringEdges(pNode).allMatch(e -> e instanceof FunctionCallEdge),
          "FunctionentryNode %s has entering edges other than FunctionCallEdge",
          debugFormat(pNode));
      verify(
          pNode.getNumLeavingEdges() == 1 && pNode.getLeavingEdge(0) instanceof BlankEdge,
          "FunctionEntryNode %s has wrong leaving edges",
          debugFormat(pNode));

    } else if (pNode instanceof FunctionExitNode) {
      // XXX there may be no entering edges if function does not return
      verify(
          CFAUtils.leavingEdges(pNode).allMatch(e -> e instanceof FunctionReturnEdge),
          "FunctionExitNode %s has leaving edges other than FunctionReturnEdge",
          debugFormat(pNode));

    } else if (pNode instanceof CFATerminationNode) {
      verify(
          pNode.getNumEnteringEdges() > 0,
          "CFATerminationNode %s has no entering edges",
          debugFormat(pNode));
      verify(
          pNode.getNumLeavingEdges() == 0,
          "CFATerminationNode %s is not a dead end",
          debugFormat(pNode));

    } else {
      // usual node or label node
      // check entering edges
      verify(pNode.getNumEnteringEdges() > 0, "%s has no entering edges", debugFormat(pNode));

      // check leaving edges
      switch (pNode.getNumLeavingEdges()) {
        case 0:
          throw new VerifyException("Dead end at node " + debugFormat(pNode));

        case 1:
          CFAEdge edge = pNode.getLeavingEdge(0);
          verify(
              !(edge instanceof AssumeEdge),
              "AssumeEdge does not appear in pair at node %s",
              debugFormat(pNode));
          verify(
              !(edge instanceof CFunctionSummaryStatementEdge),
              "CFunctionSummaryStatementEdge is not paired with CFunctionCallEdge at node %s",
              debugFormat(pNode));
          verify(
              (edge instanceof FunctionCallEdge) == (pNode.getLeavingSummaryEdge() != null),
              "FunctionCallEdge is not paired with FunctionsummaryEdge at node %s",
              debugFormat(pNode));

          checkEdge(edge, pMachineModel);
          break;

        case 2:
          CFAEdge edge1 = pNode.getLeavingEdge(0);
          CFAEdge edge2 = pNode.getLeavingEdge(1);
          // relax this assumption for summary edges
          if (edge1 instanceof CFunctionSummaryStatementEdge) {
            verify(
                edge2 instanceof CFunctionCallEdge,
                "CFunctionSummaryStatementEdge is not paired with CFunctionCallEdge at node %s",
                debugFormat(pNode));
            verify(
                pNode.getLeavingSummaryEdge() != null,
                "CFunctionSummaryStatementEdge is not paired with summary edge at node %s",
                debugFormat(pNode));
          } else if (edge2 instanceof CFunctionSummaryStatementEdge) {
            verify(
                edge1 instanceof CFunctionCallEdge,
                "CFunctionSummaryStatementEdge is not paired with CFunctionCallEdge at node %s",
                debugFormat(pNode));
            verify(
                pNode.getLeavingSummaryEdge() != null,
                "CFunctionSummaryStatementEdge is not paired with summary edge at node %s",
                debugFormat(pNode));
          } else {
            verify(
                (edge1 instanceof AssumeEdge) && (edge2 instanceof AssumeEdge),
                "Branching without conditions at node %s",
                debugFormat(pNode));
            verify(
                pNode.getLeavingSummaryEdge() == null,
                "node %s has AssumeEdges and summary edge",
                debugFormat(pNode));

            AssumeEdge ae1 = (AssumeEdge) edge1;
            AssumeEdge ae2 = (AssumeEdge) edge2;
            verify(
                ae1.getTruthAssumption() != ae2.getTruthAssumption(),
                "Inconsistent branching at node %s",
                debugFormat(pNode));
          }

          checkEdge(edge1, pMachineModel);
          checkEdge(edge2, pMachineModel);
          break;

        default:
          throw new VerifyException("Too much branching at node " + debugFormat(pNode));
      }
    }
  }

  /**
   * Check all entering and leaving edges for corresponding leaving/entering edges at
   * predecessor/successor nodes, and that there are no duplicates
   *
   * @param pNode Node to be checked
   */
  private static void isConsistentAsGraphNode(CFANode pNode) {
    Set<CFAEdge> seenEdges = new HashSet<>();
    Set<CFANode> seenNodes = new HashSet<>();

    for (CFAEdge edge : leavingEdges(pNode)) {
      verify(seenEdges.add(edge), "Duplicate leaving edge %s on node %s", edge, debugFormat(pNode));

      CFANode successor = edge.getSuccessor();
      verify(
          seenNodes.add(successor),
          "Duplicate successor %s for node %s",
          successor,
          debugFormat(pNode));

      verify(
          enteringEdges(successor).contains(edge),
          "Node %s has leaving edge %s, but node %s does not have this edge as entering edge!",
          debugFormat(pNode),
          edge,
          debugFormat(successor));
    }

    seenEdges.clear();
    seenNodes.clear();

    for (CFAEdge edge : enteringEdges(pNode)) {
      verify(
          seenEdges.add(edge), "Duplicate entering edge %s on node %s", edge, debugFormat(pNode));

      CFANode predecessor = edge.getPredecessor();
      verify(
          seenNodes.add(predecessor),
          "Duplicate predecessor %s for node %s",
          predecessor,
          debugFormat(pNode));

      verify(
          leavingEdges(predecessor).contains(edge),
          "Node %s has entering edge %s, but node %s does not have this edge as leaving edge!",
          debugFormat(pNode),
          edge,
          debugFormat(predecessor));
    }
  }

  // simple check for valid contents of an edge
  private static void checkEdge(CFAEdge edge, MachineModel machineModel) {

    switch (edge.getEdgeType()) {
      case AssumeEdge:
        if (edge instanceof CAssumeEdge) {
          checkTypes(((CAssumeEdge) edge).getExpression(), machineModel);
        }
        break;

      case DeclarationEdge:
        ADeclaration decl = ((ADeclarationEdge) edge).getDeclaration();
        if (decl instanceof CVariableDeclaration) {
          CInitializer init = ((CVariableDeclaration) decl).getInitializer();
          if (init instanceof CInitializerExpression) {
            checkTypes(((CInitializerExpression) init).getExpression(), machineModel);
          }
        }
        break;

      case StatementEdge:
        AStatement stat = ((AStatementEdge) edge).getStatement();
        if (stat instanceof CExpressionStatement) {
          checkTypes(((CExpressionStatement) stat).getExpression(), machineModel);
        }
        break;

      case BlankEdge:
        break;

      case CallToReturnEdge:
        throw new AssertionError(); // dont check them directly

      case FunctionCallEdge:
        CFANode pred = edge.getPredecessor();
        verify(
            pred.getLeavingSummaryEdge() != null,
            "FunctionCallEdge has no summary edge at %s",
            debugFormat(pred));
        break;

      case FunctionReturnEdge:
        CFANode succ = edge.getSuccessor();
        verify(
            succ.getEnteringSummaryEdge() != null,
            "FunctionReturnEdge has no summary edge at %s",
            debugFormat(succ));
        break;

      case ReturnStatementEdge:
        break;

      default:
        throw new AssertionError();
    }
  }

  private static void checkTypes(CExpression exp, MachineModel machineModel) {
    exp.accept(new ExpressionValidator(machineModel, exp));
  }

  private static final class ExpressionValidator
      extends DefaultCExpressionVisitor<Void, RuntimeException> {

    private final MachineModel machineModel;
    private final CExpression expressionForLogging;

    public ExpressionValidator(MachineModel pMachineModel, CExpression pExp) {
      machineModel = pMachineModel;
      expressionForLogging = pExp;
    }

    private void checkValueRange(CType pType, BigInteger value) {
      CSimpleType type = (CSimpleType) pType.getCanonicalType();
      verify(
          machineModel.getMinimalIntegerValue(type).compareTo(value) <= 0,
          "value '%s' is too small for type '%s' in expression '%s' at %s",
          value,
          type,
          expressionForLogging.toASTString(),
          expressionForLogging.getFileLocation());
      verify(
          machineModel.getMaximalIntegerValue(type).compareTo(value) >= 0,
          "value '%s' is too large for type '%s' in expression '%s' at %s",
          value,
          type,
          expressionForLogging.toASTString(),
          expressionForLogging.getFileLocation());
    }

    @Override
    public Void visit(CArraySubscriptExpression pArraySubscriptExpression) {
      pArraySubscriptExpression.getSubscriptExpression().accept(this);
      return null;
    }

    @Override
    public Void visit(CFieldReference pFieldReference) {
      pFieldReference.getFieldOwner().accept(this);
      return null;
    }

    @Override
    public Void visit(CPointerExpression pPointerExpression) {
      pPointerExpression.getOperand().accept(this);
      return null;
    }

    @Override
    public Void visit(CComplexCastExpression pComplexCastExpression) {
      pComplexCastExpression.getOperand().accept(this);
      return null;
    }

    @Override
    public Void visit(CBinaryExpression pBinaryExpression) {
      pBinaryExpression.getOperand1().accept(this);
      pBinaryExpression.getOperand2().accept(this);
      return null;
    }

    @Override
    public Void visit(CCastExpression pCastExpression) {
      pCastExpression.getOperand().accept(this);
      return null;
    }

    @Override
    public Void visit(CCharLiteralExpression pLiteral) {
      checkValueRange(pLiteral.getExpressionType(), BigInteger.valueOf(pLiteral.getCharacter()));
      return null;
    }

    @Override
    public Void visit(CIntegerLiteralExpression pLiteral) {
      checkValueRange(pLiteral.getExpressionType(), pLiteral.getValue());
      return null;
    }

    @Override
    public Void visit(CUnaryExpression pUnaryExpression) {
      pUnaryExpression.getOperand().accept(this);
      return null;
    }

    @Override
    public Void visit(CImaginaryLiteralExpression pLiteralExpression) {
      pLiteralExpression.getValue().accept(this);
      return null;
    }

    @Override
    protected Void visitDefault(CExpression pExp) throws RuntimeException {
      return null; // ignore the expression
    }
  }

  public static boolean checkFull(CFA pCfa) {
    for (String name : pCfa.getAllFunctionNames()) {
      ImmutableSet<CFANode> functionNodes =
          FluentIterable.from(pCfa.getAllNodes())
              .filter(n -> n.getFunctionName().equals(name))
              .toSet();
      assert check(pCfa.getFunctionHead(name), functionNodes, pCfa.getMachineModel());
    }
    return true;
  }
}
