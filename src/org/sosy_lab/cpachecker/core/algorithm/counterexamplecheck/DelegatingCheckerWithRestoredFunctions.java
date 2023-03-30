// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.io.PathTemplate;
import org.sosy_lab.common.io.TempFile.TempFileBuilder;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.Language;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.mutation.CFAMutator;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleCheckAlgorithm.CounterexampleCheckerType;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CounterexampleAnalysisFailed;
import org.sosy_lab.cpachecker.exceptions.ParserException;
import org.sosy_lab.cpachecker.util.LiveVariables;
import org.sosy_lab.cpachecker.util.LoopStructure;
import org.sosy_lab.cpachecker.util.cwriter.CFAToCTranslator;
import org.sosy_lab.cpachecker.util.cwriter.TranslatorConfig;
import org.sosy_lab.cpachecker.util.variableclassification.VariableClassification;

/**
 * Exports as delegate, appends functions that were absent in the CEX, checks the file as delegate.
 * Cannot work with CPAchecker witness format, as restored functions are appended as C definitions.
 */
public class DelegatingCheckerWithRestoredFunctions extends CounterexampleChecker {
  private final CounterexampleChecker delegate;
  private final NavigableSet<String> alreadyPresentFunctions;
  private final TranslatorConfig transConfig;
  private final CFAMutator cfaMutator;

  public DelegatingCheckerWithRestoredFunctions(
      Configuration pConfig,
      LogManager pLogger,
      CFA pMutatedCfa,
      ShutdownManager pShutdownManager,
      CounterexampleCheckerType pCheckerType,
      CFAMutator pCfaMutator)
      throws InvalidConfigurationException {

    cfaMutator = pCfaMutator;
    alreadyPresentFunctions = pMutatedCfa.getAllFunctionNames();

    transConfig = new TranslatorConfig(pConfig);
    transConfig.setIncludeHeader(false);

    switch (pCheckerType) {
      case CBMC:
        delegate = new CBMCChecker(pConfig, pLogger, pMutatedCfa);
        break;

      case CPACHECKER:
        delegate = new CexAsProgramCPAchecker(pConfig, pLogger, pShutdownManager);
        break;

      case CONCRETE_EXECUTION:
        delegate = new ConcretePathExecutionChecker(pConfig, pLogger, pMutatedCfa);
        break;

      default:
        throw new AssertionError("Unhandled case statement: " + pCheckerType);
    }
  }

  @Override
  protected TempFileBuilder getTempFileBuilder() {
    return delegate.getTempFileBuilder();
  }

  @Override
  protected @Nullable PathTemplate getCexFileTemplate() {
    return delegate.getCexFileTemplate();
  }

  @Override
  protected void writeCexFile(
      ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path pFile)
      throws CounterexampleAnalysisFailed, InterruptedException {
    delegate.writeCexFile(pRootState, pErrorState, pErrorPathStates, pFile);
    writeRestoredFunctions(pFile, alreadyPresentFunctions);
  }

  @Override
  protected boolean checkCounterexample0(
      ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path pFile)
      throws CounterexampleAnalysisFailed, InterruptedException {
    return delegate.checkCounterexample0(pRootState, pErrorState, pErrorPathStates, pFile);
  }

  /**
   * @param pCFile File that contains counterexample to check. Definitions of functions that were
   *     removed when the counterexample was found will be added to this file.
   * @param pAlreadyPresentFunctions Names of functions that were present during CEX export.
   */
  private void writeRestoredFunctions(Path pCFile, NavigableSet<String> pAlreadyPresentFunctions)
      throws CounterexampleAnalysisFailed {
    CFA restoredPart;
    try {
      CFA restoredCfa = cfaMutator.restoreCfa();
      restoredPart = new AdditionalFunctionsCFA(restoredCfa, pAlreadyPresentFunctions);
    } catch (InvalidConfigurationException | InterruptedException | ParserException e) {
      throw new CounterexampleAnalysisFailed(
          "Cannot restore CFA for proper counterexample check: " + e.getMessage(), e);
    }

    try {
      String code = new CFAToCTranslator(transConfig).translateCfa(restoredPart);
      try (Writer writer =
          IO.openOutputFile(pCFile, Charset.defaultCharset(), StandardOpenOption.APPEND)) {
        writer.write(
            "\n// Above is counterexample to check.\n// Below are restored functions.\n\n");
        writer.write(code);
      }

    } catch (CPAException | InvalidConfigurationException | IOException e) {
      throw new CounterexampleAnalysisFailed(
          "Cannot add definitions of absent functons to the counterexample file "
              + "produced with mutated CFA: "
              + e.getMessage(),
          e);
    }
  }

  private static final class AdditionalFunctionsCFA implements CFA {
    private final CFA delegate;
    private final NavigableMap<String, FunctionEntryNode> functions = new TreeMap<>();

    AdditionalFunctionsCFA(CFA pRestoredCfa, NavigableSet<String> pAlreadyPresentFunctions) {
      assert pRestoredCfa.getAllFunctionNames().containsAll(pAlreadyPresentFunctions);
      delegate = pRestoredCfa;
      functions.putAll(pRestoredCfa.getAllFunctions());
      functions.keySet().removeAll(pAlreadyPresentFunctions);
    }

    @Override
    public MachineModel getMachineModel() {
      return delegate.getMachineModel();
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @Override
    public int getNumberOfFunctions() {
      return functions.size();
    }

    @Override
    public NavigableSet<String> getAllFunctionNames() {
      return (NavigableSet<String>) functions.keySet();
    }

    @Override
    public Collection<FunctionEntryNode> getAllFunctionHeads() {
      return functions.values();
    }

    @Override
    public FunctionEntryNode getFunctionHead(String pName) {
      FunctionEntryNode result = functions.get(pName);
      assert result != null
          : "Function "
              + pName
              + " not found, and original CFA "
              + (delegate.getAllFunctionNames().contains(pName) ? "does " : "does not ")
              + "contain this function";
      return result;
    }

    @Override
    public NavigableMap<String, FunctionEntryNode> getAllFunctions() {
      return functions;
    }

    @Override
    public Collection<CFANode> getAllNodes() {
      return delegate.getAllNodes();
    }

    @Override
    public FunctionEntryNode getMainFunction() {
      return delegate.getMainFunction();
    }

    @Override
    public Optional<LoopStructure> getLoopStructure() {
      return delegate.getLoopStructure();
    }

    @Override
    public Optional<ImmutableSet<CFANode>> getAllLoopHeads() {
      return delegate.getAllLoopHeads();
    }

    @Override
    public Optional<VariableClassification> getVarClassification() {
      return delegate.getVarClassification();
    }

    @Override
    public Optional<LiveVariables> getLiveVariables() {
      return delegate.getLiveVariables();
    }

    @Override
    public Language getLanguage() {
      return delegate.getLanguage();
    }

    @Override
    public List<Path> getFileNames() {
      return delegate.getFileNames();
    }
  }
}
