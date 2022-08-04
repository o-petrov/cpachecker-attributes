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
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

/**
 * Removes node with assume edges if one of them leads to empty branch, i.e. that consists only of
 * one blank edge. If both branches are empty and have same successor, remove both of them.
 */
class EmptyBranchPruner
    extends GenericDeltaDebuggingStrategy<
        EmptyBranchPruner.RollbackInfo, EmptyBranchPruner.RollbackInfo> {
  static class RollbackInfo {
    CFANode branchingNode;
    /** Null if both are removed */
    CFANode removedSuccessor;

    CFAEdge otherEdge;
    int i, j;

    public RollbackInfo(CFANode pBranchingNode) {
      branchingNode = pBranchingNode;
      removedSuccessor = null; // both
      otherEdge = null;

      CFAEdge le = branchingNode.getLeavingEdge(0).getSuccessor().getLeavingEdge(0);
      i = -1;
      for (i = 0; i < le.getSuccessor().getNumLeavingEdges(); i++) {
        if (le.getSuccessor().getEnteringEdge(i) == le) {
          break;
        }
      }

      le = branchingNode.getLeavingEdge(1).getSuccessor().getLeavingEdge(0);
      for (j = 0; j < le.getSuccessor().getNumLeavingEdges(); j++) {
        if (le.getSuccessor().getEnteringEdge(j) == le) {
          break;
        }
      }
    }

    public RollbackInfo(CFANode pBranchingNode, int pRemovedSide) {
      branchingNode = pBranchingNode;
      removedSuccessor = branchingNode.getLeavingEdge(pRemovedSide).getSuccessor();
      otherEdge = branchingNode.getLeavingEdge(1 - pRemovedSide);

      CFAEdge le = removedSuccessor.getLeavingEdge(0);
      for (i = 0; i < le.getSuccessor().getNumLeavingEdges(); i++) {
        if (le.getSuccessor().getEnteringEdge(i) == le) {
          break;
        }
      }

      le = otherEdge;
      for (j = 0; j < le.getSuccessor().getNumLeavingEdges(); j++) {
        if (le.getSuccessor().getEnteringEdge(i) == le) {
          break;
        }
      }
    }
  }

  public EmptyBranchPruner(LogManager pLogger) {
    super(pLogger, "empty branches");
  }

  @Override
  protected List<RollbackInfo> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<RollbackInfo> result = new ArrayList<>();

    for (CFANode branchingNode : pCfa.getCFANodes().values()) {
      if (branchingNode.getNumLeavingEdges() != 2) {
        continue;
      }

      List<CFANode> branch0 = new ArrayList<>();
      CFANode s0 = branchingNode.getLeavingEdge(0).getSuccessor();
      while (CFAMutationUtils.isInsideChain(s0)) {
        branch0.add(s0);
        s0 = s0.getLeavingEdge(0).getSuccessor();
      }
      // s0 is first node after branch 0, i.e. successor

      List<CFANode> branch1 = new ArrayList<>();
      CFANode s1 = branchingNode.getLeavingEdge(1).getSuccessor();
      while (CFAMutationUtils.isInsideChain(s1)) {
        branch1.add(s1);
        s1 = s1.getLeavingEdge(0).getSuccessor();
      }
      // s1 is first node after branch 1, i.e. successor

      if (s0 == branchingNode || s1 == branchingNode) {
        // loops will be dealt with in another strategy
        continue;
      }

      boolean empty0 = branch0.size() == 1 && branch0.get(0).getLeavingEdge(0) instanceof BlankEdge;
      boolean empty1 = branch1.size() == 1 && branch1.get(0).getLeavingEdge(0) instanceof BlankEdge;

      if (empty0 && empty1) {
        if (s0 != s1) {
          logger.logf(
              Level.INFO,
              "Can not prune both empty branches from %s, as they leave to diferent successors: %s and %s",
              branchingNode,
              s0,
              s1);
          continue;
        }

        // remove three nodes, all edges entering branching node should enter s0
        result.add(new RollbackInfo(branchingNode));
      } else if (empty0) {
        // remove branching, connect pred to successor
        result.add(new RollbackInfo(branchingNode, 0));
      } else if (empty1) {
        // remove b
        result.add(new RollbackInfo(branchingNode, 1));
      }
    }
    return result;
  }

  @Override
  protected RollbackInfo removeObject(FunctionCFAsWithMetadata pCfa, RollbackInfo pChosen) {
    if (pChosen.removedSuccessor != null) {
      pCfa.getCFANodes().remove(pChosen.branchingNode.getFunctionName(), pChosen.branchingNode);
      pCfa.getCFANodes().remove(pChosen.branchingNode.getFunctionName(), pChosen.removedSuccessor);

      // disconnect node inside empty branch from successor
      CFAMutationUtils.removeFromSuccessor(pChosen.removedSuccessor.getLeavingEdge(0));
      // disconnect branching node from other branch
      CFAMutationUtils.removeFromSuccessor(pChosen.otherEdge);
      // connect edges from branching node to other branch
      CFAMutationUtils.changeSuccessor(pChosen.branchingNode, pChosen.otherEdge.getSuccessor());

    } else {
      CFANode s0 = pChosen.branchingNode.getLeavingEdge(0).getSuccessor();
      CFANode s1 = pChosen.branchingNode.getLeavingEdge(1).getSuccessor();

      pCfa.getCFANodes().remove(pChosen.branchingNode.getFunctionName(), pChosen.branchingNode);
      pCfa.getCFANodes().remove(pChosen.branchingNode.getFunctionName(), s0);
      pCfa.getCFANodes().remove(pChosen.branchingNode.getFunctionName(), s1);

      // disconnect both branches from successor
      CFAMutationUtils.removeFromSuccessor(s0.getLeavingEdge(0));
      CFAMutationUtils.removeFromSuccessor(s1.getLeavingEdge(0));

      // reconnect all entering branches from branching node to successor
      CFAMutationUtils.changeSuccessor(pChosen.branchingNode, s0.getLeavingEdge(0).getSuccessor());
    }

    return pChosen;
  }

  @Override
  protected void restoreObject(FunctionCFAsWithMetadata pCfa, RollbackInfo pRemoved) {
    if (pRemoved.removedSuccessor != null) {
      pCfa.getCFANodes().put(pRemoved.branchingNode.getFunctionName(), pRemoved.branchingNode);
      pCfa.getCFANodes().put(pRemoved.branchingNode.getFunctionName(), pRemoved.removedSuccessor);

      // reconnect edges to branching node
      CFAMutationUtils.restoreSuccessor(pRemoved.branchingNode, pRemoved.otherEdge.getSuccessor());

      // connect empty branch to successor
      CFAMutationUtils.insertInSuccessor(pRemoved.i, pRemoved.removedSuccessor.getLeavingEdge(0));
      // disconnect branching node from other branch
      CFAMutationUtils.insertInSuccessor(pRemoved.j, pRemoved.otherEdge);

    } else {
      CFANode s0 = pRemoved.branchingNode.getLeavingEdge(0).getSuccessor();
      CFANode s1 = pRemoved.branchingNode.getLeavingEdge(1).getSuccessor();

      pCfa.getCFANodes().put(pRemoved.branchingNode.getFunctionName(), pRemoved.branchingNode);
      pCfa.getCFANodes().put(pRemoved.branchingNode.getFunctionName(), s0);
      pCfa.getCFANodes().put(pRemoved.branchingNode.getFunctionName(), s1);

      // reconnect all entering branches to branching node from successor
      CFAMutationUtils.restoreSuccessor(
          pRemoved.branchingNode, s0.getLeavingEdge(0).getSuccessor());

      // connect both branches from successor
      CFAMutationUtils.insertInSuccessor(pRemoved.i, s0.getLeavingEdge(0));
      CFAMutationUtils.insertInSuccessor(pRemoved.j, s1.getLeavingEdge(0));
    }
  }
}
