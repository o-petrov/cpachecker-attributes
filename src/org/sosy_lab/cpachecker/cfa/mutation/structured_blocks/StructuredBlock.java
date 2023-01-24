// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation.structured_blocks;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.cpachecker.cfa.CFAReversePostorder;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.util.CFAUtils;

/** Stores a block exit node and the node outside the exit */
class BlockExit {
  private CFANode exit;
  private CFANode after;

  public BlockExit(CFANode pExit, CFANode pAfter) {
    exit = Preconditions.checkNotNull(pExit);
    Preconditions.checkArgument(exit.hasEdgeTo(pAfter));
    after = pAfter;
  }

  public CFANode getExitNode() {
    return exit;
  }

  public CFANode getNodeAfter() {
    return after;
  }

  @Override
  public String toString() {
    return "block exit " + exit + " -> " + after;
  }
}

public class StructuredBlock {
  private static final Map<StructuredBlock, StructuredBlock> enclosingBlock = new HashMap<>();
  private static final Multimap<StructuredBlock, StructuredBlock> containedBlocks =
      ArrayListMultimap.create();

  private enum BlockKind {
    EMPTY_BLOCK,
    BASIC_BLOCK,
    COMPOUND_BLOCK,
    CONDITIONAL_BLOCK,
    LOOP_BLOCK,
    FUNCTION_BODY
  }

  // this node is the only entry to the block
  private final ImmutableSet<CFANode> entries;
  // all nodes inside this block (includes entry, dead ends, and exit; excludes nodeAfterExit)
  private final ImmutableSet<CFANode> nodes;
  // these nodes are other ends reachable from the entry
  private final ImmutableSet<CFANode> deadEnds;
  // this node is the only exit from the block
  private final ImmutableSet<BlockExit> exits;
  // this is a node that has all edges leaving this block as entering
  private Optional<CFANode> nodeAfterExit = null;
  // kind of this block
  private final BlockKind kind;

  // save info for rollback
  // edges from the entry before removing this block
  private ImmutableList<CFAEdge> leavingFirstNode = null;
  // edges to the node after this block before removing this block
  private ImmutableList<CFAEdge> enteringNextNode = null;
  private ImmutableList<CFANode> newNodes = null;
  private CFANode newNextNode;
  private ImmutableList<CFAEdge> enteringFirstNode;

  @Override
  public String toString() {
    CFANode node = getEntry().orElse(getNodeAfter().orElse(null));
    if (node == null && getExit().isPresent()) {
      node = getExit().orElseThrow().getExitNode();
    }
    return kind
        + " from "
        + (node == null ? "???" : node.getFunctionName() + ":")
        + (getEntries().size() == 1 ? getEntry().orElseThrow() : getEntries())
        + " to "
        + (getExits().size() == 1 ? getExit().orElseThrow() : getExits())
        + " (next: "
        + nodeAfterExit
        + "; dead ends: "
        + deadEnds
        + ")";
  }

  private static Map<CFANode, StructuredBlock> nodeToSmallestBlock = new TreeMap<>();
  private static Map<CFANode, StructuredBlock> entryToBiggestBlock = new TreeMap<>();
  private static Map<CFANode, StructuredBlock> exitToBiggestBlock = new TreeMap<>();

  private static boolean inBlock(StructuredBlock pBlock, StructuredBlock toFind, CFANode pNode) {
    if (pBlock == toFind) {
      return true;
    }

    for (StructuredBlock sub : pBlock.getSubBlocks()) {
      if (sub.getNodes().contains(pNode)) {
        assert sub == toFind || inBlock(sub, toFind, pNode);
        return true;
      }
    }

    return false;
  }

  private static boolean isInfiniteLoop(StructuredBlock pBlock) {
    return pBlock.kind == BlockKind.LOOP_BLOCK && pBlock.getExits().isEmpty();
  }

  private StructuredBlock(
      BlockKind pKind,
      //      FunctionExitNode pFunctionExit,
      ImmutableSet<CFANode> pEntries,
      ImmutableSet<CFANode> pNodes,
      ImmutableSet<CFANode> pDeadEnds,
      ImmutableSet<BlockExit> pExits,
      List<StructuredBlock> pBlocks) {
    assert pNodes.containsAll(
        FluentIterable.from(pBlocks).transformAndConcat(StructuredBlock::getNodes).toSet());
    assert pDeadEnds.containsAll(
        FluentIterable.from(pBlocks).transformAndConcat(StructuredBlock::getDeadEnds).toSet());
    kind = pKind;
    entries = pEntries;
    nodes = pNodes;
    deadEnds = pDeadEnds;
    exits = pExits;

    containedBlocks.putAll(this, pBlocks);
    for (var b : pBlocks) {
      enclosingBlock.put(b, this);
    }

    ImmutableSet<CFANode> afters =
        FluentIterable.from(exits).transform(BlockExit::getNodeAfter).toSet();

    //    if (afters.size() >= 2) {
    //      System.out.println(
    //          entries.asList().get(0).getFunctionName() + ":" + kind + " " + entries);
    //      System.out.println(
    //          "nodes " + nodes + " dead ends " + deadEnds +
    //          " exits " + exits + " afters " + afters);
    //    }

    switch (kind) {
      case EMPTY_BLOCK:
      case BASIC_BLOCK:
        assert pBlocks.isEmpty();
        assert entries.size() == 1;
        assert exits.size() + deadEnds.size() == 1;
        break;

      case COMPOUND_BLOCK:
        assert pBlocks.size() > 1;
        assert entries.size() == 1;
        assert afters.size() == 1
            || afters.stream().anyMatch(n -> n instanceof FunctionExitNode)
            || (afters.size() == 0
                && (deadEnds.size() > 0 || isInfiniteLoop(pBlocks.get(pBlocks.size() - 1))));
        break;

      case CONDITIONAL_BLOCK:
        assert pBlocks.size() == 2;
        assert entries.size() == 1;
        assert exits.size() + deadEnds.size() >= 2;
        assert afters.size() <= 1 || afters.stream().anyMatch(n -> n instanceof FunctionExitNode);
        break;

      case LOOP_BLOCK:
        assert entries.size() >= 1;
        break;

      case FUNCTION_BODY:
        assert entries.size() == 1;
        assert exits.size() == 0;
        assert deadEnds.stream().filter(n -> n instanceof FunctionExitNode).count() == 1;
        break;

      default:
        throw new AssertionError();
    }

    if (afters.size() == 1) {
      nodeAfterExit = Optional.of(Iterables.getOnlyElement(afters));
    } else {
      nodeAfterExit = Optional.empty();
    }

    for (CFANode n : nodes) {
      StructuredBlock b = nodeToSmallestBlock.get(n);

      if (b == null) {
        nodeToSmallestBlock.put(n, this);

      } else {
        assert inBlock(this, b, n)
            : n.getFunctionName()
                + ":"
                + n.getNodeNumber()
                + " is already in "
                + b
                + ", not in "
                + this;
      }
    }

    for (BlockExit e : exits) {
      StructuredBlock b = exitToBiggestBlock.get(e.getExitNode());
      assert b == null || inBlock(this, b, e.getExitNode());
      exitToBiggestBlock.put(e.getExitNode(), this);
    }

    assert ImmutableSet.copyOf(pBlocks).size() == pBlocks.size();
  }

  public ImmutableList<StructuredBlock> getSubBlocks() {
    return ImmutableList.copyOf(containedBlocks.get(this));
  }

  public Optional<StructuredBlock> getEnclosingBlock() {
    return Optional.ofNullable(enclosingBlock.get(this));
  }

  public ImmutableSet<CFANode> getEntries() {
    return entries;
  }

  public Optional<CFANode> getEntry() {
    if (entries.size() == 1) {
      return Optional.of(Iterables.getOnlyElement(entries));
    } else {
      return Optional.empty();
    }
  }

  public ImmutableSet<CFANode> getNodes() {
    return nodes;
  }

  public ImmutableSet<CFANode> getDeadEnds() {
    return deadEnds;
  }

  public ImmutableSet<BlockExit> getExits() {
    return exits;
  }

  public Optional<BlockExit> getExit() {
    if (exits.size() == 1) {
      return Optional.of(Iterables.getOnlyElement(exits));
    } else {
      return Optional.empty();
    }
  }

  public Optional<CFANode> getNodeAfter() {
    return nodeAfterExit;
  }

  private static StructuredBlock constructCompoundBlock(List<StructuredBlock> pBlocks) {
    for (int i = 1; i < pBlocks.size(); i++) {
      assert pBlocks.get(i - 1).getNodeAfter().orElseThrow()
              == pBlocks.get(i).getEntry().orElseThrow()
          : "block " + pBlocks.get(i) + " is not after " + pBlocks.get(i - 1);
    }
    return new StructuredBlock(
        BlockKind.COMPOUND_BLOCK,
        ImmutableSet.of(pBlocks.get(0).getEntry().orElseThrow()),
        FluentIterable.from(pBlocks).transformAndConcat(StructuredBlock::getNodes).toSet(),
        FluentIterable.from(pBlocks).transformAndConcat(StructuredBlock::getDeadEnds).toSet(),
        pBlocks.get(pBlocks.size() - 1).getExits(), // can be no exits if it is a dead end
        pBlocks);
  }

  private static StructuredBlock constructBasicBlock(CFANode pFirst) {
    assert pFirst.getNumLeavingEdges() < 2;

    if (pFirst.getNumLeavingEdges() == 0) {
      // immediately a dead end
      return new StructuredBlock(
          BlockKind.BASIC_BLOCK,
          ImmutableSet.of(pFirst),
          ImmutableSet.of(pFirst),
          ImmutableSet.of(pFirst),
          ImmutableSet.of(),
          ImmutableList.of());
    }

    ImmutableSet.Builder<CFANode> b = ImmutableSet.builder();
    CFANode node = pFirst;
    b.add(node);
    CFANode next = node.getLeavingEdge(0).getSuccessor();

    while (next.getNumEnteringEdges() == 1 && next.getNumLeavingEdges() == 1) {
      node = next;
      b.add(node);
      next = node.getLeavingEdge(0).getSuccessor();
    }

    if (next.getNumLeavingEdges() == 0 && next.getNumEnteringEdges() == 1) {
      // include a dead end
      b.add(next);
      return new StructuredBlock(
          BlockKind.BASIC_BLOCK,
          ImmutableSet.of(pFirst),
          b.build(),
          ImmutableSet.of(next),
          ImmutableSet.of(),
          ImmutableList.of());
    }

    return new StructuredBlock(
        BlockKind.BASIC_BLOCK,
        ImmutableSet.of(pFirst),
        b.build(),
        ImmutableSet.of(),
        ImmutableSet.of(new BlockExit(node, next)),
        ImmutableList.of());
  }

  private static StructuredBlock constructEmptyBlock(CFANode pFirst, List<CFANode> pWing) {
    CFANode after = pWing.get(pWing.size() - 1);
    CFANode exit = pWing.get(pWing.size() - 2);

    return new StructuredBlock(
        BlockKind.EMPTY_BLOCK,
        ImmutableSet.of(pFirst),
        ImmutableSet.copyOf(pWing.subList(0, pWing.size() - 1)),
        ImmutableSet.of(),
        ImmutableSet.of(new BlockExit(exit, after)),
        ImmutableList.of());
  }

  private static class Conditional {
    private final List<CFANode> left = new ArrayList<>();
    private final List<CFANode> right = new ArrayList<>();
    private final Set<CFANode> nodes = new TreeSet<>();
    private final CFANode successor0;
    private final CFANode successor1;

    public Conditional(CFANode pNode) {
      assert pNode.getNumLeavingEdges() == 2;
      left.add(pNode);
      right.add(pNode);
      CFANode s1 = pNode.getLeavingEdge(0).getSuccessor();
      CFANode s2 = pNode.getLeavingEdge(1).getSuccessor();

      assert Sets.intersection(
                  CFAUtils.predecessorsOf(s1).append(CFAUtils.predecessorsOf(s2)).toSet(),
                  CFAUtils.predecessorsOf(pNode).toSet())
              .isEmpty()
          : pNode.getFunctionName()
              + ":"
              + pNode.getNodeNumber()
              + " -> { "
              + s1
              + ", "
              + s2
              + " }";
      left.add(s1);
      right.add(s2);
      nodes.addAll(left);
      nodes.addAll(right);

      boolean changed = true;

      while (changed) {
        changed = false;

        if (s1.getNumLeavingEdges() == 2
            && nodes.containsAll(CFAUtils.predecessorsOf(s1).toSet())) {

          if (s1.getLeavingEdge(0).getSuccessor() == s2) {
            s1 = s1.getLeavingEdge(1).getSuccessor();
            left.add(s1);
            changed = true;

          } else if (s1.getLeavingEdge(1).getSuccessor() == s2) {
            s1 = s1.getLeavingEdge(0).getSuccessor();
            left.add(s1);
            changed = true;
          }

        } else if (s2.getNumLeavingEdges() == 2
            && nodes.containsAll(CFAUtils.predecessorsOf(s2).toSet())) {

          if (s2.getLeavingEdge(0).getSuccessor() == s1) {
            s2 = s2.getLeavingEdge(1).getSuccessor();
            right.add(s2);
            changed = true;

          } else if (s2.getLeavingEdge(1).getSuccessor() == s1) {
            s2 = s2.getLeavingEdge(0).getSuccessor();
            right.add(s2);
            changed = true;
          }
        }

        nodes.addAll(left);
        nodes.addAll(right);

      }

      successor0 = s1;
      successor1 = s2;
      nodes.remove(s1);
      nodes.remove(s2);
    }
  }

  private static StructuredBlock constructConditionalBlock(CFANode pFirst) {
    Conditional c = new Conditional(pFirst);

    StructuredBlock b1 =
        CFAUtils.predecessorsOf(c.successor0).filter(p -> !c.nodes.contains(p)).isEmpty()
            ? startingFrom(c.successor0)
            : constructEmptyBlock(pFirst, c.left);
    StructuredBlock b2 =
        CFAUtils.predecessorsOf(c.successor1).filter(p -> !c.nodes.contains(p)).isEmpty()
            ? startingFrom(c.successor1)
            : constructEmptyBlock(pFirst, c.right);

    return new StructuredBlock(
        BlockKind.CONDITIONAL_BLOCK,
        ImmutableSet.of(pFirst),
        FluentIterable.from(c.nodes).append(b1.getNodes()).append(b2.getNodes()).toSet(),
        FluentIterable.from(b1.getDeadEnds()).append(b2.getDeadEnds()).toSet(),
        FluentIterable.from(b1.getExits()).append(b2.getExits()).toSet(),
        ImmutableList.of(b1, b2));
  }

  public static StructuredBlock functionBody(FunctionEntryNode pEntry) {
    if (entryToBiggestBlock.containsKey(pEntry)) {
      return entryToBiggestBlock.get(pEntry);
    }

    CFAReversePostorder.assignIds(pEntry);
    List<StructuredBlock> blocks = new ArrayList<>();

    // exclude function start dummy edge
    assert pEntry.getNumLeavingEdges() == 1;
    // returns block with 0 or multiple exits
    StructuredBlock lastBlock = startingFrom(pEntry.getLeavingEdge(0).getSuccessor());

    // if there are early returns or weird goto, blocks are not structured
    // collect them in function body
    Deque<CFANode> waitlist = new ArrayDeque<>();
    Set<CFANode> seenset = new HashSet<>();

    blocks.add(lastBlock);
    for (BlockExit be : lastBlock.getExits()) {
      if (seenset.add(be.getNodeAfter())) {
        waitlist.add(be.getNodeAfter());
      }
    }

    while (!waitlist.isEmpty()) {
      lastBlock = startingFrom(waitlist.poll());
      blocks.add(lastBlock);
      for (BlockExit be : lastBlock.getExits()) {
        if (seenset.add(be.getNodeAfter())) {
          waitlist.add(be.getNodeAfter());
        }
      }
    }

    boolean changed = true;
    StructuredBlock result = null;
    while (changed) {
      result =
          new StructuredBlock(
              BlockKind.FUNCTION_BODY,
              ImmutableSet.of(pEntry),
              FluentIterable.from(blocks)
                  .transformAndConcat(StructuredBlock::getNodes)
                  .append(pEntry)
                  .toSet(),
              FluentIterable.from(blocks).transformAndConcat(StructuredBlock::getDeadEnds).toSet(),
              ImmutableSet.of(),
              ImmutableList.copyOf(blocks));

      for (StructuredBlock b : blocks) {
        if (b.getEntry().isPresent()) {
          ImmutableList<CFANode> preds =
              CFAUtils.predecessorsOf(b.getEntry().orElseThrow())
                  .filter(n -> !(n instanceof FunctionEntryNode))
                  .toList();
          if (!preds.isEmpty()) {
            StructuredBlock pred = exitsSmallestCommonBlock(preds);
            if (pred != result) {
              if (enclosingBlock.get(pred).kind == BlockKind.COMPOUND_BLOCK) {
                appendToCompound(enclosingBlock.get(pred), b);
              } else {
                throw new AssertionError(pred);
              }
              changed = true;
            }
          }
        }
      }
    }

    entryToBiggestBlock.put(pEntry, result);
    return result;
  }

  private static void appendToCompound(StructuredBlock pCompoundBlock, StructuredBlock pBlock) {
    containedBlocks.get(pCompoundBlock).add(pBlock);
    constructCompoundBlock(pCompoundBlock.getSubBlocks());
    containedBlocks.removeAll(pCompoundBlock);
    enclosingBlock.remove(pCompoundBlock);
  }

  private static StructuredBlock startingFrom(CFANode pFirst) {
    if (entryToBiggestBlock.get(pFirst) != null) {
      return entryToBiggestBlock.get(pFirst);
    }

    List<StructuredBlock> blocks = new ArrayList<>();
    StructuredBlock lastBlock = null;

    AddNextBlock:
    while (true) {
      assert pFirst.getEnteringSummaryEdge() == null;
      assert pFirst.getLeavingSummaryEdge() == null;

      if (pFirst.getNumLeavingEdges() == 0) {
        // no leaving edges -- this block consists of just this node
        lastBlock = constructBasicBlock(pFirst);

      } else if (pFirst.getNumLeavingEdges() == 1) {
        // it is linear block
        lastBlock = constructBasicBlock(pFirst);

      } else if (pFirst.getNumLeavingEdges() == 2) {
        // it is conditional block
        lastBlock = constructConditionalBlock(pFirst);

      } else {
        // leaving edges != 0, 1, 2
        throw new AssertionError();
      }

      blocks.add(lastBlock);
      if (lastBlock.getNodeAfter().isEmpty()) {
        break;
      }
      pFirst = lastBlock.getNodeAfter().orElseThrow();

      final var prevNodes = lastBlock.getNodes();
      if (CFAUtils.predecessorsOf(pFirst).anyMatch(p -> !prevNodes.contains(p))) {
        // that means some other block ends here too
        break AddNextBlock;
      }
    }

    assert blocks.size() > 0;
    StructuredBlock result;
    if (blocks.size() == 1) {
      result = blocks.get(0);
    } else {
      result = constructCompoundBlock(blocks);
    }

    entryToBiggestBlock.put(result.getEntry().orElseThrow(), result);
    for (BlockExit be : result.getExits()) {
      exitToBiggestBlock.put(be.getExitNode(), result);
    }

    return result;
  }

  private static StructuredBlock smallestEnclosingBlock(StructuredBlock b1, StructuredBlock b2) {
    if (b1.getNodes().containsAll(b2.getNodes())) {
      return b1;

    } else if (b2.getNodes().containsAll(b1.getNodes())) {
      return b2;

    } else {
      return smallestEnclosingBlock(
          b1.getEnclosingBlock().orElseThrow(), b2.getEnclosingBlock().orElseThrow());
    }
  }

  private static StructuredBlock exitsSmallestCommonBlock(Iterable<CFANode> pNodes) {
    return FluentIterable.from(pNodes).transform(n -> exitToBiggestBlock.get(n)).stream()
        .reduce(StructuredBlock::smallestEnclosingBlock)
        .orElseThrow();
  }

  private static StructuredBlock constructLoopBlock(CFANode pFirst) {
    List<StructuredBlock> blocks = new ArrayList<>();
    List<CFANode> entries = new ArrayList<>();
    List<BlockExit> exits = new ArrayList<>();
    List<CFANode> nodes = new ArrayList<>();
    StructuredBlock block = null;
    CFANode node = pFirst;

    do {
      nodes.add(node);
      if (!CFAUtils.predecessorsOf(node).filter(n -> !nodes.contains(n)).isEmpty()) {
        entries.add(node);
      }

      if (node.getNumLeavingEdges() == 2) {
        // node can be loop exit
        boolean hasPath1 = false;
        boolean hasPath2 = false;
        CFANode next1 = node.getLeavingEdge(0).getSuccessor();
        CFANode next2 = node.getLeavingEdge(1).getSuccessor();
        try {
          hasPath1 =
              CFAUtils.existsPath(
                  next1, pFirst, CFAUtils::leavingEdges, ShutdownNotifier.createDummy());
          hasPath2 =
              CFAUtils.existsPath(
                  next2, pFirst, CFAUtils::leavingEdges, ShutdownNotifier.createDummy());
        } catch (InterruptedException e) {
          // impossible
        }

        if (!hasPath1 && !hasPath2) {
          // no way to first node -- impossible
          throw new AssertionError();

        } else if (hasPath1 != hasPath2) {
          // one is a way out
          exits.add(new BlockExit(node, hasPath1 ? next2 : next1));
          // exit node successor inside loop
          node = hasPath1 ? next1 : next2;

        } else {
          // add conditional block
          block = constructConditionalBlock(node);
          blocks.add(block);
          nodes.addAll(block.getNodes());

          if (block.getNodeAfter().isPresent()) {
            node = block.getNodeAfter().orElseThrow();

          } else {
            // multiple next blocks
            Deque<BlockExit> waitlist = new ArrayDeque<>();
            Set<CFANode> seenset = new HashSet<>();

            for (BlockExit be : block.getExits()) {
              if (seenset.add(be.getNodeAfter())) {
                waitlist.add(be);
              }
            }

            while (!waitlist.isEmpty()) {
              BlockExit currentExit = waitlist.poll();
              StructuredBlock nextBlock = startingFrom(currentExit.getNodeAfter());

              if (nextBlock.getExit().isEmpty() && nextBlock.reachesFunctionExit()) {
                // block is out of loop
                exits.add(currentExit);

              } else {
                blocks.add(nextBlock);
                for (BlockExit be : nextBlock.getExits()) {
                  if (seenset.add(be.getNodeAfter())) {
                    waitlist.add(be);
                  }
                }
              }
            }
          }
        }

      } else if (node.getNumLeavingEdges() == 1) {
        block = constructBasicBlock(node);
        blocks.add(block);
        nodes.addAll(block.getNodes());
        node = block.getNodeAfter().orElse(pFirst); // XXX

      } else {
        throw new AssertionError();
      }
    } while (node != pFirst);

    return new StructuredBlock(
        BlockKind.LOOP_BLOCK,
        ImmutableSet.copyOf(entries),
        ImmutableSet.copyOf(nodes),
        FluentIterable.from(blocks).transformAndConcat(StructuredBlock::getDeadEnds).toSet(),
        ImmutableSet.copyOf(exits),
        ImmutableList.copyOf(blocks));
  }

  private boolean reachesFunctionExit() {
    return deadEnds.stream().anyMatch(n -> n instanceof FunctionExitNode);
  }

  public void removeEdgesTo(CFANode pNode) {
    CFAUtils.enteringEdges(getEntry().orElseThrow())
        .filter(edge -> nodes.contains(edge.getPredecessor()))
        .toList()
        .forEach(edge -> getEntry().orElseThrow().removeEnteringEdge(edge));
    CFAUtils.enteringEdges(pNode)
        .filter(edge -> nodes.contains(edge.getPredecessor()))
        .toList()
        .forEach(edge -> pNode.removeEnteringEdge(edge));
  }

  /** nodes that are placed instead of this block */
  public void addNewNodes(ImmutableList<CFANode> pNodes) {
    newNodes = pNodes;
  }

  /** nodes that are placed instead of this block */
  public ImmutableList<CFANode> getNewNodes() {
    return newNodes;
  }

  public void setNewNextNodeAndSaveEdges(CFANode pNode) {
    newNextNode = pNode;
    leavingFirstNode = CFAUtils.leavingEdges(getEntry().orElseThrow()).toList();
    enteringFirstNode = CFAUtils.enteringEdges(getEntry().orElseThrow()).toList();
    enteringNextNode = CFAUtils.enteringEdges(newNextNode).toList();
  }

  public CFANode getNewNextNode() {
    return newNextNode;
  }

  @SuppressWarnings("deprecation")
  public void resetEdges() {
    getEntry().orElseThrow().resetLeavingEdges(leavingFirstNode);
    getEntry().orElseThrow().resetEnteringEdges(enteringFirstNode);
    newNextNode.resetEnteringEdges(enteringNextNode);
  }

  public String getFunctionName() {
    return entries.iterator().next().getFunctionName();
  }
}
