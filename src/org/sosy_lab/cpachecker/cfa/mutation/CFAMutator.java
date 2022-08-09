// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Collection;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.CFACreator;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.exceptions.ParserException;

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
    mutatorStats.setCfaStats(localCfa);
    CFA result = super.createCFA(pParseResult, pMainFunction);
    mutatorStats.setCfaStats(result);
    return result;
  }

  @Override
  protected void exportCFAAsync(CFA pCfa) {
    // do not export asynchronously as CFA will be mutated
    if (mutatorStats.getRound() == 0) {
      mutatorStats.startExport();
      exportDirectory = cfaExportDirectory.resolve("original-cfa");
      super.exportCFA(pCfa);
      mutatorStats.stopExport();
    }
  }

  public void setup() {
    // reset CFACreator stats so following variable classification
    // and pointer resolving wont add a lot of similar stats to print
    stats = new CFACreatorStatistics(logger);
  }

  public boolean canMutate() {
    // clear processings before first #canMutate and after rollbacks
    mutatorStats.startCfaReset();
    localCfa.resetEdgesInNodes();
    mutatorStats.stopCfaReset();

    mutatorStats.startPreparations();
    boolean result = strategy.canMutate(localCfa);
    mutatorStats.stopPreparations();
    return result;
  }

  /** Apply some mutation to the CFA */
  public CFA mutate() throws InterruptedException, InvalidConfigurationException, ParserException {
    mutatorStats.startMutation();
    strategy.mutate(localCfa);
    mutatorStats.stopMutation();

    mutatorStats.setCfaStats(localCfa);
    CFA result = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
    mutatorStats.setCfaStats(result);

    mutatorStats.startExport();
    exportDirectory = cfaExportDirectory.resolve(mutatorStats.getRound() + "-mutation-round");
    super.exportCFA(result);
    mutatorStats.stopExport();

    return result;
  }

  /** Undo last mutation if needed */
  public CFA setResult(DDResultOfARun pResult)
      throws InvalidConfigurationException, InterruptedException, ParserException {

    // XXX write result?
    // undo createCFA before possible mutation rollback
    mutatorStats.startCfaReset();
    localCfa.resetEdgesInNodes();
    mutatorStats.stopCfaReset();

    mutatorStats.startAftermath(pResult);
    strategy.setResult(localCfa, pResult);
    mutatorStats.stopAftermath();

    CFA rollbackedCfa = null;
    if (pResult != DDResultOfARun.FAIL) {
      // export after rollback as there may be no more mutations
      mutatorStats.setCfaStats(localCfa);
      rollbackedCfa = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
      mutatorStats.setCfaStats(rollbackedCfa);

      mutatorStats.startExport();
      exportDirectory =
          cfaExportDirectory.resolve(mutatorStats.getRound() + "-mutation-round-rollbacked");
      super.exportCFA(rollbackedCfa);
      mutatorStats.stopExport();
    }

    return rollbackedCfa;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(mutatorStats);
    strategy.collectStatistics(pStatsCollection);
  }
}
