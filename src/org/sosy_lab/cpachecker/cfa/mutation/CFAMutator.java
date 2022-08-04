// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Collection;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.CFACreator;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.defaults.MultiStatistics;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.exceptions.ParserException;
import org.sosy_lab.cpachecker.util.statistics.StatCounter;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;

/**
 * Mutates the CFA before next analysis run, mainly to minimize and simplify CFA. Operates on {@link
 * FunctionCFAsWithMetadata}. All processings in {@link CFACreator#createCFA} are applied after this
 * to get proper CFA for analysis run.
 */
public class CFAMutator extends CFACreator implements StatisticsProvider {
  /** local CFA of functions before processing */
  private FunctionCFAsWithMetadata localCfa = null;
  /** Strategy that decides how to change the CFA and implements this change */
  private final CFAMutationStrategy strategy;

  private final Path cfaExportDirectory;

  private final CFAMutatorStatistics mutatorStats;

  private static class CFAMutatorStatistics extends MultiStatistics {
    private StatCounter round = new StatCounter("mutation rounds");
    private StatCounter rollbacks = new StatCounter("unsuccessful mutation rounds (rollbacks)");
    private StatTimer mutationTimer = new StatTimer("time for mutations");
    private StatTimer rollbackTimer = new StatTimer("time for rollbacks");
    private StatTimer totalMutatorTimer = new StatTimer("total time for CFA mutator");
    private StatTimer processCfaTimer = new StatTimer("time for CFA creation from function CFAs");
    private StatTimer resetCfaTimer = new StatTimer("time for reverting CFA to function CFAs");
    private StatTimer exportTimer = new StatTimer("time for CFA export");

    private CFAMutatorStatistics(LogManager pLogger) {
      super(pLogger);
    }

    @Override
    public @Nullable String getName() {
      return "CFA mutator";
    }

    @Override
    public void printStatistics(PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {
      put(pOut, 1, round);
      put(pOut, 2, rollbacks);
      put(pOut, 1, totalMutatorTimer);
      put(pOut, 2, mutationTimer);
      put(pOut, 2, rollbackTimer);
      put(pOut, 2, processCfaTimer);
      put(pOut, 2, resetCfaTimer);
      put(pOut, 2, exportTimer);
      super.printStatistics(pOut, pResult, pReached);
    }
  }

  public CFAMutator(Configuration pConfig, LogManager pLogger, ShutdownNotifier pShutdownNotifier)
      throws InvalidConfigurationException {
    super(pConfig, pLogger, pShutdownNotifier);
    cfaExportDirectory =
        exportDirectory == null ? Path.of("output/contol-flow-automaton") : exportDirectory;
    strategy =
        new CompositeCFAMutationStrategy(
            pLogger,
            ImmutableList.of(
                new FunctionBodyRemover(pLogger),
                new SimpleBranchingRemover(pLogger, 1),
                new SimpleBranchingRemover(pLogger, 0),
                new ChainRemover(pLogger),
                new EmptyBranchPruner(pLogger),
                new StatementRemover(pLogger)));
    mutatorStats = new CFAMutatorStatistics(pLogger);
  }

  /**
   * Use once while parsing the source, initialize CFA that will be mutated. Use {@link
   * CFACreator#createCFA} to fully create CFA from function CFA.
   */
  @Override
  protected CFA createCFA(ParseResult pParseResult, FunctionEntryNode pMainFunction)
      throws InvalidConfigurationException, InterruptedException, ParserException {
    localCfa =
        FunctionCFAsWithMetadata.fromParseResult(
            pParseResult, machineModel, pMainFunction, language);
    return super.createCFA(pParseResult, pMainFunction);
  }

  @Override
  protected void exportCFAAsync(CFA pCfa) {
    // do not export asynchronously as CFA will be mutated
    if (mutatorStats.round.getValue() == 0) {
      mutatorStats.exportTimer.start();
      exportDirectory = cfaExportDirectory.resolve("original-cfa");
      super.exportCFA(pCfa);
      mutatorStats.exportTimer.stop();
    }
  }

  public boolean canMutate() {
    mutatorStats.totalMutatorTimer.start();
    mutatorStats.resetCfaTimer.start();
    // save previous CFACreator stats and reset it
    stats = new CFACreatorStatistics(logger);

    // start next round of work with CFA -- clear processings
    localCfa.resetEdgesInNodes();

    mutatorStats.resetCfaTimer.stop();

    boolean result = strategy.canMutate(localCfa);

    mutatorStats.totalMutatorTimer.stop();
    return result;
  }

  /** Apply some mutation to the CFA */
  public CFA mutate() throws InterruptedException, InvalidConfigurationException, ParserException {
    mutatorStats.totalMutatorTimer.start();
    mutatorStats.mutationTimer.start();
    mutatorStats.round.inc();

    strategy.mutate(localCfa);

    mutatorStats.processCfaTimer.start();
    CFA result = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
    mutatorStats.processCfaTimer.stop();

    mutatorStats.exportTimer.start();
    exportDirectory = cfaExportDirectory.resolve(mutatorStats.round.getValue() + "-mutation-round");
    super.exportCFA(result);
    mutatorStats.exportTimer.stop();

    mutatorStats.mutationTimer.stop();
    mutatorStats.totalMutatorTimer.stop();
    return result;
  }

  /** Undo last mutation if needed */
  public CFA setResult(DDResultOfARun pResult)
      throws InvalidConfigurationException, InterruptedException, ParserException {
    mutatorStats.totalMutatorTimer.start();
    mutatorStats.rollbackTimer.start();

    // XXX write result?
    // undo createCFA before possible mutation rollback
    mutatorStats.resetCfaTimer.start();
    localCfa.resetEdgesInNodes();
    mutatorStats.resetCfaTimer.stop();

    strategy.setResult(localCfa, pResult);

    CFA rollbackedCfa = null;
    if (pResult != DDResultOfARun.FAIL) {
      mutatorStats.rollbacks.inc();

      mutatorStats.processCfaTimer.start();
      // export after rollback
      rollbackedCfa = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
      mutatorStats.processCfaTimer.stop();

      mutatorStats.exportTimer.start();
      exportDirectory =
          cfaExportDirectory.resolve(mutatorStats.round.getValue() + "-mutation-round-rollbacked");
      super.exportCFA(rollbackedCfa);
      mutatorStats.exportTimer.stop();
    }

    mutatorStats.rollbackTimer.stop();
    mutatorStats.totalMutatorTimer.stop();
    return rollbackedCfa;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(mutatorStats);
  }
}
