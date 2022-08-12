// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

class DeclarationEdgeRemover extends SingleEdgeRemover {
  private DeclarationCollector dc = new DeclarationCollector();
  private boolean firstRun = true;

  public DeclarationEdgeRemover(LogManager pLogger) {
    super(pLogger, "local declarations");
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    boolean result;

    if (firstRun) {
      firstRun = false;
      dc.collectUsed(pCfa);
      return super.canMutate(pCfa);
    }

    // subseq runs
    result = super.canMutate(pCfa);
    if (!result) {
      dc.collectUsed(pCfa);
      result = super.canMutate(pCfa);
    }
    return result;
  }

  @Override
  protected boolean canRemoveWithLeavingEdge(CFANode pNode) {
    if (! super.canRemoveWithLeavingEdge(pNode)) {
      return false;
    }
    if (!(pNode.getLeavingEdge(0) instanceof ADeclarationEdge)) {
      return false;
    }
    ADeclaration decl = ((ADeclarationEdge) pNode.getLeavingEdge(0)).getDeclaration();
    return decl instanceof AVariableDeclaration
        && !dc.getUsedLocalVariables().get(pNode.getFunction().getName()).contains(decl);
  }

}
