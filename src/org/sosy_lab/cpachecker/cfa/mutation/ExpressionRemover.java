// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import java.util.ArrayList;
import java.util.List;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

/**
 * Replace expression on an edge with expression chosen by {@link ToDefaultsExpressionSubstitutor}.
 *
 * <p>E.g. integer literals are not replaced, but other integer expressions are most likely replaced
 * with {@code 0} literal. Only right part is replaced in assignments, only arguments are replaced
 * in function calls.
 */
class ExpressionRemover extends GenericDeltaDebuggingStrategy<CFAEdge, CFAEdge> {
  private AbstractExpressionSubstitutor expressionSubstitutor;

  public ExpressionRemover(LogManager pLogger) {
    super(pLogger, "expressions");
    expressionSubstitutor = new ToDefaultsExpressionSubstitutor();
  }

  @Override
  protected List<CFAEdge> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<CFAEdge> newEdges = new ArrayList<>();
    for (CFANode n : pCfa.getCFANodes().values()) {
      if (n.getNumLeavingEdges() != 1) {
        // XXX cant remove expression from assume edge
        continue;
      }

      CFAEdge edge = n.getLeavingEdge(0);
      CFAEdge newEdge = expressionSubstitutor.substitute(edge);
      if (newEdge != null) {
        newEdges.add(newEdge);
      }
    }
    return newEdges;
  }

  @Override
  protected CFAEdge removeObject(FunctionCFAsWithMetadata pCfa, CFAEdge pChosen) {
    CFAEdge oldEdge = pChosen.getPredecessor().getEdgeTo(pChosen.getSuccessor());
    CFAMutationUtils.replaceInPredecessor(oldEdge, pChosen);
    CFAMutationUtils.replaceInSuccessor(oldEdge, pChosen);
    return oldEdge;
  }

  @Override
  protected void restoreObject(FunctionCFAsWithMetadata pCfa, CFAEdge pRemoved) {
    CFAEdge newEdge = pRemoved.getPredecessor().getEdgeTo(pRemoved.getSuccessor());
    CFAMutationUtils.replaceInPredecessor(newEdge, pRemoved);
    CFAMutationUtils.replaceInSuccessor(newEdge, pRemoved);
  }
}
