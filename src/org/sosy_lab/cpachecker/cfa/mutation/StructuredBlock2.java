// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.graph.Traverser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.sosy_lab.cpachecker.cfa.CFAReversePostorder;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.CFATerminationNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFATraversal.CFAVisitor;
import org.sosy_lab.cpachecker.util.CFATraversal.TraversalProcess;
import org.sosy_lab.cpachecker.util.CFAUtils;

enum BlockKind {
  DEAD_END,
  BLOCK,
  FINITE_LOOP,
  ENDLESS_LOOP,
  IRREGULAR_BLOCK,
  FUNCTION_BODY
}

public class StructuredBlock2 {

  private final BlockKind kind;
  private final CFANode start;
  private final CFANode after;
  private final Set<CFANode> nodes = new HashSet<>();
  private final ImmutableSet<CFANode> allNodes;
  private final Set<StructuredBlock2> blocks = new HashSet<>();
  private StructuredBlock2 enclosingBlock = null;
  private final FunctionExitNode functionExitNode;

  public static StructuredBlock2 buildBodyFor(FunctionEntryNode pEntry) {
    return new StructuredFunctionBodyBuilder(pEntry).build();
  }

  public static StructuredBlock2 getBodyFor(FunctionEntryNode pEntry) {
    return StructuredFunctionBodyBuilder.byFunctionExit.get(pEntry.getExitNode()).getBody();
  }

  StructuredBlock2(
      BlockKind pKind,
      CFANode pStart,
      ImmutableSet<CFANode> pNodes,
      ImmutableSet<StructuredBlock2> pBlocks,
      CFANode pAfter,
      FunctionExitNode pFunctionExitNode) {
    assert (pAfter == null) == (pKind == BlockKind.DEAD_END || pKind == BlockKind.ENDLESS_LOOP)
        : "" + pAfter + " " + pKind;
    var f = pFunctionExitNode.getFunction();
    assert f.equals(pStart.getFunction());
    assert pAfter == null || f.equals(pAfter.getFunction());

    kind = pKind;
    start = pStart;
    after = pAfter;
    functionExitNode = pFunctionExitNode;

    assert pNodes.size() + pBlocks.size() > 0;

    var builder = StructuredFunctionBodyBuilder.byFunctionExit.get(functionExitNode);
    boolean check;
    for (CFANode n : pNodes) {
      assert n != functionExitNode || kind == BlockKind.FUNCTION_BODY;

      if (builder.hasNodeMappedToBlock(n)) {
        nodes.addAll(pNodes);
        throw new AssertionError(builder.nodeToBlock.get(n) + "\n" + this);
      }
      builder.mapNodeToBlock(n, this);

      check = nodes.add(n);
      assert check;
    }

    for (StructuredBlock2 b : pBlocks) {
      assert functionExitNode == b.functionExitNode;
      assert b != this;

      check = blocks.add(b);
      assert check;

      assert b.getEnclosingBlock() == null;
      b.enclosingBlock = this;
    }

    allNodes =
        FluentIterable.from(blocks)
            .transformAndConcat(StructuredBlock2::getAllNodes)
            .append(nodes)
            .toSet();
  }

  @Override
  public String toString() {
    return kind
        + " "
        + start.getFunctionName()
        + ":"
        + start.getNodeNumber()
        + " -"
        + nodes
        + "-> "
        + after;
  }

  public CFANode getStart() {
    return start;
  }

  public Optional<CFANode> getAfter() {
    return Optional.ofNullable(after);
  }

  public boolean isLoop() {
    return kind == BlockKind.ENDLESS_LOOP || kind == BlockKind.FINITE_LOOP;
  }

  public StructuredBlock2 getEnclosingBlock() {
    return enclosingBlock;
  }

  public Set<StructuredBlock2> getBlocks() {
    return blocks;
  }

  @SuppressWarnings("unused") // XXX
  private boolean hasEarlyReturn() {
    return getBlocks().stream().anyMatch(b -> b.after == functionExitNode)
        || getBlocks().stream().anyMatch(b -> b.hasEarlyReturn());
  }

  public Set<CFANode> getNodes() {
    return nodes;
  }

  public Set<CFANode> getAllNodes() {
    return allNodes;
  }

  private CFANode newAfter;
  private ImmutableList<CFAEdge> edgesToStart;
  private ImmutableList<CFAEdge> edgesFromStart;
  private ImmutableList<CFAEdge> edgesToAfter;
  private ImmutableList<CFANode> newNodes;

  public void retainDeclarations(FunctionCFAsWithMetadata pCfa) {
    // replace block with linear chain of declared variables
    // do not optimize decls out to be deterministic

    newAfter = after == null ? new CFATerminationNode(functionExitNode.getFunction()) : after;
    String functionName = functionExitNode.getFunctionName();

    pCfa.getCFANodes().values().removeAll(nodes);
    pCfa.getCFANodes().put(functionName, start);
    pCfa.getCFANodes().put(functionName, newAfter);

    CFAMutationUtils.removeAllLeavingEdges(start);
    edgesToStart = CFAUtils.enteringEdges(start).toList();
    edgesFromStart = CFAUtils.leavingEdges(start).toList();
    for (CFAEdge e : edgesToStart.reverse()) {
      if (allNodes.contains(e.getPredecessor())) {
        start.removeEnteringEdge(e);
      }
    }
    edgesToAfter = CFAUtils.enteringEdges(after).toList();
    for (CFAEdge e : edgesToAfter.reverse()) {
      if (allNodes.contains(e.getPredecessor())) {
        after.removeEnteringEdge(e);
      }
    }

    List<ADeclarationEdge> decls = new ArrayList<>();
    CFAVisitor vis =
        new CFATraversal.DefaultCFAVisitor() {
          @Override
          public TraversalProcess visitEdge(CFAEdge pEdge) {
            if (pEdge instanceof ADeclarationEdge) {
              decls.add((ADeclarationEdge) pEdge);
            }
            return TraversalProcess.CONTINUE;
          }
        };
    CFATraversal.dfs().traverseOnce(start, vis);

    CFANode next = start;
    ImmutableList.Builder<CFANode> newNodesBuilder = ImmutableList.builder();
    newNodesBuilder.add(after);
    for (ADeclarationEdge e : decls) {
      next = CFAMutationUtils.copyDeclarationEdge(e, next);
      newNodesBuilder.add(next);
    }
    newNodes = newNodesBuilder.build();
    pCfa.getCFANodes().putAll(functionName, newNodes);

    if (after == functionExitNode) {
      // we need return statement
      CFAMutationUtils.insertDefaultReturnStatementEdge(next, functionExitNode);

    } else {
      BlankEdge blank = new BlankEdge("", FileLocation.DUMMY, next, after, "");
      next.addLeavingEdge(blank);
      after.addEnteringEdge(blank);
    }
  }

  @SuppressWarnings("deprecation")
  public void rollback(FunctionCFAsWithMetadata pCfa) {
    String functionName = functionExitNode.getFunctionName();

    pCfa.getCFANodes().values().removeAll(newNodes);
    pCfa.getCFANodes().putAll(functionName, nodes);

    start.resetEnteringEdges(edgesToStart);
    start.resetLeavingEdges(edgesFromStart);
    after.resetEnteringEdges(edgesToAfter);
  }
}

class StructuredFunctionBodyBuilder {

  static final Map<FunctionExitNode, StructuredFunctionBodyBuilder> byFunctionExit = new TreeMap<>();

  private final FunctionEntryNode entry;
  private final FunctionExitNode exit;
  private StructuredBlock2 body;

  private final Map<CFANode, StructuredBlock2> entryToBasicBlock = new TreeMap<>();
  private final Map<CFANode, StructuredBlock2> entryToBlock = new TreeMap<>();
  final Map<CFANode, StructuredBlock2> nodeToBlock = new TreeMap<>();
  private final Map<CFANode, Condition> entryToCondition = new TreeMap<>();
  private final Map<CFANode, CFANode> doms = new TreeMap<>();
  private final Map<CFANode, CFANode> postdoms = new TreeMap<>();

  boolean hasNodeMappedToBlock(CFANode pNode) {
    return nodeToBlock.containsKey(pNode);
  }

  void mapNodeToBlock(CFANode pNode, StructuredBlock2 pBlock) {
    nodeToBlock.put(pNode, pBlock);
  }

  StructuredFunctionBodyBuilder(FunctionEntryNode pEntry) {
    entry = pEntry;
    exit = pEntry.getExitNode();
    CFAReversePostorder.assignIds(entry);
    byFunctionExit.put(exit, this);
  }

  public StructuredBlock2 getBody() {
    return body;
  }

  public StructuredBlock2 build() {
    // exclude function start dummy edge
    assert entry.getNumLeavingEdges() == 1;
    // returns block with 0 or multiple exits
    constructLinearBlocks(entry.getLeavingEdge(0).getSuccessor());
    findDominators();
    findPostdominators();

    Deque<StructuredBlock2> waitlist = new ArrayDeque<>();
    waitlist.addAll(entryToBasicBlock.values());

    while (!waitlist.isEmpty()) {
      while (!waitlist.isEmpty()) {
        waitlist = combineBranches(waitlist);
        waitlist = combineConsequent(waitlist);
      }

      waitlist = constructIrregularBlocks();
    }

    body =
        new StructuredBlock2(
            BlockKind.FUNCTION_BODY,
            entry,
            ImmutableSet.of(entry, exit),
            getTopBlocks(),
            exit,
            exit);
    return getBody();
  }

  private ImmutableSet<StructuredBlock2> getTopBlocks() {
    return entryToBlock.values().stream()
        .filter(b -> b.getEnclosingBlock() == null)
        .collect(ImmutableSet.toImmutableSet());
  }

  private void constructLinearBlocks(CFANode pStart) {
    if (entryToBlock.containsKey(pStart)) {
      return;
    }

    if (pStart.getNumLeavingEdges() == 0) {
      // nothing to do XXX empty?

    } else if (pStart.getNumLeavingEdges() == 1) {
      var block = constructBasicBlock(pStart);
      if (block.getAfter().isPresent()) {
        constructLinearBlocks(block.getAfter().orElseThrow());
      }

    } else if (pStart.getNumLeavingEdges() == 2) {
      // construct next blocks
      constructLinearBlocks(pStart.getLeavingEdge(0).getSuccessor());
      constructLinearBlocks(pStart.getLeavingEdge(1).getSuccessor());

    } else if (pStart.getNumLeavingEdges() != 1) {
      // wrong
      throw new AssertionError();
    }
  }

  private StructuredBlock2 constructBasicBlock(CFANode pStart) {
    ImmutableSet.Builder<CFANode> b = ImmutableSet.builder();
    CFANode node = pStart;
    b.add(node);
    assert pStart.getNumLeavingEdges() == 1;
    CFANode next = node.getLeavingEdge(0).getSuccessor();

    while (next.getNumEnteringEdges() == 1 && next.getNumLeavingEdges() == 1) {
      node = next;
      b.add(node);
      next = node.getLeavingEdge(0).getSuccessor();
    }

    BlockKind k;
    if (next instanceof CFATerminationNode && next.getNumEnteringEdges() == 1) {
      // include a dead end
      k = BlockKind.DEAD_END;
      b.add(next);
      next = null;

    } else {
      k = BlockKind.BLOCK;
    }

    StructuredBlock2 block =
        new StructuredBlock2(k, pStart, b.build(), ImmutableSet.of(), next, exit);
    entryToBasicBlock.put(pStart, block);

    if (block.getAfter().orElse(null) == block.getStart()) {
      // endless loop
      block =
          new StructuredBlock2(
              BlockKind.ENDLESS_LOOP,
              pStart,
              ImmutableSet.of(),
              ImmutableSet.of(block),
              null,
              exit);
    }

    entryToBlock.put(pStart, block);
    return block;
  }

  private static class Condition {
    private CFANode s0, s1, p;
    private final Set<CFANode> nodes = new HashSet<>();

    Condition(CFANode pNode) {
      assert pNode.getNumLeavingEdges() == 2;
      s0 = pNode.getLeavingEdge(0).getSuccessor();
      s1 = pNode.getLeavingEdge(1).getSuccessor();
      reachConditionEnd();
      p = pNode;
      reachConditionStart();
    }

    private void reachConditionEnd() {
      if (s0.getNumLeavingEdges() == 2 && s0.hasEdgeTo(s1)) {
        CFANode t0 = s0.getLeavingEdge(0).getSuccessor();
        CFANode t1 = s0.getLeavingEdge(1).getSuccessor();
        nodes.add(s0);
        s0 = t0 == s1 ? t1 : t0;
        reachConditionEnd();
      }

      if (s1.getNumLeavingEdges() == 2 && s1.hasEdgeTo(s0)) {
        CFANode t0 = s1.getLeavingEdge(0).getSuccessor();
        CFANode t1 = s1.getLeavingEdge(1).getSuccessor();
        nodes.add(s1);
        s1 = t0 == s0 ? t1 : t0;
        reachConditionEnd();
      }
    }

    private void reachConditionStart() {
      nodes.add(p);
      if (p.getNumEnteringEdges() == 1) {
        CFANode pred = p.getEnteringEdge(0).getPredecessor();
        if (pred.getNumLeavingEdges() == 2 && (pred.hasEdgeTo(s0) || pred.hasEdgeTo(s1))) {
          p = pred;
          reachConditionStart();
        }
      }
    }

    private ImmutableSet<CFANode> getNodes() {
      return ImmutableSet.copyOf(nodes);
    }
  }

  private Deque<StructuredBlock2> combineBranches(Deque<StructuredBlock2> pWaitlist) {
    Deque<StructuredBlock2> result = new ArrayDeque<>();

    while (!pWaitlist.isEmpty()) {
      StructuredBlock2 block = pWaitlist.poll();

      if (block.getStart().getNumEnteringEdges() == 1) {
        CFANode predNode = block.getStart().getEnteringEdge(0).getPredecessor();

        if (predNode.getNumLeavingEdges() == 2) {
          // can combine
          StructuredBlock2 newCondBlock = constructConditionalBlock(predNode);
          if (newCondBlock == null) {
            // cannot determine the block
            continue;
          }

          entryToCondition.remove(newCondBlock.getStart());
          entryToBlock.put(newCondBlock.getStart(), newCondBlock);
          pWaitlist.add(newCondBlock);
          result.add(newCondBlock);
        }
      }
    }

    return result;
  }

  private StructuredBlock2 constructConditionalBlock(CFANode pNode) {
    Condition c = new Condition(pNode);

    StructuredBlock2 b0 = entryToBlock.get(c.s0);
    StructuredBlock2 b1 = entryToBlock.get(c.s1);
    ImmutableSet<StructuredBlock2> bothBlocks = ImmutableSet.of(b0, b1);

    // empty branches
    if (b0 != null && b0.getAfter().orElse(null) == c.s1) {
      // b1 is actually after b0, branch-1 is only assume edge
      return new StructuredBlock2(
          BlockKind.BLOCK, c.p, c.getNodes(), ImmutableSet.of(b0), c.s1, exit);
    }
    if (b1 != null && b1.getAfter().orElse(null) == c.s0) {
      // b0 is actually after b1, and branch-0 is just empty
      return new StructuredBlock2(
          BlockKind.BLOCK, c.p, c.getNodes(), ImmutableSet.of(b1), c.s0, exit);
    }

    // loops
    if (b0 != null && b0.getAfter().orElse(null) == c.p && b1 != null && b1.getAfter().orElse(null) == c.p) {
      // it is a loop
      return new StructuredBlock2(
          BlockKind.ENDLESS_LOOP, c.p, c.getNodes(), bothBlocks, null, exit);
    }
    if (b0 != null && b0.getAfter().orElse(null) == c.p) {
      // it is a loop
      return new StructuredBlock2(
          BlockKind.FINITE_LOOP, c.p, c.getNodes(), ImmutableSet.of(b0), c.s1, exit);
    }
    if (b1 != null && b1.getAfter().orElse(null) == c.p) {
      // it is a loop
      return new StructuredBlock2(
          BlockKind.FINITE_LOOP, c.p, c.getNodes(), ImmutableSet.of(b1), c.s0, exit);
    }


    if (b0 == null || b1 == null) {
      // there are no blocks there yet
      entryToCondition.put(c.p, c);
      return null;
    }

    if (b0.getAfter().isEmpty() && b1.getAfter().isEmpty()) {
      // both are dead ends
      return new StructuredBlock2(BlockKind.DEAD_END, c.p, c.getNodes(), bothBlocks, null, exit);
    }

    if (b1.getAfter().isEmpty() || b1.getAfter().orElseThrow() == b0.getAfter().orElse(null)) {
      // they merge OR
      // b1 is dead-end => can exit this conditional block only through b0
      return new StructuredBlock2(
          BlockKind.BLOCK, c.p, c.getNodes(), bothBlocks, b0.getAfter().orElse(null), exit);
    }

    if (b0.getAfter().isEmpty() || b0.getAfter().orElse(null) == exit) {
      // b0 is dead-end => can exit this conditional block (only) through b1
      // OR b0 is early return
      return new StructuredBlock2(
          BlockKind.BLOCK, c.p, c.getNodes(), bothBlocks, b1.getAfter().orElse(null), exit);
    }

    if (b1.getAfter().orElse(null) == exit) {
      // b1 is early return => choose b0 as 'natural' exit
      return new StructuredBlock2(
          BlockKind.BLOCK, c.p, c.getNodes(), bothBlocks, b0.getAfter().orElse(null), exit);
    }

    // b0 and b1 leave to different nodes
    entryToCondition.put(c.p, c);
    return null;
  }

  private Deque<StructuredBlock2> combineConsequent(Deque<StructuredBlock2> pWaitlist) {
    Deque<StructuredBlock2> result = new ArrayDeque<>();

    while (!pWaitlist.isEmpty()) {
      StructuredBlock2 b1 = pWaitlist.poll();
      List<StructuredBlock2> blocks = new ArrayList<>();
      blocks.add(b1);

      CFANode nextNode = b1.getAfter().orElse(null);
      assert nextNode != b1.getStart();
      StructuredBlock2 b2 = nextNode == null ? null : entryToBlock.getOrDefault(nextNode, null);

      while (b2 != null) {
        blocks.add(b2);
        nextNode = b2.getAfter().orElse(null);
        b2 =
            (nextNode == null || nextNode == b1.getStart())
                ? null
                : entryToBlock.getOrDefault(nextNode, null);
      }

      if (blocks.size() > 1) {
        StructuredBlock2 c =
            new StructuredBlock2(
                nextNode == b1.getStart() ? BlockKind.ENDLESS_LOOP : BlockKind.BLOCK,
                b1.getStart(),
                ImmutableSet.of(),
                ImmutableSet.copyOf(blocks),
                nextNode == b1.getStart() ? null : nextNode,
                exit);
        entryToBlock.put(c.getStart(), c);
        result.add(c);
      }
    }

    return result;
  }

  private void findDominators() {
    doms.put(entry, entry);
    List<CFANode> nodes = new ArrayList<>();

    for (CFANode n : Traverser.forGraph(CFAUtils::successorsOf).depthFirstPostOrder(entry)) {
      nodes.add(n);
    }
    nodes = Lists.reverse(nodes);

    nodes.remove(entry);
    boolean changed = true;

    while (changed) {
      changed = false;
      for (CFANode n : nodes) { // for all nodes, b, in reverse postorder (except start node)
        CFANode newIdom = null;
        for (CFANode p : CFAUtils.predecessorsOf(n)) {
          if (doms.containsKey(p)) {
            newIdom = intersectDominators(p, newIdom);
          }
        }
        if (doms.get(n) != newIdom) {
          doms.put(n, newIdom);
          changed = true;
        }
      }
    }
  }

  private CFANode intersectDominators(CFANode p1, CFANode p2) {
    if (p2 == null) {
      return p1;
    }

    CFANode finger1 = p1;
    CFANode finger2 = p2;
    while (finger1 != finger2) {
      while (finger1.getReversePostorderId() < finger2.getReversePostorderId()) {
        finger1 = doms.get(finger1);
      }
      while (finger2.getReversePostorderId() < finger1.getReversePostorderId()) {
        finger2 = doms.get(finger2);
      }
    }
    return finger1;
  }

  private void findPostdominators() {
    List<CFANode> nodes = new ArrayList<>();

    // XXX or in #findDominators?
    for (CFANode n : Traverser.forGraph(CFAUtils::successorsOf).depthFirstPostOrder(entry)) {
      nodes.add(n);
    }

    for (CFANode n : nodes) {
      if (n.getNumLeavingEdges() == 0) {
        postdoms.put(n, n);
      }
    }
    nodes.removeAll(postdoms.keySet());
    //    System.out.println(nodes);

    boolean changed = true;
    while (changed) {
      changed = false;

      for (CFANode n : nodes) { // for all nodes, b, in postorder (not reversed (?))
        CFANode newIpostdom = null;

        for (CFANode s : CFAUtils.successorsOf(n)) {
          if (postdoms.containsKey(s)) {
            newIpostdom = intersectPostdominators(s, newIpostdom);
          }
        }

        if (newIpostdom == null) {
          // all successors have no postdoms initialized
          // it is last node of endless loop
          assert n.getNumLeavingEdges() == 1 : n.getFunctionName() + ":" + n.getNodeNumber();
          newIpostdom = n.getLeavingEdge(0).getSuccessor();
        }

        if (postdoms.get(n) != newIpostdom) {
          postdoms.put(n, newIpostdom);
          changed = true;
        }
      }
    }
  }

  private final CFANode intersectPostdominators(CFANode p1, CFANode p2) {
    if (p2 == null) {
      return p1;
    }

    CFANode finger1 = p1;
    CFANode finger2 = p2;
    while (finger1 != finger2) {
      while (finger1.getReversePostorderId() > finger2.getReversePostorderId()) {
        finger1 = postdoms.get(finger1);
      }
      while (finger2.getReversePostorderId() > finger1.getReversePostorderId()) {
        finger2 = postdoms.get(finger2);
      }
    }
    return finger1;
  }

  private Deque<StructuredBlock2> constructIrregularBlocks() {
    Deque<StructuredBlock2> result = new ArrayDeque<>();

    for (CFANode start : ImmutableSet.copyOf(entryToCondition.keySet())) {
      assert !entryToBlock.containsKey(start);
      CFANode after = postdoms.get(start);
      if (doms.get(after) == start) {
        // else there is a path around start i.e. this is not a 'block' with one entry
        StructuredBlock2 newIrrBlock = constructIrregularBlock(start, after);
        if (newIrrBlock != null) {
          entryToCondition.remove(start);
          entryToBlock.put(start, newIrrBlock);
          result.add(newIrrBlock);
        }
      }
    }

    return result;
  }

  private StructuredBlock2 constructIrregularBlock(CFANode pStart, CFANode pAfter) {
    ImmutableSet.Builder<CFANode> nodes = ImmutableSet.builder();
    HashSet<StructuredBlock2> blocks = new HashSet<>();
    Deque<StructuredBlock2> waitlist = new ArrayDeque<>();

    Condition c = entryToCondition.get(pStart);
    nodes.addAll(c.nodes);
    waitlist.add(entryToBlock.get(c.s0));
    waitlist.add(entryToBlock.get(c.s1));

    while (!waitlist.isEmpty()) {
      StructuredBlock2 b = waitlist.poll();
      blocks.add(b);
      if (entryToCondition.containsKey(b.getStart())) {
        nodes.addAll(entryToCondition.get(b.getStart()).nodes);
      }

      if (b.getAfter().isPresent() && b.getAfter().orElseThrow() != pAfter) {
        StructuredBlock2 next = entryToBlock.get(b.getAfter().orElseThrow());
        if (!blocks.contains(next)) {
          waitlist.add(next);
        }
      }
    }

    return new StructuredBlock2(
        BlockKind.IRREGULAR_BLOCK,
        pStart,
        nodes.build(),
        ImmutableSet.copyOf(blocks),
        pAfter,
        exit);
  }
}
