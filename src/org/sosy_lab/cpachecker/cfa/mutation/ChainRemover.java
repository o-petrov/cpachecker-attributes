// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

/**
 * Replaces chain of edges with one blank edge. A chain of edges starts at a node with one leaving
 * edge and includes its successors with one leaving edge. Subchains inside other chains are chains,
 * but they are not considered. One node can be a chain too.
 */
class ChainRemover extends GenericDeltaDebuggingStrategy<CFANode, CFAEdge> {

  public ChainRemover(LogManager pLogger) {
    super(pLogger, "chains of edges");
  }

  @Override
  protected List<CFANode> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<CFANode> chainHeads = new ArrayList<>();

    for (CFANode n : pCfa.getCFANodes().values()) {
      if (n.getNumLeavingEdges() != 1 || n.getNumEnteringEdges() == 0) {
        continue;
      }
      if (n.getNumEnteringEdges() > 1
          || n.getEnteringEdge(0).getPredecessor().getNumLeavingEdges() > 1) {
        // has more than one predecessor or predecessor is branching point (i.e. not in a chain)
        chainHeads.add(n);
      }
    }

    return chainHeads;
  }

  static boolean isInsideChain(CFANode pNode) {
    return pNode.getNumLeavingEdges() == 1 && pNode.getNumEnteringEdges() == 1;
  }

  @Override
  protected CFAEdge removeObject(FunctionCFAsWithMetadata pCfa, CFANode pChainHead) {
    // collect edges in chain to summarize them in blank edge
    List<CFAEdge> chainEdges = new ArrayList<>();

    chainEdges.add(pChainHead.getLeavingEdge(0));
    CFANode successor = pChainHead.getLeavingEdge(0).getSuccessor();

    // remove nodes after head (no edges disconnected yet)
    while (isInsideChain(successor)) {
      assert pCfa.getCFANodes().remove(pChainHead.getFunctionName(), successor);
      chainEdges.add(successor.getLeavingEdge(0));
      successor = successor.getLeavingEdge(0).getSuccessor();
    }

    CFAEdge blankEdge =
        new BlankEdge(
            Joiner.on('\n').join(chainEdges.stream().map(CFAEdge::getRawStatement).iterator()),
            FileLocation.merge(
                ImmutableList.copyOf(Iterables.transform(chainEdges, CFAEdge::getFileLocation))),
            pChainHead,
            successor,
            "replaced chain of " + chainEdges.size() + " edges");

    // replace chain o edges with the blank edge
    CFAMutationUtils.replaceInSuccessor(chainEdges.get(chainEdges.size() - 1), blankEdge);
    CFAMutationUtils.replaceInPredecessor(chainEdges.get(0), blankEdge);

    return chainEdges.get(0);
  }

  @Override
  protected void restoreObject(FunctionCFAsWithMetadata pCfa, CFAEdge pRemoved) {
    final CFANode chainHead = pRemoved.getPredecessor();

    // restore nodes
    CFAEdge blankEdge = chainHead.getLeavingEdge(0);
    CFANode successor = blankEdge.getSuccessor();

    CFANode n = pRemoved.getSuccessor();
    CFAEdge lastEdge = pRemoved;
    while (n != successor) {
      assert pCfa.getCFANodes().put(chainHead.getFunctionName(), n);
      lastEdge = n.getLeavingEdge(0);
      n = lastEdge.getSuccessor();
    }

    // reconnect chain tail node and successor
    CFAMutationUtils.replaceInPredecessor(blankEdge, pRemoved);
    CFAMutationUtils.replaceInSuccessor(blankEdge, lastEdge);
  }
}
