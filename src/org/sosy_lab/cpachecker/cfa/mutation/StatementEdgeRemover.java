// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

public class StatementEdgeRemover extends SingleEdgeRemover {

  public StatementEdgeRemover(LogManager pLogger) {
    super(
        pLogger.withComponentName(StatementEdgeRemover.class.getSimpleName()),
        "statement and blank edges");
  }

  @Override
  protected boolean canRemoveWithLeavingEdge(CFANode pNode) {
    if (!super.canRemoveWithLeavingEdge(pNode)) {
      return false;
    }
    if (!CFAMutationUtils.isStatementOrBlank(pNode.getLeavingEdge(0))) {
      return false;
    }

    // dont touch short chains of one edge
    // can remove if next is statement edge too
    CFANode successor = pNode.getLeavingEdge(0).getSuccessor();
    if (CFAMutationUtils.isInsideChain(successor)
        && CFAMutationUtils.isStatementOrBlank(successor.getLeavingEdge(0))) {
      return true;
    }
    // else can remove if prev is statement edge too
    return CFAMutationUtils.isInsideChain(pNode)
        && CFAMutationUtils.isStatementOrBlank(pNode.getEnteringEdge(0));
  }
}
