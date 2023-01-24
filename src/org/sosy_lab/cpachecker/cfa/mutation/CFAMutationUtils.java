// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.Optional;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CDesignatedInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerList;
import org.sosy_lab.cpachecker.cfa.ast.c.CReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.java.JDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.java.JExpression;
import org.sosy_lab.cpachecker.cfa.ast.java.JReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.java.JStatement;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.AReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.c.CAssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.java.JAssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.java.JDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.java.JReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.java.JStatementEdge;
import org.sosy_lab.cpachecker.cfa.types.c.CDefaults;
import org.sosy_lab.cpachecker.cfa.types.c.CStorageClass;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CVoidType;
import org.sosy_lab.cpachecker.util.CFAUtils;

class CFAMutationUtils {

  public static CFAEdge copyWithOtherSuccessor(CFAEdge pEdge, CFANode pSuccessor) {
    String raw = pEdge.getRawStatement();
    FileLocation loc = pEdge.getFileLocation();
    CFANode pred = pEdge.getPredecessor();

    switch (pEdge.getEdgeType()) {
      case AssumeEdge:
        AssumeEdge assumeEdge = (AssumeEdge) pEdge;
        var e = assumeEdge.getExpression();
        var ta = assumeEdge.getTruthAssumption();
        if (pEdge instanceof CAssumeEdge) {
          var swap = assumeEdge.isSwapped();
          var ai = assumeEdge.isArtificialIntermediate();
          return new CAssumeEdge(raw, loc, pred, pSuccessor, (CExpression) e, ta, swap, ai);
        } else if (pEdge instanceof JAssumeEdge) {
          return new JAssumeEdge(raw, loc, pred, pSuccessor, (JExpression) e, ta);
        } else {
          throw new AssertionError();
        }

      case BlankEdge:
        var desc = ((BlankEdge) pEdge).getDescription();
        return new BlankEdge(raw, loc, pred, pSuccessor, desc);

      case CallToReturnEdge: // no interprocedure
        throw new AssertionError();

      case DeclarationEdge:
        var d = ((ADeclarationEdge) pEdge).getDeclaration();
        if (pEdge instanceof CDeclarationEdge) {
          return new CDeclarationEdge(raw, loc, pred, pSuccessor, (CDeclaration) d);
        } else if (pEdge instanceof JDeclarationEdge) {
          return new JDeclarationEdge(raw, loc, pred, pSuccessor, (JDeclaration) d);
        } else {
          throw new AssertionError();
        }

      case FunctionCallEdge: // no interprocedure
      case FunctionReturnEdge: // no interprocedure
        throw new AssertionError();

      case ReturnStatementEdge:
        var rs = ((AReturnStatementEdge) pEdge).getReturnStatement();
        if (pEdge instanceof CReturnStatementEdge) {
          return new CReturnStatementEdge(
              raw, (CReturnStatement) rs, loc, pred, (FunctionExitNode) pSuccessor);
        } else if (pEdge instanceof JReturnStatementEdge) {
          return new JReturnStatementEdge(
              raw, (JReturnStatement) rs, loc, pred, (FunctionExitNode) pSuccessor);
        } else {
          throw new AssertionError();
        }

      case StatementEdge:
        var s = ((AStatementEdge) pEdge).getStatement();
        if (pEdge instanceof CStatementEdge) {
          return new CStatementEdge(raw, (CStatement) s, loc, pred, pSuccessor);
        } else if (pEdge instanceof JStatementEdge) {
          return new JStatementEdge(raw, (JStatement) s, loc, pred, pSuccessor);
        } else {
          throw new AssertionError();
        }

      default:
        throw new AssertionError();
    }
  }

  public static CFANode copyDeclarationEdge(ADeclarationEdge pEdge, CFANode pPredecessor) {
    ADeclarationEdge newEdge;
    CFANode newNode = new CFANode(pPredecessor.getFunction());

    String raw = pEdge.getRawStatement();
    FileLocation loc = pEdge.getFileLocation();
    ADeclaration d = pEdge.getDeclaration();

    if (pEdge instanceof CDeclarationEdge) {
      newEdge = new CDeclarationEdge(raw, loc, pPredecessor, newNode, (CDeclaration) d);
    } else if (pEdge instanceof JDeclarationEdge) {
      newEdge = new JDeclarationEdge(raw, loc, pPredecessor, newNode, (JDeclaration) d);
    } else {
      throw new AssertionError();
    }

    pPredecessor.addLeavingEdge(newEdge);
    newNode.addEnteringEdge(newEdge);

    return newNode;
  }

  /** Is this node inside a chain, i.e. has it exctly one entering and exactly one leaving edge */
  public static boolean isInsideChain(CFANode pNode) {
    return pNode.getNumLeavingEdges() == 1 && pNode.getNumEnteringEdges() == 1;
  }

  public static boolean isStatementOrBlank(CFAEdge pEdge) {
    return pEdge instanceof AStatementEdge || pEdge instanceof BlankEdge;
  }

  /**
   * Remove given edge both from its successor's entering edges list and from CFA's list of edges in
   * this function.
   */
  public static void removeFromSuccessor(CFAEdge pEdge) {
    pEdge.getSuccessor().removeEnteringEdge(pEdge);
  }

  /**
   * Add given edge both to its successor's entering edges list and CFA's list of edges in this
   * function.
   */
  public static void addToSuccessor(CFAEdge pEdge) {
    pEdge.getSuccessor().addEnteringEdge(pEdge);
  }

  /**
   * Replace one edge with another both in its predecessor's leaving edges list and CFA's list of
   * edges in this function.
   */
  @SuppressWarnings("deprecation") // uses 'private' method
  public static void replaceInPredecessor(CFAEdge pEdge, CFAEdge pNewEdge) {
    pEdge.getPredecessor().replaceLeavingEdge(pEdge, pNewEdge);
  }

  @SuppressWarnings("deprecation") // uses 'private' method
  public static void insertInSuccessor(int pIndex, CFAEdge pEdge) {
    pEdge.getSuccessor().insertEnteringEdge(pIndex, pEdge);
  }

  @SuppressWarnings("deprecation")
  public static void replaceInSuccessor(CFAEdge pEdge, CFAEdge pNewEdge) {
    pEdge.getSuccessor().replaceEnteringEdge(pEdge, pNewEdge);
  }

  /**
   * Disconnect entering edges of node pOldSuccessor from their predecessors and connect their
   * copies leaving to pNewSuccessor instead.
   */
  public static void changeSuccessor(CFANode pOldSuccessor, CFANode pNewSuccessor) {
    for (CFAEdge edge : CFAUtils.enteringEdges(pOldSuccessor)) {
      CFAEdge newEdge = CFAMutationUtils.copyWithOtherSuccessor(edge, pNewSuccessor);
      CFAMutationUtils.replaceInPredecessor(edge, newEdge);
      CFAMutationUtils.addToSuccessor(newEdge);
    }
  }

  /**
   * Undo {@link #changeSuccessor}.
   *
   * <p>Disconnect replacements of pOldSuccessor entering edges from their predecessors and connect
   * back to pOldSuccessor.
   */
  public static void restoreSuccessor(CFANode pOldSuccessor, CFANode pNewSuccessor) {
    for (CFAEdge oldEdge : CFAUtils.enteringEdges(pOldSuccessor)) {
      CFAEdge newEdge = oldEdge.getPredecessor().getEdgeTo(pNewSuccessor);
      CFAMutationUtils.replaceInPredecessor(newEdge, oldEdge);
      CFAMutationUtils.removeFromSuccessor(newEdge);
    }
  }

  public static void removeAllEnteringEdges(CFANode pNode) {
    for (int i = pNode.getNumEnteringEdges() - 1; i >= 0; i--) {
      pNode.removeEnteringEdge(pNode.getEnteringEdge(i));
    }
  }

  public static void removeAllLeavingEdges(CFANode pNode) {
    for (int i = pNode.getNumLeavingEdges() - 1; i >= 0; i--) {
      pNode.removeLeavingEdge(pNode.getLeavingEdge(i));
    }
  }

  private static final String retVarName = "CPAchecker_CFAmutator_dummy_retval";

  public static Optional<CFANode> insertDefaultReturnStatementEdge(
      CFANode pNode, FunctionExitNode pExit) {
    FileLocation loc = FileLocation.DUMMY;
    AFunctionDeclaration functionDecl = pNode.getFunction();
    CType returnType = (CType) functionDecl.getType().getReturnType();

    if (returnType instanceof CVoidType) {
      CReturnStatement rst = new CReturnStatement(loc, Optional.empty(), Optional.empty());

      CFAEdge e = new CReturnStatementEdge(rst.toASTString(), rst, loc, pNode, pExit);
      pNode.addLeavingEdge(e);
      pExit.addEnteringEdge(e);

      return Optional.empty();
    }

    CInitializer init = CDefaults.forType(returnType, loc);
    CExpression rexp = null;
    CFANode afterDecl = null;

    if (init instanceof CInitializerList) {
      // complex type, can not write return with this value
      afterDecl = new CFANode(functionDecl);

      CVariableDeclaration decl =
          new CVariableDeclaration(
              loc, false, CStorageClass.AUTO, returnType, retVarName, retVarName, retVarName, init);

      CFAEdge e = new CDeclarationEdge(decl.toASTString(), loc, pNode, afterDecl, decl);
      pNode.addLeavingEdge(e);
      afterDecl.addEnteringEdge(e);

      pNode = afterDecl;
      rexp = new CIdExpression(loc, decl);

    } else if (init instanceof CDesignatedInitializer) {
      throw new AssertionError();

    } else if (init instanceof CInitializerExpression) {
      rexp = ((CInitializerExpression) init).getExpression();
    }

    CReturnStatement rst = new CReturnStatement(loc, Optional.of(rexp), Optional.empty());

    CFAEdge e = new CReturnStatementEdge(rst.toASTString(), rst, loc, pNode, pExit);
    pNode.addLeavingEdge(e);
    pExit.addEnteringEdge(e);

    return Optional.ofNullable(afterDecl);
  }
}
