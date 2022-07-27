// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CStatement;
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

class CFAMutationUtils {

  public static CFAEdge changeSuccessor(CFAEdge pEdge, CFANode pSuccessor) {
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
        if (pEdge instanceof CAssumeEdge) {
          return new CDeclarationEdge(raw, loc, pred, pSuccessor, (CDeclaration) d);
        } else if (pEdge instanceof JAssumeEdge) {
          return new JDeclarationEdge(raw, loc, pred, pSuccessor, (JDeclaration) d);
        } else {
          throw new AssertionError();
        }

      case FunctionCallEdge: // no interprocedure
      case FunctionReturnEdge: // no interprocedure
        throw new AssertionError();

      case ReturnStatementEdge:
        var rs = ((AReturnStatementEdge) pEdge).getReturnStatement();
        if (pEdge instanceof CAssumeEdge) {
          return new CReturnStatementEdge(
              raw, (CReturnStatement) rs, loc, pred, (FunctionExitNode) pSuccessor);
        } else if (pEdge instanceof JAssumeEdge) {
          return new JReturnStatementEdge(
              raw, (JReturnStatement) rs, loc, pred, (FunctionExitNode) pSuccessor);
        } else {
          throw new AssertionError();
        }

      case StatementEdge:
        var s = ((AStatementEdge) pEdge).getStatement();
        if (pEdge instanceof CAssumeEdge) {
          return new CStatementEdge(raw, (CStatement) s, loc, pred, pSuccessor);
        } else if (pEdge instanceof JAssumeEdge) {
          return new JStatementEdge(raw, (JStatement) s, loc, pred, pSuccessor);
        } else {
          throw new AssertionError();
        }

      default:
        throw new AssertionError();
    }
  }
}
