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
import org.sosy_lab.cpachecker.cfa.model.AssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.util.CFAUtils;

/** Removes branching when one of assume edges leaves to a node that has other entering edges */
public class AssumeEdgesRemover
    extends GenericDeltaDebuggingStrategy<
        AssumeEdgesRemover.RollbackInfo, AssumeEdgesRemover.RollbackInfo> {
  static class RollbackInfo {
    CFANode branchingNode;
    int whichRemoved;
    int indexForRemoved;
    int indexForOther;

    RollbackInfo(CFAEdge pEdge) {
      branchingNode = pEdge.getPredecessor();

      CFAEdge e0 = branchingNode.getLeavingEdge(0);
      CFAEdge e1 = branchingNode.getLeavingEdge(1);

      whichRemoved = pEdge == e0 ? 0 : 1;

      CFANode s0 = e0.getSuccessor();
      CFANode s1 = e1.getSuccessor();

      int index = -1;
      for (int i = 0; i < s0.getNumEnteringEdges(); i++) {
        if (e0 == s0.getEnteringEdge(i)) {
          index = i;
        }
      }
      assert index >= 0;
      if (e0 == pEdge) {
        indexForRemoved = index;
      } else {
        indexForOther = index;
      }

      index = -1;
      for (int i = 0; i < s1.getNumEnteringEdges(); i++) {
        if (e1 == s1.getEnteringEdge(i)) {
          index = i;
        }
      }
      assert index >= 0;
      if (e1 == pEdge) {
        indexForRemoved = index;
      } else {
        indexForOther = index;
      }
    }

    @Override
    public String toString() {
      return branchingNode + " with " + branchingNode.getLeavingEdge(whichRemoved);
    }
  }

  private int side;

  public AssumeEdgesRemover(LogManager pLogger) {
    super(
        pLogger.withComponentName(AssumeEdgesRemover.class.getSimpleName()), "branching nodes");
    side = 1;
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (super.canMutate(pCfa)) {
      return true;
    }

    if (side == 0) {
      // already removed side 1 and side 0
      return false;
    }

    reset();
    side = 0;
    return super.canMutate(pCfa);
  }

  @Override
  protected List<RollbackInfo> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<RollbackInfo> result = new ArrayList<>();

    for (CFANode branchingNode : pCfa.getCFANodes().values()) {
      if (branchingNode.getNumLeavingEdges() != 2) {
        continue;
      }

      CFAEdge edge = branchingNode.getLeavingEdge(side);
      CFANode successor = edge.getSuccessor();
      if (successor.getNumEnteringEdges() > 1) {
        // can remove assume edge to this point as it is reachable from somewhere else
        // check that there are other edges after assume edges are removed
        // or just dont remove first one
        if (CFAUtils.enteringEdges(successor).anyMatch(e -> !(e instanceof AssumeEdge))
            || successor.getEnteringEdge(0) != edge) {
          result.add(new RollbackInfo(edge));
          continue;
        }
      }
    }

    return result;
  }

  @Override
  protected RollbackInfo removeObject(FunctionCFAsWithMetadata pCfa, RollbackInfo pChosen) {
    CFAEdge toRemove = pChosen.branchingNode.getLeavingEdge(pChosen.whichRemoved);
    pCfa.getCFANodes().remove(pChosen.branchingNode.getFunctionName(), pChosen.branchingNode);
    CFAMutationUtils.removeFromSuccessor(toRemove);

    CFAEdge otherEdge = pChosen.branchingNode.getLeavingEdge(1 - pChosen.whichRemoved);
    CFANode otherSuccessor = otherEdge.getSuccessor();
    CFAMutationUtils.removeFromSuccessor(otherEdge);

    CFAMutationUtils.changeSuccessor(pChosen.branchingNode, otherSuccessor);
    return pChosen;
  }

  @Override
  protected void restoreObject(FunctionCFAsWithMetadata pCfa, RollbackInfo pRemoved) {
    pCfa.getCFANodes().put(pRemoved.branchingNode.getFunctionName(), pRemoved.branchingNode);
    CFAEdge otherEdge = pRemoved.branchingNode.getLeavingEdge(1 - pRemoved.whichRemoved);
    CFANode otherSuccessor = otherEdge.getSuccessor();

    CFAMutationUtils.restoreSuccessor(pRemoved.branchingNode, otherSuccessor);

    CFAMutationUtils.insertInSuccessor(pRemoved.indexForOther, otherEdge);
    CFAMutationUtils.insertInSuccessor(
        pRemoved.indexForRemoved, pRemoved.branchingNode.getLeavingEdge(pRemoved.whichRemoved));
  }
}
