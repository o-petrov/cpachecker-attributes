// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.CFATerminationNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFATraversal.CFAVisitor;
import org.sosy_lab.cpachecker.util.CFATraversal.DefaultCFAVisitor;
import org.sosy_lab.cpachecker.util.CFATraversal.TraversalProcess;

/**
 * Remove node with assume edges if one of them leads to empty branch, i.e. that consists only of
 * one blank edge. If both branches are empty and have same successor, remove both of them.
 */
// TODO better branch detection -- dominators/basic blocks
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
    super(pLogger.withComponentName(EmptyBranchPruner.class.getSimpleName()), "empty branches");
  }

  @Override
  protected List<RollbackInfo> getAllObjects(FunctionCFAsWithMetadata pCfa) {
    List<RollbackInfo> result = new ArrayList<>();

    for (CFANode branchingNode : pCfa.getCFANodes().values()) {
      if (branchingNode.getNumLeavingEdges() != 2) {
        continue;
      }

      Set<CFANode> leftNodes = new TreeSet<>();
      Set<CFANode> rightNodes = new TreeSet<>();
      Set<CFANode> commonNodes = new TreeSet<>();
      // TODO for now it ignores endless loop heads with two leaving edges
      Set<CFANode> sinks = new TreeSet<>();

      CFAVisitor branchResolver =
          new DefaultCFAVisitor() {
            @Override
            public TraversalProcess visitEdge(CFAEdge pEdge) {
              CFANode s = pEdge.getSuccessor();
              CFANode p = pEdge.getPredecessor();
              assert !commonNodes.contains(p);

              if (s instanceof CFATerminationNode || s instanceof FunctionExitNode) {
                sinks.add(s);
              }

              if (leftNodes.contains(p)) {
                if (leftNodes.contains(s)) {
                  // node has only path out and was reached already -- it is endless loop head
                  if (s.getNumLeavingEdges() == 1) {
                    sinks.add(s);
                  }
                  return TraversalProcess.SKIP;

                } else if (rightNodes.contains(s)) {
                  rightNodes.remove(s);
                  commonNodes.add(s);
                  return TraversalProcess.SKIP;

                } else if (commonNodes.contains(s)) {
                  return TraversalProcess.SKIP;

                } else {
                  leftNodes.add(s);
                  return s == branchingNode ? TraversalProcess.SKIP : TraversalProcess.CONTINUE;
                }

              } else if (rightNodes.contains(p)) {
                if (rightNodes.contains(s)) {
                  // node has only path out and was reached already -- it is endless loop head
                  if (s.getNumLeavingEdges() == 1) {
                    sinks.add(s);
                  }
                  return TraversalProcess.SKIP;

                } else if (leftNodes.contains(s)) {
                  rightNodes.remove(s);
                  commonNodes.add(s);
                  return TraversalProcess.SKIP;

                } else if (commonNodes.contains(s)) {
                  return TraversalProcess.SKIP;

                } else {
                  rightNodes.add(s);
                  return s == branchingNode ? TraversalProcess.SKIP : TraversalProcess.CONTINUE;
                }

              } else {
                throw new AssertionError("on " + branchingNode + ": " + pEdge);
              }
            }
          };

      CFANode s0 = branchingNode.getLeavingEdge(0).getSuccessor();
      CFANode s1 = branchingNode.getLeavingEdge(1).getSuccessor();
      leftNodes.add(s0);
      rightNodes.add(s1);

      CFATraversal.dfs().traverseOnce(s0, branchResolver);
      // s1 can be moved to common if left branch ends on s1
      // no need to traverse right branch then
      if (rightNodes.contains(s1)) {
        CFATraversal.dfs().traverseOnce(s1, branchResolver);
      }

      Set<CFANode> leftSinks = Sets.intersection(sinks, rightNodes);
      SetView<CFANode> rightSinks = Sets.intersection(sinks, leftNodes);
      if (!leftSinks.isEmpty() || !rightSinks.isEmpty()) {
        logger.log(
            Level.INFO,
            "Can not deal with branches from",
            branchingNode,
            "as they do not merge completely: right branch never reaches",
            leftSinks,
            "and left never reaches",
            rightSinks);
        continue;
      }

      if (commonNodes.contains(branchingNode)
          || rightNodes.contains(branchingNode)
          || leftNodes.contains(branchingNode)) {
        // loops will be dealt with in another strategy
        continue;
      }

      boolean emptyLeft =
          leftNodes.size() == 1
              && Iterables.getOnlyElement(leftNodes).getLeavingEdge(0) instanceof BlankEdge;
      boolean emptyRight =
          rightNodes.size() == 1
              && Iterables.getOnlyElement(rightNodes).getLeavingEdge(0) instanceof BlankEdge;

      if (emptyLeft && emptyRight) {
        if (commonNodes.size() != 1) {
          logger.logf(
              Level.INFO,
              "Can not prune empty branches from %s, as they leave to diferent successors",
              branchingNode);
          continue;
        }

        // remove three nodes, all edges entering branching node should enter s0
        result.add(new RollbackInfo(branchingNode));
      } else if (emptyLeft) {
        // remove branching, connect pred to successor
        result.add(new RollbackInfo(branchingNode, 0));
      } else if (emptyRight) {
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
