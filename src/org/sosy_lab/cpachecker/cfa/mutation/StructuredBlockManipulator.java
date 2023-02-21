// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.graph.ValueGraphBuilder;
import java.nio.file.Path;
import java.util.Collection;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.FileOption.Type;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;

@Options(prefix = "cfaMutation")
class StructuredBlockManipulator
    extends CFAElementManipulator<StructuredBlock2, StructuredBlockManipulator.BlockDependency> {
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

  private static final String removeUnsupported =
      "Block manipulator can only prune, as structured program blocks cannot be removed independently.";

  public StructuredBlockManipulator(Configuration pConfig, LogManager pLogger)
      throws InvalidConfigurationException {
    super(pLogger, "structured blocks");
    pConfig.inject(this, StructuredBlockManipulator.class);
  }

  @Override
  public void constructElementGraph(FunctionCFAsWithMetadata pCfa) {
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
    new StructuredBlockToDotWriter(pCfa).dump(dirForBlocks, getLogger());

    // TODO decls?
  }

  private void addBlocks(StructuredBlock2 pBlock) {
    graph.addNode(pBlock);
    for (StructuredBlock2 sub : pBlock.getBlocks()) {
      graph.putEdgeValue(pBlock, sub, BlockDependency.INCLUDES);
      addBlocks(sub);
    }
  }

  @Override
  protected void removeElement(FunctionCFAsWithMetadata pCfa, StructuredBlock2 pBlock) {
    // TODO
    throw new UnsupportedOperationException("TODO actually remove blocks from CFA");
  }

  @Override
  public ImmutableSet<StructuredBlock2> whatRemainsIfRemove(Collection<StructuredBlock2> pChosen) {
    throw new UnsupportedOperationException(removeUnsupported);
  }

  @Override
  public void remove(FunctionCFAsWithMetadata pCfa, Collection<StructuredBlock2> pChosen) {
    throw new UnsupportedOperationException(removeUnsupported);
  }

  @Override
  protected void restoreElement(FunctionCFAsWithMetadata pCfa, StructuredBlock2 pChosen) {
    // TODO
    throw new UnsupportedOperationException("TODO actually restore blocks from CFA");
  }

  /*  private void pruneSubblocks(FunctionCFAsWithMetadata pCfa, StructuredBlock2 pBlock) {
  //    logInfo("removing subblocks of", pBlock);
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
  //  } */
}
