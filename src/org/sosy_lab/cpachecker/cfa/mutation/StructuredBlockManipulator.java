// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.ValueGraphBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.FileOption.Type;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;

@Options(prefix = "cfaMutation")
class StructuredBlockManipulator implements CFAElementManipulator<StructuredBlock2> {
  @FileOption(Type.OUTPUT_DIRECTORY)
  @Option(
      secure = true,
      name = "exportBlocksDir",
      description = "directory to dump function CFAs with marked up structured blocks")
  private Path dirForBlocks = Path.of("cfa-structured-blocks");

  enum BlockDependency {
    INCLUDES,
    DECL_USED;

    @Override
    public String toString() {
      switch (this) {
        case INCLUDES:
          return "includes";
        case DECL_USED:
          return "declares a name used";
        default:
          throw new AssertionError();
      }
    }
  }

  private MutableValueGraph<StructuredBlock2, BlockDependency> graph = null;
  private ImmutableSet<StructuredBlock2> currentLevel = null;
  private List<StructuredBlock2> previousLevels = new ArrayList<>();

  private List<StructuredBlock2> currentMutation = new ArrayList<>();
  private final LogManager logger;

  public StructuredBlockManipulator(Configuration pConfig, LogManager pLogger)
      throws InvalidConfigurationException {
    logger = Preconditions.checkNotNull(pLogger);
    pConfig.inject(this, StructuredBlockManipulator.class);
  }

  @Override
  public void setupFromCfa(FunctionCFAsWithMetadata pCfa) {
    Preconditions.checkState(graph == null, "Structured program blocks graph was already set up");
    graph = ValueGraphBuilder.directed().build();

    for (FunctionEntryNode entry : pCfa.getFunctions().values()) {
      StructuredBlock2 body = StructuredBlock2.buildBodyFor(entry);
      var diff =
          Sets.symmetricDifference(
              body.getAllNodes(), pCfa.getCFANodes().get(entry.getFunctionName()));
      assert diff.isEmpty() : entry.getFunctionName() + " diff is " + diff;
      addBlocks(body);
    }
    new StructuredBlockToDotWriter(pCfa).dump(dirForBlocks, logger);

    // TODO decls?
  }

  private void addBlocks(StructuredBlock2 pBlock) {
    graph.addNode(pBlock);
    for (StructuredBlock2 sub : pBlock.getBlocks()) {
      graph.putEdgeValue(pBlock, sub, BlockDependency.INCLUDES);
      addBlocks(sub);
    }
  }

  private void removeBlocks(StructuredBlock2 pBlock) {
    graph.removeNode(pBlock);
    for (StructuredBlock2 sub : pBlock.getBlocks()) {
      removeBlocks(sub);
    }
  }

  @Override
  public ImmutableValueGraph<StructuredBlock2, ?> getGraph() {
    return ImmutableValueGraph.copyOf(graph);
  }

  @Override
  public String getElementTitle() {
    return "structured blocks";
  }

  @Override
  public ImmutableSet<StructuredBlock2> getAllElements() {
    return ImmutableSet.copyOf(graph.nodes());
  }

  @Override
  public ImmutableSet<StructuredBlock2> getNextLevelElements() {
    Preconditions.checkState(graph != null, "Structured program blocks graph was not set up");
    if (currentLevel == null) {
      // return roots/sources
      // functions that are not called by any other
      currentLevel =
          graph.nodes().stream()
              .filter(
                  f ->
                      graph.predecessors(f).stream()
                          .collect(ImmutableSet.toImmutableSet())
                          .isEmpty())
              .collect(ImmutableSet.toImmutableSet());
    } else {
      currentLevel =
          FluentIterable.from(currentLevel)
              // filter out f removed from graph but remaining in 'currentLevel'
              .filter(f -> graph.nodes().contains(f))
              .transformAndConcat(f -> graph.successors(f))
              // filter out g that were in current or previous levels
              // filter out g that has caller not from current or previous level
              // (do not count 'callers' from recursive calls, i.e. with smaller postorder)
              .filter(
                  g ->
                      !previousLevels.contains(g)
                          && previousLevels.containsAll(
                              graph.predecessors(g).stream()
                                  .collect(ImmutableSet.toImmutableSet())))
              .toSet();
    }
    previousLevels.addAll(currentLevel);
    return currentLevel;
  }

  @Override
  public void remove(FunctionCFAsWithMetadata pCfa, Collection<StructuredBlock2> pChosen) {
    throw new UnsupportedOperationException(
        "structured program blocks cannot be removed independently");
  }

  @Override
  public void prune(FunctionCFAsWithMetadata pCfa, Collection<StructuredBlock2> pChosen) {
    for (StructuredBlock2 b : pChosen) {
      currentMutation.add(b);
      b.retainDeclarations(pCfa);
      removeBlocks(b);
    }
  }

  //  private void pruneSubblocks(FunctionCFAsWithMetadata pCfa, StructuredBlock2 pBlock) {
  //    logger.log(Level.INFO, "removing subblocks of", pBlock);
  //    currentMutation.add(pBlock);
  //    pCfa.getCFANodes().values().removeAll(pBlock.getNodes());
  //    prune(pCfa, pBlock.getSubBlocks());
  //    removeBlocks(pBlock);
  //  }
  //
  //  private void retainDeclarations(
  //      FunctionCFAsWithMetadata pCfa, StructuredBlock pBlock) {
  //    // replace block with linear chain of declared variables
  //    // do not optimize decls out to be deterministic
  //
  //    CFANode first = pBlock.getEntry().orElseThrow();
  //    CFANode after = pBlock.getNewNextNode();
  //    if (first == after || after == null) {
  //      return;
  //    }
  //    currentMutation.add(pBlock);
  //    String functionName = first.getFunctionName();
  //
  //    pCfa.getCFANodes().values().removeAll(pBlock.getNodes());
  //    pCfa.getCFANodes().put(functionName, first);
  //    pCfa.getCFANodes().put(functionName, after);
  //
  //    CFAMutationUtils.removeAllLeavingEdges(first);
  //    pBlock.removeEdgesTo(after);
  //
  //    List<ADeclarationEdge> decls = new ArrayList<>();
  //    CFAVisitor vis =
  //        new CFATraversal.DefaultCFAVisitor() {
  //          @Override
  //          public TraversalProcess visitEdge(CFAEdge pEdge) {
  //            if (pEdge instanceof ADeclarationEdge) {
  //              decls.add((ADeclarationEdge) pEdge);
  //            }
  //            return TraversalProcess.CONTINUE;
  //          }
  //        };
  //    CFATraversal.dfs().traverseOnce(first, vis);
  //
  //    CFANode next = first;
  //    ImmutableList.Builder<CFANode> newNodes = ImmutableList.builder();
  //    newNodes.add(after);
  //    for (ADeclarationEdge e : decls) {
  //      next = CFAMutationUtils.copyDeclarationEdge(e, next);
  //      newNodes.add(next);
  //    }
  //    pBlock.addNewNodes(newNodes.build());
  //    pCfa.getCFANodes().putAll(functionName, pBlock.getNewNodes());
  //
  //    if (pCfa.getFunctions().get(functionName).getExitNode() == after) {
  //      // we need return statement
  //      CFAMutationUtils.insertDefaultReturnStatementEdge(next, (FunctionExitNode) after);
  //
  //    } else {
  //      BlankEdge blank = new BlankEdge("", FileLocation.DUMMY, next, after, "");
  //      next.addLeavingEdge(blank);
  //      after.addEnteringEdge(blank);
  //    }
  //
  //    removeBlocks(pBlock);
  //  }

  @Override
  public void rollback(FunctionCFAsWithMetadata pCfa) {
    for (StructuredBlock2 b : Lists.reverse(currentMutation)) {
      addBlocks(b);
      b.rollback(pCfa);
    }
  }
}
