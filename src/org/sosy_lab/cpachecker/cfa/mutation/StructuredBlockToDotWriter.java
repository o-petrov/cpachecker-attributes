// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFAReversePostorder;
import org.sosy_lab.cpachecker.cfa.export.DOTBuilder;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdgeType;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionReturnEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionSummaryEdge;
import org.sosy_lab.cpachecker.util.CFAUtils;

/** This Writer can dump a cfa with blocks into a file. */
public class StructuredBlockToDotWriter {

  final FunctionCFAsWithMetadata cfa;
  int blockIndex = 0;

  public StructuredBlockToDotWriter(FunctionCFAsWithMetadata pCfa) {
    pCfa.getFunctions().values().forEach(CFAReversePostorder::assignIds);
    cfa = Preconditions.checkNotNull(pCfa);
  }

  /** dump the cfa with blocks and colourful nodes. */
  public void dump(final Path pDir, LogManager pLogger) {
    for (String functionName : cfa.getFunctions().keySet()) {
      Path blocksFile = pDir.resolve("sb__" + functionName + ".dot");

      try {
        MoreFiles.createParentDirectories(blocksFile);
      } catch (IOException e) {
        pLogger.logUserException(
            Level.WARNING, e, "Could not create parent directories to write blocks to dot file");
        return;
      }

      try (Writer w = Files.newBufferedWriter(blocksFile, StandardCharsets.UTF_8)) {
        dump(w, cfa.getFunctions().get(functionName), pLogger);
      } catch (IOException e) {
        pLogger.logUserException(Level.WARNING, e, "Could not write blocks to dot file");
        // ignore exception and continue analysis
      }
    }
  }

  /** dump the cfa with blocks and colourful nodes. */
  private void dump(
      final Appendable app, FunctionEntryNode entry, @SuppressWarnings("unused") LogManager pLogger)
      throws IOException {

    app.append("digraph blocked_CFA_of_" + entry.getFunctionName() + "_function {\n");
    final List<CFAEdge> edges = new ArrayList<>();

    // dump nodes of all blocks
    final Set<CFANode> finished = new HashSet<>();
    dumpBlock(app, finished, StructuredBlock2.getBodyFor(entry), edges, 0);

    // we have to dump edges after the nodes and sub-graphs,
    // because Dot generates wrong graphs for edges from an inner block to an outer block.
    for (CFAEdge edge : edges) {
      if (finished.contains(edge.getSuccessor())) {
        app.append(formatEdge(edge));
        }
      }

    app.append("}");
  }

  private static final String[] background = new String[] {"white", "lightgrey", "grey"};

  /** Dump the current block and all innerblocks of it. */
  private void dumpBlock(
      final Appendable app,
      final Set<CFANode> finished,
      final StructuredBlock2 pStructuredBlock2,
      final List<CFAEdge> edges,
      final int depth)
      throws IOException {
    // todo use some block-identifier instead of index as blockname?
    final String blockname = "b" + blockIndex++;
    app.append("subgraph cluster_" + blockname + " {\n");
    app.append("style=filled\n");
    app.append("fillcolor=" + background[depth % background.length] + "\n");
    app.append(
        "label=\""
            + pStructuredBlock2.toString().replace("to ", "to\\n").replace(" (", "\\n(")
            + "\"\n");

    // dump inner blocks
    for (StructuredBlock2 innerBlock : pStructuredBlock2.getBlocks()) {
      dumpBlock(app, finished, innerBlock, edges, depth + 1);
    }

    // - dump nodes, that are in current block and not in inner blocks
    // (nodes of inner blocks are 'finished')
    // - dump edges later to avoid ugly layouts
    // (nodes are in correct subgraphs already, but some targets of edges might not yet be handled)
    for (CFANode node : pStructuredBlock2.getNodes()) {
      if (finished.add(node)) {
        app.append(formatNode(node));
        Iterables.addAll(edges, CFAUtils.leavingEdges(node));
        FunctionSummaryEdge func = node.getEnteringSummaryEdge();
        if (func != null) {
          edges.add(func);
        }
      }
    }

    app.append("}\n");
  }

  private String formatNode(CFANode node) {
    String shape = "";
    if (node.isLoopStart()) {
      shape = "shape=doubleoctagon ";
    } else if (node.getNumLeavingEdges() > 0
        && node.getLeavingEdge(0).getEdgeType() == CFAEdgeType.AssumeEdge) {
      shape = "shape=diamond ";
    }

    String label =
        "label=\"N" + node.getNodeNumber() + "\\n" + node.getReversePostorderId() + "\\n" + node.getOutOfScopeVariables() + "\" ";
    return node.getNodeNumber() + " [" + shape + label + "]\n";
  }

  // method copied from org.sosy_lab.cpachecker.cfa.export.DOTBuilder.DotGenerator#formatEdge
  private static String formatEdge(CFAEdge edge) {
    StringBuilder sb = new StringBuilder();
    sb.append(edge.getPredecessor().getNodeNumber());
    sb.append(" -> ");
    sb.append(edge.getSuccessor().getNodeNumber());
    sb.append(" [label=\"");
    sb.append(DOTBuilder.escapeGraphvizLabel(edge.getDescription(), " "));
    sb.append("\"");
    if (edge instanceof FunctionSummaryEdge) {
      sb.append(" style=\"dotted\" arrowhead=\"empty\"");
    } else if (edge instanceof FunctionCallEdge) {
      sb.append(" style=\"dashed\" arrowhead=\"empty\"");
    } else if (edge instanceof FunctionReturnEdge) {
      sb.append(" style=\"dashed\" arrowhead=\"empty\"");
    }
    sb.append("]\n");
    return sb.toString();
  }
}
