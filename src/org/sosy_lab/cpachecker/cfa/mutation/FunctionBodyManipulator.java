// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ImmutableValueGraph;
import com.google.common.graph.MutableValueGraph;
import com.google.common.graph.Traverser;
import com.google.common.graph.ValueGraphBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.FunctionCallCollector;
import org.sosy_lab.cpachecker.cfa.Language;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCall;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallExpression;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.postprocessing.function.ThreadCreateTransformer;
import org.sosy_lab.cpachecker.cfa.postprocessing.function.ThreadCreateTransformer.ThreadFinder;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CVoidType;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFAUtils;

class FunctionBodyManipulator implements CFAElementManipulator<FunctionBodyManipulator.FunctionElement> {

  enum FunctionCall {
    DIRECT_CALL,
    POINTER_CALL,
    THREAD_CREATION;

    @Override
    public String toString() {
      switch (this) {
        case DIRECT_CALL:
          return "calls";
        case POINTER_CALL:
          return "calls via pointer";
        case THREAD_CREATION:
          return "creates new thread and calls";
        default:
          throw new AssertionError();
      }
    }
  }

  static class FunctionElement {
    private final String name;
    private final FunctionEntryNode entry;
    private final NavigableSet<CFANode> oldNodes;
    private NavigableSet<CFANode> newNodes = null;
    private ImmutableList<CFAEdge> halfConnectedEdges = ImmutableList.of();
    private int callHeight = -1; // postorder in callgraph

    FunctionElement(FunctionCFAsWithMetadata pCfa, String pName) {
      Preconditions.checkArgument(!Strings.isNullOrEmpty(pName));
      name = pName;
      entry = pCfa.getFunctions().get(name);
      oldNodes = new TreeSet<>(pCfa.getCFANodes().get(name));
    }

    @Override
    public String toString() {
      String result = name;
      if (callHeight >= 0) {
        result += "_" + callHeight;
      }
      return result;
    }

    private void setCallHeight(int p) {
      //      System.out.println(callHeight == -1 ? ""
      //            : name + " already has call height (post oreder) set to " + callHeight);
      Preconditions.checkArgument(p >= 0);
      callHeight = p;
    }

    public int getCallHeight() {
      Preconditions.checkState(callHeight >= 0);
      return callHeight;
    }

    private void insertDummyReturn() {
      FunctionExitNode exit = entry.getExitNode();
      ImmutableList.Builder<CFAEdge> halfConnected = ImmutableList.builder();

      newNodes = new TreeSet<>();
      newNodes.add(entry);
      newNodes.add(exit);

      assert entry.getNumLeavingEdges() == 1;
      halfConnected.add(entry.getLeavingEdge(0));
      halfConnected.addAll(CFAUtils.enteringEdges(exit));
      CFAMutationUtils.removeAllEnteringEdges(exit);

      final CType returnType = (CType) entry.getFunction().getType().getReturnType();
      if (returnType instanceof CVoidType) {
        // no need in return
        CFAMutationUtils.changeSuccessor(entry.getLeavingEdge(0).getSuccessor(), exit);
        halfConnectedEdges = halfConnected.build();
      }

      // add node after entry
      CFANode lastNode = new CFANode(entry.getFunction());
      CFAMutationUtils.changeSuccessor(entry.getLeavingEdge(0).getSuccessor(), lastNode);
      newNodes.add(lastNode);

      Optional<CFANode> node = CFAMutationUtils.insertDefaultReturnStatementEdge(lastNode, exit);
      if (node.isPresent()) {
        newNodes.add(node.orElseThrow());
      }

      halfConnectedEdges = halfConnected.build();
    }
  }

  private ImmutableSet<String> bodiesNeeded;

  private final ThreadCreateTransformer threadCreateTransformer;

  private final LogManager logger;

  private Map<String, FunctionElement> functionElements = new TreeMap<>();

  private MutableValueGraph<FunctionElement, FunctionCall> functionCallGraph = null;
  private ImmutableValueGraph<FunctionElement, FunctionCall> prevGraph = null;
  private ImmutableSet<FunctionElement> currentLevel = null;
  private List<FunctionElement> previousLevels = new ArrayList<>();

  private ImmutableList<FunctionElement> currentMutation;

  public FunctionBodyManipulator(Configuration pConfig, LogManager pLogger)
      throws InvalidConfigurationException {
    logger = Preconditions.checkNotNull(pLogger);
    threadCreateTransformer = new ThreadCreateTransformer(pLogger, pConfig);
  }

  private FunctionElement functionElementByName(FunctionCFAsWithMetadata pCfa, String pName) {
    FunctionElement result = functionElements.get(pName);
    if (result == null) {
      result = new FunctionElement(pCfa, pName);
      functionElements.put(pName, result);
    }
    return result;
  }

  @Override
  public ImmutableValueGraph<FunctionElement, ?> getGraph() {
    return ImmutableValueGraph.copyOf(functionCallGraph);
  }

  @Override
  public void setupFromCfa(FunctionCFAsWithMetadata pCfa) {
    functionCallGraph =
        ValueGraphBuilder.directed().expectedNodeCount(pCfa.getFunctions().size()).build();

    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    builder.add(pCfa.getMainFunction().getFunctionName());

    // thread operations need body of thread-called function to be present
    ThreadFinder threadFinder = threadCreateTransformer.new ThreadFinder();
    for (FunctionEntryNode functionStartNode : pCfa.getFunctions().values()) {
      CFATraversal.dfs().traverseOnce(functionStartNode, threadFinder);
    }

    threadFinder.getThreadCreates().values().stream()
        .map(
            threadCreate ->
                ThreadCreateTransformer.getFunctionName(
                    threadCreate.getParameterExpressions().get(2)))
        .forEach(idExp -> builder.add(idExp.getName()));

    bodiesNeeded = builder.build();

    for (String name : pCfa.getFunctions().keySet()) {
      functionCallGraph.addNode(functionElementByName(pCfa, name));
    }

    addDirectCalls(pCfa, functionCallGraph);
    // TODO other function calls... (via pointers and thread creation too)

    setPostorder();
  }

  private void addDirectCalls(
      FunctionCFAsWithMetadata pCfa, MutableValueGraph<FunctionElement, FunctionCall> pCallGraph) {
    FunctionCallCollector fcc = new FunctionCallCollector();
    for (FunctionEntryNode entry : pCfa.getFunctions().values()) {
      CFATraversal.dfs().ignoreFunctionCalls().traverseOnce(entry, fcc);
    }

    for (AStatementEdge edge : fcc.getFunctionCalls()) {
      String callerName = edge.getPredecessor().getFunctionName();
      AFunctionCall callStmt = (AFunctionCall) edge.getStatement();
      AFunctionCallExpression callExpr = callStmt.getFunctionCallExpression();
      AFunctionDeclaration decl = callExpr.getDeclaration();
      if (decl == null) {
        continue;
      }
      String calledName = callExpr.getDeclaration().getName();
      if (!callerName.equals(calledName) && pCfa.getFunctions().containsKey(calledName)) {
        // do not count self-recursion or call to not present function
        pCallGraph.putEdgeValue(
            functionElementByName(pCfa, callerName),
            functionElementByName(pCfa, calledName),
            FunctionCall.DIRECT_CALL);
      }
    }
  }

  public void remove(FunctionCFAsWithMetadata pCfa, FunctionElement pChosen) {
    if (bodiesNeeded.contains(pChosen.name)) {
      logger.log(Level.FINE, "Replacing function", pChosen, "body with default 'return'");
      if (pCfa.getLanguage() != Language.C) {
        throw new UnsupportedOperationException(
            "Replacing function bodies with dummy returns is not supported for Java");
      }

      pChosen.insertDummyReturn();
      pCfa.getCFANodes().removeAll(pChosen.name);
      pCfa.getCFANodes().putAll(pChosen.name, pChosen.newNodes);

    } else {
      logger.log(Level.FINE, "Removing function", pChosen);
      // remove function entry
      pCfa.getFunctions().remove(pChosen.name);
      // remove nodes
      pCfa.getCFANodes().asMap().remove(pChosen.name);
    }
  }

  public void restore(FunctionCFAsWithMetadata pCfa, FunctionElement pRemoved) {
    logger.log(Level.FINE, "Restoring function", pRemoved.name);
    pCfa.getCFANodes().removeAll(pRemoved.name);
    pCfa.getCFANodes().putAll(pRemoved.name, pRemoved.oldNodes);
    pCfa.getFunctions().put(pRemoved.name, pRemoved.entry);
    // restore edges if needed
    if (pRemoved.halfConnectedEdges.isEmpty()) {
      return;
    }
    pRemoved.entry.removeLeavingEdge(pRemoved.entry.getLeavingEdge(0));
    pRemoved.entry.addLeavingEdge(pRemoved.halfConnectedEdges.get(0));
    FunctionExitNode exit = pRemoved.entry.getExitNode();
    CFAMutationUtils.removeAllEnteringEdges(exit);
    for (int i = 1; i < pRemoved.halfConnectedEdges.size(); i++) {
      exit.addEnteringEdge(pRemoved.halfConnectedEdges.get(i));
    }
  }

  @Override
  public String getElementTitle() {
    return "functions";
  }

  @Override
  public ImmutableSet<FunctionElement> getAllElements() {
    Preconditions.checkState(functionCallGraph != null, "Function call graph was not set up");
    return ImmutableSet.copyOf(functionCallGraph.nodes());
  }

  @Override
  public ImmutableSet<FunctionElement> getNextLevelElements() {
    Preconditions.checkState(functionCallGraph != null, "Function call graph was not set up");
    if (currentLevel == null) {
      // return roots/sources
      // functions that are not called by any other
      currentLevel =
          functionCallGraph.nodes().stream()
              .filter(
                  f ->
                      functionCallGraph.predecessors(f).stream()
                          .filter(g -> g.getCallHeight() > f.getCallHeight())
                          .collect(ImmutableSet.toImmutableSet())
                          .isEmpty())
              .collect(ImmutableSet.toImmutableSet());
    } else {
      currentLevel =
          FluentIterable.from(currentLevel)
              // filter out f removed from graph but remaining in 'currentLevel'
              .filter(f -> functionCallGraph.nodes().contains(f))
              .transformAndConcat(f -> functionCallGraph.successors(f))
              // filter out g that were in current or previous levels
              // filter out g that has caller not from current or previous level
              // (do not count 'callers' from recursive calls, i.e. with smaller postorder)
              .filter(
                  g ->
                      !previousLevels.contains(g)
                          && previousLevels.containsAll(
                              functionCallGraph.predecessors(g).stream()
                                  .filter(f -> f.getCallHeight() > g.getCallHeight())
                                  .collect(ImmutableSet.toImmutableSet())))
              .toSet();
    }
    previousLevels.addAll(currentLevel);
    return currentLevel;
  }

  private void setPostorder() {
    for (FunctionElement source : functionCallGraph.nodes()) {
      if (functionCallGraph.inDegree(source) == 0) {

        int id = 0;
        Iterable<FunctionElement> nodesInPostOrder =
            Traverser.<FunctionElement>forGraph(n -> functionCallGraph.successors(n))
                .depthFirstPostOrder(source);

        for (FunctionElement node : nodesInPostOrder) {
          node.setCallHeight(id++);
        }
      }
    }

    //    System.out.println(functionCallGraph.nodes());
    Optional<FunctionElement> source =
        functionCallGraph.nodes().stream().filter(node -> node.callHeight < 0).findFirst();
    //    System.out.println("found " + source);

    while (source.isPresent()) {
      int id = 0;
      Iterable<FunctionElement> nodesInPostOrder =
          Traverser.<FunctionElement>forGraph(n -> functionCallGraph.successors(n))
              .depthFirstPostOrder(source.orElseThrow());

      for (FunctionElement node : nodesInPostOrder) {
        node.setCallHeight(id++);
      }

      //      System.out.println(functionCallGraph.nodes());
      source = functionCallGraph.nodes().stream().filter(node -> node.callHeight < 0).findFirst();
      //      System.out.println("found " + source);
    }
  }

  @Override
  public void remove(FunctionCFAsWithMetadata pCfa, Collection<FunctionElement> pChosen) {
    pChosen.forEach(f -> remove(pCfa, f));
    currentMutation = ImmutableList.copyOf(pChosen);
  }

  @Override
  public void restore(FunctionCFAsWithMetadata pCfa, Collection<FunctionElement> pChosen) {
    pChosen.forEach(f -> restore(pCfa, f));
  }

  @Override
  public ImmutableSet<FunctionElement> whatRemainsIfRemove(Collection<FunctionElement> pChosen) {
    return FluentIterable.from(functionCallGraph.nodes()).filter(f -> !pChosen.contains(f)).toSet();
  }

  @Override
  public ImmutableSet<FunctionElement> whatRemainsIfPrune(Collection<FunctionElement> pChosen) {
    List<FunctionElement> toRemove = whatToPruneIfChoose(pChosen);
    return FluentIterable.from(functionCallGraph.nodes())
        .filter(f -> !toRemove.contains(f))
        .toSet();
  }

  private ImmutableList<FunctionElement> whatToPruneIfChoose(Collection<FunctionElement> pChosen) {
    List<FunctionElement> removed = new ArrayList<>(pChosen);

    for (int i = 0; i < removed.size(); i++) {
      FunctionElement f = removed.get(i);
      functionCallGraph.successors(f).stream()
          .filter(g -> !removed.contains(g))
          .filter(
              g ->
                  removed.containsAll(
                      functionCallGraph.predecessors(g).stream()
                          .filter(h -> h.getCallHeight() > g.getCallHeight())
                          .collect(ImmutableSet.toImmutableSet())))
          .forEach(g -> removed.add(g));
    }

    return ImmutableList.copyOf(removed);
  }

  @Override
  public void prune(FunctionCFAsWithMetadata pCfa, Collection<FunctionElement> pChosen) {
    Preconditions.checkState(functionCallGraph != null, "Function call graph was not set up");

    prevGraph = ImmutableValueGraph.copyOf(functionCallGraph);
    currentMutation = whatToPruneIfChoose(pChosen);
    assert prevGraph.nodes().containsAll(currentMutation);

    logger.log(
        Level.INFO,
        "Removing",
        currentMutation.size(),
        getElementTitle(),
        "including callees:",
        currentMutation);

    currentMutation.forEach(
        f -> {
          remove(pCfa, f);
          functionCallGraph.removeNode(f);
        });
  }

  @Override
  public void rollback(FunctionCFAsWithMetadata pCfa) {
    logger.log(
        Level.INFO,
        "Restoring",
        currentMutation.size(),
        getElementTitle(),
        currentMutation);
    if (prevGraph != null) {
      for (FunctionElement node : prevGraph.nodes()) {
        functionCallGraph.addNode(node);
      }
      for (EndpointPair<FunctionElement> edge : prevGraph.edges()) {
        functionCallGraph.putEdgeValue(edge, prevGraph.edgeValue(edge).orElseThrow());
      }
    }
    currentMutation.reverse().forEach(f -> restore(pCfa, f));
  }
}
