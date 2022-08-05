// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

public class StatementRemover extends SingleEdgeRemover {

  public StatementRemover(LogManager pLogger) {
    super(
        pLogger.withComponentName(StatementRemover.class.getSimpleName()),
        "statement and blank edges");
  }

  @Override
  protected boolean canRemoveWithLeavingEdge(CFANode pNode) {
    if (!super.canRemoveWithLeavingEdge(pNode)) {
      return false;
    }
    return pNode.getLeavingEdge(0) instanceof AStatementEdge
        || pNode.getLeavingEdge(0) instanceof BlankEdge;
  }
}
