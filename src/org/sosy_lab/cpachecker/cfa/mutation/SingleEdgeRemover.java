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
import org.sosy_lab.cpachecker.util.Pair;

/**
 * Remove an edge with a predecessor from a CFA. If this can not be done, replace edge with a blank
 * one. Predecessor has to have single leaving edge.
 */
public class SingleEdgeRemover
    extends GenericDeltaDebuggingStrategy<CFANode, Pair<Integer, CFANode>> {

  public SingleEdgeRemover(LogManager pLogger) {
    super(
        pLogger.withComponentName(SingleEdgeRemover.class.getSimpleName()),
        "nodes with single leaving edge");
  }

  protected SingleEdgeRemover(LogManager pLogger, String pTitle) {
    super(pLogger, pTitle);
  }

  // Usually we can remove a node with single leaving edge, but if a cycle consisted of three
  // nodes (see below) and we removed node C, we can't remove node A.
  // (Removing a node here means removing its leaving edge and connecting its entering edges to
  // its successor.)
  //                       |
  // --> A -----> B -->    |  --> A ---> B -->
  //     ^-- C <--'        |      ^------'
  @Override
  protected List<CFANode> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<CFANode> result = new ArrayList<>();
    pCfa.getCFANodes().values().stream()
        .filter(node -> canRemoveWithLeavingEdge(node))
        .forEach(node -> result.add(node));
    return result;
  }

  protected boolean canRemoveWithLeavingEdge(CFANode pNode) {
    if (pNode.getNumLeavingEdges() != 1 || pNode.getNumEnteringEdges() == 0) {
      return false;
    }

    CFANode successor = pNode.getLeavingEdge(0).getSuccessor();
    if (successor == pNode || successor.getNumEnteringEdges() > 1) {
      return false;
    }

    return true;
  }

  @Override
  protected Pair<Integer, CFANode> removeObject(FunctionCFAsWithMetadata pCfa, CFANode pChosen) {

    final CFAEdge toRemove = pChosen.getLeavingEdge(0);
    final CFANode successor = toRemove.getSuccessor();

    // remove predecessor itself (no edges disconnected yet)
    assert pCfa.getCFANodes().remove(pChosen.getFunctionName(), pChosen);
    // save index to restore properly
    int index = -1;
    for (int i = 0; i < successor.getNumEnteringEdges(); i++) {
      if (toRemove == successor.getEnteringEdge(i)) {
        index = i;
        break;
      }
    }
    assert index >= 0;

    // disconnect edge from successor
    CFAMutationUtils.removeFromSuccessor(toRemove);
    // disconnect pred-predecessors from predecessor and connect to successor
    CFAMutationUtils.changeSuccessor(pChosen, successor);

    return Pair.of(index, pChosen);
  }

  @Override
  protected void restoreObject(FunctionCFAsWithMetadata pCfa, Pair<Integer, CFANode> pRemoved) {
    final int index = pRemoved.getFirst();
    final CFANode oldPredecessor = pRemoved.getSecond();
    final CFAEdge removedEdge = oldPredecessor.getLeavingEdge(0);
    final CFANode successor = removedEdge.getSuccessor();

    // restore predecessor itself
    assert pCfa.getCFANodes().put(oldPredecessor.getFunctionName(), oldPredecessor);
    // reconnect pred-predecessors to predecessor, disconnect from successor
    CFAMutationUtils.restoreSuccessor(oldPredecessor, successor);
    // reconnect predecessor and successor
    CFAMutationUtils.insertInSuccessor(index, removedEdge);
  }
}
