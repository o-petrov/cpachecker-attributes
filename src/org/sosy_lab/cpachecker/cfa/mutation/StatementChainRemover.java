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

class StatementChainRemover extends ChainRemover {

  public StatementChainRemover(LogManager pLogger) {
    super(
        pLogger.withComponentName(StatementChainRemover.class.getSimpleName()),
        "chains of statement and blank edges");
  }

  @Override
  protected boolean isChainHead(CFANode pNode) {
    return pNode.getNumLeavingEdges() == 1
        && CFAMutationUtils.isStatementOrBlank(pNode.getLeavingEdge(0))
        && (pNode.getNumEnteringEdges() != 1
            || pNode.getEnteringEdge(0).getPredecessor().getNumLeavingEdges() > 1
            || !CFAMutationUtils.isStatementOrBlank(pNode.getEnteringEdge(0)));
  }

  @Override
  protected boolean isInsideChain(CFANode pNode) {
    return pNode.getNumLeavingEdges() == 1
        && CFAMutationUtils.isStatementOrBlank(pNode.getLeavingEdge(0))
        && (pNode.getNumEnteringEdges() == 1
            && CFAMutationUtils.isStatementOrBlank(pNode.getEnteringEdge(0)));
  }
}
