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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.TreeMultimap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
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
public class FunctionCFAsWithMetadata extends ParseResult {
  private TreeMap<CFANode, Hedgehog> localEdges;
  private final MachineModel machinemodel;
  private final FunctionEntryNode mainFunction;
  private final Language language;

  private static FunctionCFAsWithMetadata original;

  private FunctionCFAsWithMetadata(
      MachineModel pMachineModel,
      NavigableMap<String, FunctionEntryNode> pFunctions,
      TreeMultimap<String, CFANode> pAllNodes,
      FunctionEntryNode pMainFunction,
      List<Path> pFileNames,
      Language pLanguage,
      List<Pair<ADeclaration, String>> pGlobalDeclarations) {
    super(pFunctions, pAllNodes, pGlobalDeclarations, pFileNames);
    assert pAllNodes.keySet().equals(pFunctions.keySet());
    assert pFunctions.values().contains(pMainFunction);
    assert pFunctions.entrySet().stream()
        .allMatch(pair -> pAllNodes.get(pair.getKey()).contains(pair.getValue()));
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
      for (CFAEdge edge : enteringEdges) {
        assert edge.getPredecessor()
            .getFunctionName()
            .equals(edge.getSuccessor().getFunctionName());
        assert CFAUtils.leavingEdges(edge.getPredecessor()).contains(edge);
      }
      leavingEdges = CFAUtils.leavingEdges(pNode).toList();
      for (CFAEdge edge : leavingEdges) {
        assert edge.getPredecessor()
            .getFunctionName()
            .equals(edge.getSuccessor().getFunctionName());
        assert CFAUtils.enteringEdges(edge.getSuccessor()).contains(edge);
      }
    }
  }

  /**
   * Store local edges. Use before {@link CFACreator#createCFA} processes the CFA, so this CFA can
   * be used after {@link #resetEdgesInNodes} to create another CFA.
   */
  public void saveEdgesInNodes() {
    // store present edges
    localEdges = new TreeMap<>();
    for (CFANode node : cfaNodes.values()) {
      localEdges.put(node, new Hedgehog(node));
    }

    makeConsistent();
    checkConsistency();
  }

  /**
   * Restore local edges. Use after analysis run, so this CFA is ready for mutation and processings
   * in {@link CFACreator#createCFA}.
   */
  @SuppressWarnings("deprecation") // uses three 'private' methods
  public void resetEdgesInNodes() {
    if (this != original) {
      original.resetEdgesInNodes();
    }

    for (Entry<CFANode, Hedgehog> entry : localEdges.entrySet()) {
      entry.getKey().resetNodeInfo();
      entry.getKey().resetEnteringEdges(entry.getValue().enteringEdges);
      entry.getKey().resetLeavingEdges(entry.getValue().leavingEdges);
    }

    makeConsistent();
    checkConsistency();

    for (CFANode node : localEdges.keySet()) {
      assert CFAUtils.enteringEdges(node)
          .allMatch(
              edge ->
                  edge.getPredecessor()
                      .getFunctionName()
                      .equals(edge.getSuccessor().getFunctionName()));
      assert CFAUtils.leavingEdges(node)
          .allMatch(
              edge ->
                  edge.getPredecessor()
                      .getFunctionName()
                      .equals(edge.getSuccessor().getFunctionName()));
    }
  }

  private void makeConsistent() {
    cfaNodes.values().retainAll(localEdges.keySet());
    localEdges.keySet().forEach(node -> cfaNodes.put(node.getFunctionName(), node));
    functions.keySet().retainAll(cfaNodes.keySet());
    cfaNodes
        .keySet()
        .forEach(
            name ->
                functions.put(
                    name,
                    FluentIterable.from(cfaNodes.get(name))
                        .filter(FunctionEntryNode.class)
                        .first()
                        .get()));
  }

  private void checkConsistency() {
    assert cfaNodes.values().size() == localEdges.keySet().size()
        : "nodes saved in original ParseResult and map [node -> saved edges] have different count of nodes";
    assert functions.keySet().equals(cfaNodes.keySet())
        : "entries and nodes are present for different sets of functions";
  }

  public static FunctionCFAsWithMetadata fromParseResult(
      ParseResult pParseResult,
      MachineModel pMachineModel,
      FunctionEntryNode pMainFunction,
      Language pLanguage) {

    TreeMap<String, FunctionEntryNode> newFunctions = new TreeMap<>();
    newFunctions.putAll(pParseResult.getFunctions());

    TreeMultimap<String, CFANode> newNodes = TreeMultimap.create();
    newNodes.putAll(pParseResult.getCFANodes());

    FunctionCFAsWithMetadata result =
        new FunctionCFAsWithMetadata(
            pMachineModel,
            newFunctions,
            newNodes,
            pMainFunction,
            new ArrayList<>(pParseResult.getFileNames()),
            pLanguage,
            new ArrayList<>(pParseResult.getGlobalDeclarations()));

    // save edges first time, so they will be restored
    result.saveEdgesInNodes();

    if (original == null) {
      original = result;
      // make another copy to return
      return fromParseResult(pParseResult, pMachineModel, pMainFunction, pLanguage);
    }

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

  @SuppressWarnings("deprecation")
  public static Pair<ParseResult, FunctionEntryNode> originalCopy() {
    Preconditions.checkState(original != null, "original CFA was not set");

    // reset edges in nodes
    for (CFANode node : original.localEdges.keySet()) {
      Hedgehog hedgehog = original.localEdges.get(node);
      node.resetNodeInfo();
      node.resetEnteringEdges(hedgehog.enteringEdges);
      node.resetLeavingEdges(hedgehog.leavingEdges);
    }

    original.makeConsistent();
    original.checkConsistency();

    // create copies
    TreeMap<String, FunctionEntryNode> newFunctions = new TreeMap<>();
    newFunctions.putAll(original.functions);

    TreeMultimap<String, CFANode> newNodes = TreeMultimap.create();
    newNodes.putAll(original.cfaNodes);

    return Pair.of(
        new ParseResult(
            newFunctions,
            newNodes,
            new ArrayList<>(original.globalDeclarations),
            new ArrayList<>(original.fileNames)),
        original.mainFunction);
  }
}
