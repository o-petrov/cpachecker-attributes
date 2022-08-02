// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.TreeMultimap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.sosy_lab.cpachecker.cfa.CFACreator;
import org.sosy_lab.cpachecker.cfa.Language;
import org.sosy_lab.cpachecker.cfa.MutableCFA;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.util.CFAUtils;
import org.sosy_lab.cpachecker.util.Pair;

/**
 * This class stores CFA of every function and list of global declarations as {@link ParseResult}
 * does. It also stores machine model, main function, and language, as {@link MutableCFA} does.
 *
 * <p>Besides nodes, it also stores all edges in CFA. This is needed to undo changes from following
 * steps of CFA creation.
 */
// TODO consistent interface. Class will be immutable?
class FunctionCFAsWithMetadata extends ParseResult {
  private ImmutableSet<CFANode> localNodes = null;
  private TreeMap<CFANode, Hedgehog> localEdges = null;
  private final MachineModel machinemodel;
  private final FunctionEntryNode mainFunction;
  private final Language language;

  public FunctionCFAsWithMetadata(
      MachineModel pMachineModel,
      NavigableMap<String, FunctionEntryNode> pFunctions,
      TreeMultimap<String, CFANode> pAllNodes,
      FunctionEntryNode pMainFunction,
      List<Path> pFileNames,
      Language pLanguage,
      List<Pair<ADeclaration, String>> pGlobalDeclarations) {
    super(pFunctions, pAllNodes, pGlobalDeclarations, pFileNames);
    machinemodel = pMachineModel;
    mainFunction = pMainFunction;
    language = pLanguage;
  }

  public Language getLanguage() {
    return language;
  }

  public MachineModel getMachinemodel() {
    return machinemodel;
  }

  public FunctionEntryNode getMainFunction() {
    return mainFunction;
  }

  /**
   * Stores all edges incident to a node. No summary edges are stored, as interprocedural edges will
   * be removed.
   */
  private static class Hedgehog {
    final ImmutableList<CFAEdge> enteringEdges;
    final ImmutableList<CFAEdge> leavingEdges;

    Hedgehog(CFANode pNode) {
      enteringEdges = CFAUtils.enteringEdges(pNode).toList();
      leavingEdges = CFAUtils.leavingEdges(pNode).toList();
    }
  }

  /**
   * Store local edges. Use before {@link CFACreator#createCFA} processes the CFA, so this CFA can
   * be used after {@link #resetEdgesInNodes} to create another CFA.
   */
  public void saveEdgesInNodes() {
    // store present edges
    localNodes = ImmutableSet.copyOf(cfaNodes.values());
    localEdges = new TreeMap<>();
    for (CFANode node : cfaNodes.values()) {
      localEdges.put(node, new Hedgehog(node));
    }
  }

  /**
   * Restore local edges. Use after analysis run, so this CFA is ready for mutation and processings
   * in {@link CFACreator#createCFA}.
   */
  @SuppressWarnings("deprecation") // uses three 'private' methods
  public void resetEdgesInNodes() {
    if (localEdges == null) {
      return;
    }

    for (CFANode node : localNodes) {
      assert node != null;
      assert localEdges.get(node) != null : "new node " + node;
      assert localEdges.get(node).enteringEdges != null;
      assert localEdges.get(node).leavingEdges != null;
      node.resetNodeInfo();
      node.resetEnteringEdges(localEdges.get(node).enteringEdges);
      node.resetLeavingEdges(localEdges.get(node).leavingEdges);
    }

    localEdges = null;
    cfaNodes.values().retainAll(localNodes);
    for (CFANode n : localNodes) {
      cfaNodes.put(n.getFunctionName(), n);
    }
    localNodes = null;
  }

  public static FunctionCFAsWithMetadata fromParseResult(
      ParseResult pParseResult,
      MachineModel pMachineModel,
      FunctionEntryNode pMainFunction,
      Language pLanguage) {
    FunctionCFAsWithMetadata result =
        new FunctionCFAsWithMetadata(
            pMachineModel,
            pParseResult.getFunctions(),
            pParseResult.getCFANodes(),
            pMainFunction,
            pParseResult.getFileNames(),
            pLanguage,
            pParseResult.getGlobalDeclarations());
    // save edges first time, so they will be restored
    result.saveEdgesInNodes();
    return result;
  }

  public ParseResult copyAsParseResult() {
    // save local edges
    saveEdgesInNodes();
    // create copies that can be modified in #createCFA
    // so localCfa has clean copies (and edges in nodes are saved above)
    TreeMap<String, FunctionEntryNode> newFunctions = new TreeMap<>();
    newFunctions.putAll(functions);
    TreeMultimap<String, CFANode> newNodes = TreeMultimap.create();
    newNodes.putAll(cfaNodes);
    return new ParseResult(
        newFunctions, newNodes, new ArrayList<>(globalDeclarations), new ArrayList<>(fileNames));
  }
}
