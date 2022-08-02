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
import org.sosy_lab.cpachecker.util.CFAUtils;
import org.sosy_lab.cpachecker.util.Pair;

/**
 * Remove an edge with a predecessor from a CFA. If this can not be done, replace edge with a blank
 * one. Predecessor has to have single leaving edge.
 */
public class SingleEdgeRemover
    extends GenericDeltaDebuggingStrategy<CFAEdge, Pair<Integer, CFAEdge>> {

  public SingleEdgeRemover(LogManager pLogger) {
    super(pLogger, "nodes with single leaving edge");
  }

  // Usually we can remove a node with single leaving edge, but if a cycle consisted of three
  // nodes (see below) and we removed node C, we can't remove node A.
  // (Removing a node here means removing its leaving edge and connecting its entering edges to
  // its successor.)
  //                       |
  // --> A -----> B -->    |  --> A ---> B -->
  //     ^-- C <--'        |      ^------'
  @Override
  protected List<CFAEdge> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<CFAEdge> result = new ArrayList<>();
    pCfa.getCFANodes().values().stream()
        .filter(node -> canRemoveWithLeavingEdge(node))
        .forEach(node -> result.add(node.getLeavingEdge(0)));
    return result;
  }

  protected boolean canRemoveWithLeavingEdge(CFANode pNode) {
    // TODO proper constraints (on node class too?)
    if (pNode.getNumLeavingEdges() != 1) {
      return false;
    }
    if (pNode.getNumEnteringEdges() != 1) {
      return false;
    }
    CFANode successor = pNode.getLeavingEdge(0).getSuccessor();
    if (successor == pNode) {
      return false;
    }
    CFANode predecessor = pNode.getEnteringEdge(0).getPredecessor();
    if (successor == predecessor) {
      return false;
    }
    if (predecessor.getNumLeavingEdges() > 1) {
      return false;
    }
    if (canRemoveWithLeavingEdge(predecessor)) {
      return false;
    }
    return true;
  }

  @Override
  protected Pair<Integer, CFAEdge> removeObject(FunctionCFAsWithMetadata pCfa, CFAEdge pChosen) {
    // TODO do not remove node for some cases, just replace edge with blank // other strategy??
    final CFANode predecessor = pChosen.getPredecessor();
    final CFANode successor = pChosen.getSuccessor();

    // remove predecessor itself (no edges disconnected yet)
    assert pCfa.getCFANodes().remove(predecessor.getFunctionName(), predecessor);

    // save index to restore properly
    int index = -1;
    for (int i = 0; i < successor.getNumEnteringEdges(); i++) {
      if (pChosen == successor.getEnteringEdge(i)) {
        index = i;
        break;
      }
    }
    // disconnect edge from successor
    CFAMutationUtils.removeFromSuccessor(pChosen);

    // disconnect pred-predecessors from predecessor and connect to successor
    for (CFAEdge edge : CFAUtils.allEnteringEdges(predecessor)) {
      CFAEdge newEdge = CFAMutationUtils.copyWithOtherSuccessor(edge, successor);
      CFAMutationUtils.replaceInPredecessor(edge, newEdge);
      CFAMutationUtils.addToSuccessor(newEdge);
    }

    return Pair.of(index, pChosen);
  }

  @Override
  protected void restoreObject(FunctionCFAsWithMetadata pCfa, Pair<Integer, CFAEdge> pRemoved) {
    final int index = pRemoved.getFirst();
    final CFAEdge removedEdge = pRemoved.getSecond();
    final CFANode oldPredecessor = removedEdge.getPredecessor();
    final CFANode successor = removedEdge.getSuccessor();

    // restore predecessor itself
    assert pCfa.getCFANodes().put(oldPredecessor.getFunctionName(), oldPredecessor);

    // reconnect pred-predecessors to predecessor, disconnect from successor
    for (CFAEdge oldEdge : CFAUtils.allEnteringEdges(oldPredecessor)) {
      CFANode predpred = oldEdge.getPredecessor();
      CFAEdge newEdge = predpred.getEdgeTo(successor);
      CFAMutationUtils.replaceInPredecessor(newEdge, oldEdge);
      CFAMutationUtils.removeFromSuccessor(newEdge);
    }

    // reconnect predecessor and successor
    CFAMutationUtils.insertInSuccessor(index, removedEdge);
  }
}
