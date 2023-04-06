// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.FluentIterable.from;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.MoreStrings;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.converters.FileTypeConverter;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.mutation.AnalysisOutcome;
import org.sosy_lab.cpachecker.cfa.mutation.CFAMutator;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleCheckAlgorithm;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleChecker;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.core.specification.Specification;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CounterexampleAnalysisFailed;
import org.sosy_lab.cpachecker.exceptions.ParserException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;
import org.sosy_lab.cpachecker.util.statistics.StatTimerWithMoreOutput;
import org.sosy_lab.cpachecker.util.statistics.StatisticsUtils;

public final class CPAcheckerMutator extends CPAchecker {

  static enum AnalysisRun {
    ORIGINAL,
    AFTER_MUTATION,
    FEASIBILITY_CHECK,
    AFTER_ROLLBACK,
  }

  private CFAMutationManager cfaMutationManager;

  private final CFAMutator cfaMutator;

  private final String defaultOutputDirectory;

  private int round = 0;

  public CPAcheckerMutator(
      Configuration pConfiguration,
      LogManager pLogManager,
      ShutdownManager pShutdownManager,
      ResourceLimitChecker pLimits)
      throws InvalidConfigurationException {

    super(pConfiguration, pLogManager, pShutdownManager);
    cfaMutator = new CFAMutator(config, logger, shutdownNotifier);
    cfaMutationManager =
        new CFAMutationManager(pConfiguration, pLogManager, pShutdownManager, pLimits, cfaMutator);
    defaultOutputDirectory =
        ((FileTypeConverter) Configuration.getDefaultConverters().get(FileOption.class))
            .getOutputDirectory();

    if (getSerializedCfaFile() != null) {
      throw new InvalidConfigurationException(
          "CFA mutation needs source files to be parsed into CFA. Do not specify 'cfaMutation=true' "
              + "and loading CFA with 'analysis.serializedCfaFile' simultaneously.");
    }
  }

  @Override
  public CPAcheckerResult run(List<String> programDenotation) {
    checkArgument(!programDenotation.isEmpty());
    logger.logf(
        Level.INFO,
        "%s / CFA %s (%s) started",
        getVersion(config),
        cfaMutator.getApproachName(),
        getJavaInformation());
    MainCFAMutationStatistics totalStats = new MainCFAMutationStatistics();
    AnalysisResult lastResult = null;

    try {
      resetExportDirectory(AnalysisRun.ORIGINAL);
      parse(cfaMutator, programDenotation);
      totalStats.setCFACreatorStatistics(cfaMutator.getStatistics());
      cfaMutator.clearCreatorStats();

      CFA originalCfa = getCfa();
      if (originalCfa == null) {
        // invalid input files
        return produceResult();
      }

      Specification originalSpec = getSpecification(originalCfa);

      final AnalysisResult originalResult =
          analysisRound(originalCfa, totalStats.originalTime, AnalysisRun.ORIGINAL);

      AnalysisOutcome originalOutcome = originalResult.toAnalysisOutcome(originalResult);
      if (cfaMutator.shouldReturnWithoutMutation(originalOutcome)) {
        return originalResult.result;
      }

      cfaMutationManager.setSpecification(originalSpec);
      cfaMutationManager.setOriginalTime(totalStats.originalTime.getConsumedTime());

      if (cfaMutationManager.exceedsLimitsForAnalysis(
          "CFA mutation interrupted before it started to mutate the CFA:")) {
        return originalResult.asMutatorResult(Result.NOT_YET_STARTED, cfaMutator, totalStats);
      }

      // CFAMutator stores needed info from #parse,
      // so no need to pass CFA as argument in next calls

      int rollbacksInRow = 0;
      for (round = 1; cfaMutator.canMutate(); round++) {
        logger.log(Level.INFO, "Mutation round", round);

        resetExportDirectory(AnalysisRun.AFTER_MUTATION);
        CFA mutated = cfaMutator.mutate();
        lastResult =
            analysisRound(mutated, totalStats.afterMutations, AnalysisRun.AFTER_MUTATION);
        // TODO export intermediate results
        // XXX it is incorrect to save intermediate stats, as reached is updated?

        AnalysisOutcome lastOutcome = lastResult.toAnalysisOutcome(originalResult);

        if (cfaMutator.shouldCheckFeasibiblity(lastOutcome)) {
          if (cfaMutationManager.exceedsLimitsForFeasibility()) {
            return lastResult.asMutatorResult(Result.FALSE, cfaMutator, totalStats);
          }

          logger.log(Level.INFO, "Found a counterexample. Checking it on original program.");

          resetExportDirectory(AnalysisRun.FEASIBILITY_CHECK);
          boolean errorIsFeasible = false;
          try {
            totalStats.feasibilityCheck.start();
            errorIsFeasible = isFeasible(lastResult);
          } finally {
            totalStats.feasibilityCheck.stop();
          }

          if (errorIsFeasible) {
            logger.log(
                Level.INFO, "Found feasible error:", lastResult.result.getTargetDescription());
            return lastResult.asMutatorResult(Result.FALSE, cfaMutator, totalStats);
          }

        } else {
          logger.log(Level.INFO, lastOutcome);
        }

        resetExportDirectory(AnalysisRun.AFTER_ROLLBACK);
        CFA rollbacked = cfaMutator.setResult(lastOutcome);

        if (cfaMutationManager.exceedsLimitsForAnalysis(
            String.format("CFA mutation interrupted after %s. analysis round", round))) {
          return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);
        }

        // Check that property is still preserved after rollback
        if (rollbacked == null || cfaMutationManager.getCheckAfterRollbacks() == 0) {
          // options say pass the check this time
          rollbacksInRow = 0;

        } else if (++rollbacksInRow % cfaMutationManager.getCheckAfterRollbacks() == 0) {
          logger.log(
              Level.INFO, "Running analysis after", rollbacksInRow, "mutation rollback in row");

          lastResult =
              analysisRound(rollbacked, totalStats.afterRollbacks, AnalysisRun.AFTER_ROLLBACK);
          AnalysisOutcome analysisOutcome = lastResult.toAnalysisOutcome(originalResult);
          // If analysis ended because of a global shutdown, the result may be TIMEOUT
          // otherwise, check it is expected one
          if (!shutdownNotifier.shouldShutdown()
              || analysisOutcome != AnalysisOutcome.VERDICT_UNKNOWN_BECAUSE_OF_TIMEOUT) {
            cfaMutator.verifyOutcome(analysisOutcome);
          }

          if (cfaMutationManager.exceedsLimitsForAnalysis(
              String.format(
                  "CFA mutation interrupted after rollback after %s. analysis round", round))) {
            return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);
          }
        }
      }

      if (lastResult == null) {
        logger.log(Level.INFO, "There are no possible mutations for the CFA of the given program.");
        return originalResult.asMutatorResult(Result.NOT_YET_STARTED, cfaMutator, totalStats);
      }

      logger.log(Level.INFO, "CFA mutation ended, as no more minimizatins can be found");
      return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);

    } catch (InvalidConfigurationException e) {
      logger.logUserException(Level.SEVERE, e, "Invalid configuration");
      // XXX move to #analysisRound?
      // but for first round should catch here?

    } catch (InterruptedException e) {
      // CPAchecker must exit because it was asked to
      // we return normally instead of propagating the exception
      // so we can return the partial result we have so far
      logger.logUserException(Level.WARNING, e, "CFA mutation interrupted");

    } catch (ParserException e) {
      logger.logUserException(Level.SEVERE, e, "Parser error");
      // XXX is it possible?
      // TODO export previous CFA then?

    }

    if (lastResult == null) {
      return new CPAcheckerResult(Result.NOT_YET_STARTED, "", null, null, null);
    }
    return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);
  }

  public void resetExportDirectory(AnalysisRun pRun) throws InvalidConfigurationException {
    String exportPath = defaultOutputDirectory + File.separatorChar;

    switch (pRun) {
      case ORIGINAL:
        assert round == 0;
        exportPath += "0-original-round";
        break;

      case AFTER_MUTATION:
        exportPath += String.valueOf(round) + "-mutation-round";
        break;

      case FEASIBILITY_CHECK:
        exportPath += String.valueOf(round) + "-mutation-round-feasibility";
        break;

      case AFTER_ROLLBACK:
        exportPath += String.valueOf(round) + "-mutation-round-rollback";
        break;

      default:
        throw new AssertionError();
    }

    FileTypeConverter fileTypeConverter = FileTypeConverter.create(Configuration.builder().setOption("output.path", exportPath).build());
    Configuration.getDefaultConverters().put(FileOption.class, fileTypeConverter);
  }

  @SuppressFBWarnings("DCN_NULLPOINTER_EXCEPTION")
  // run analysis, but for already stored CFA, and catch its errors
  private AnalysisResult analysisRound(CFA pCfa, StatTimer pTimer, AnalysisRun pRun)
      throws InvalidConfigurationException {

    pTimer.start();
    Throwable t = null;
    CPAchecker cpachecker = cfaMutationManager.createCpacheckerAndStartLimits(pRun);
    LogManager curLogger = cfaMutationManager.getCurrentLogger();

    try {
      cpachecker.setupMainStats();
      cpachecker.setCfa(pCfa);
      try {
        cpachecker.setupAnalysis();
      } finally {
        cpachecker.stopMainStatsCreationTimer();
      }
      cpachecker.runAnalysis();

    } catch (InterruptedException e) {
      // this round was too long
      t = e;
      logger.logUserException(Level.WARNING, e, "Analysis round interrupted");
      curLogger.logException(Level.WARNING, e, "Analysis round interrupted");

    } catch (AssertionError
        | IllegalArgumentException
        | IllegalStateException
        | IndexOutOfBoundsException
        | ClassCastException
        | NullPointerException
        | NoSuchElementException
        | VerifyException
        | CPAException e) {
      // Expect exceptions as bugs of CPAchecker, and remember them to reproduce on a smaller CFA.
      // TODO which types exactly
      t = e;
      if (e.getStackTrace().length == 0) {
        // too many same exceptions were thrown, it's JVM optimization
        logger.logf(Level.WARNING, "Recurring %s (no stack trace)", e.getClass());
        curLogger.logf(Level.WARNING, "Recurring %s (no stack trace)", e.getClass());
      } else {
        logger.logUserException(Level.WARNING, e, null);
        curLogger.logException(Level.WARNING, e, null);
      }

    } finally {
      cfaMutationManager.cancelAnalysisLimits();
      cpachecker.closeCPAsIfPossible();
      pTimer.stop();
    }

    CPAcheckerResult cur = cpachecker.produceResult();
    if (t == null) {
      curLogger.log(Level.INFO, cur.getResultString());
    } else {
      // exception was already logged
    }

    try (PrintStream w = new PrintStream(cfaMutationManager.getIntermediateStatisticsFile())) {
      MainCPAStatistics curStats = cpachecker.getStats();
      StatisticsUtils.printStatistics(curStats, w, curLogger, cur.getResult(), cur.getReached());
      StatisticsUtils.writeOutputFiles(curStats, curLogger, cur.getResult(), cur.getReached());
      cfaMutationManager.printCfaNodeRank(w, cur.getReached());

    } catch (IOException e) {
      logger.logUserException(Level.WARNING, e, "Cannot write intermediate statistics");
    }

    return new AnalysisResult(cur, t);
  }

  /** Mostly copied from {@link CounterexampleCheckAlgorithm} */
  private boolean isFeasible(AnalysisResult pResult) throws InvalidConfigurationException {

    CounterexampleChecker cexChecker = cfaMutationManager.createCexCheckerAndStartLimits(pResult);
    LogManager curLogger = cfaMutationManager.getCurrentLogger();
    ReachedSet reached = pResult.result.getReached();
    assert ARGUtils.checkARG(reached);
    ARGState rootState = (ARGState) reached.getFirstState();

    final List<ARGState> errorStates =
        from(reached)
            .transform(AbstractStates.toState(ARGState.class))
            .filter(AbstractStates::isTargetState)
            .toList();

    assert !errorStates.isEmpty() : "no error states while CEX check";

    // check counterexample
    for (ARGState errorState : errorStates) {
      curLogger.log(
          Level.FINE,
          "Path to error state",
          errorState.getClass().getSimpleName(),
          errorState.getStateId(),
          errorState
              .getCounterexampleInformation()
              .map(cex -> String.format("(CEX #%d)", cex.getUniqueId()))
              .orElse("(without CEX info)"),
          "found, starting counterexample check");

      // if (ambigiousARG) {
      // statesOnErrorPath = SlicingAbstractionsUtils.getStatesOnErrorPath(errorState);
      ImmutableSet<ARGState> statesOnErrorPath = ARGUtils.getAllStatesOnPathsTo(errorState);
      curLogger.log(
          Level.FINE,
          "Paths include states ",
          MoreStrings.lazyString(
              () -> from(statesOnErrorPath).transform(ARGState::getStateId).join(Joiner.on(", "))));

      try {
        if (cexChecker.checkCounterexample(rootState, errorState, statesOnErrorPath)) {
          logger.log(Level.INFO, "Error path confirmed by counterexample check");
          return true;
        }

        curLogger.log(Level.FINE, "This error path identified as infeasible.");

      } catch (CounterexampleAnalysisFailed e) {
        logger.logUserException(
            Level.WARNING, e, "Counterexample found, but feasibility could not be verified");

      } catch (InterruptedException e) {
        // this feasibility check was too long
        logger.logf(Level.INFO, "Feasibility check interrupted (%s)", e.getMessage());
      }
    }

    cfaMutationManager.cancelCheckLimits();
    logger.log(Level.INFO, "No feasible error path found");
    return false;
  }

  // keep last result and thrown error
  static class AnalysisResult {
    private final CPAcheckerResult result;
    private final @Nullable Throwable thrown;

    public AnalysisResult(CPAcheckerResult pResult, @Nullable Throwable pThrown) {
      result = pResult;
      thrown = pThrown;
    }

    public AnalysisOutcome toAnalysisOutcome(AnalysisResult pOther) {
      switch (getVerdict()) {
        case TRUE:
          return AnalysisOutcome.VERDICT_TRUE;

        case FALSE:
          return pOther.getVerdict() == Result.FALSE && sameTargetDescription(pOther)
              ? AnalysisOutcome.SAME_VERDICT_FALSE
              : AnalysisOutcome.VERDICT_FALSE;

        case NOT_YET_STARTED:
        case UNKNOWN:
          if (thrown instanceof InterruptedException) {
            return AnalysisOutcome.VERDICT_UNKNOWN_BECAUSE_OF_TIMEOUT;
          }
          if (sameException(pOther)) {
            return AnalysisOutcome.FAILURE_BECAUSE_OF_SAME_EXCEPTION;
          }
          return thrown == null
              ? AnalysisOutcome.ANOTHER_VERDICT_UNKNOWN
              : AnalysisOutcome.FAILURE_BECAUSE_OF_EXCEPTION;

        default:
          throw new AssertionError();
      }
    }

    private boolean sameTargetDescription(AnalysisResult pOther) {
      return pOther.result.getTargetDescription().equals(result.getTargetDescription());
    }

    private boolean sameException(AnalysisResult pOther) {
      return thrown != null
          && pOther.thrown != null
          && pOther.thrown.getClass().equals(thrown.getClass())
          && (thrown.getStackTrace().length == 0 // optimized by JVM if many exceptions
              || pOther.thrown.getStackTrace()[0].equals(thrown.getStackTrace()[0]));
    }

    public CPAcheckerResult asMutatorResult(
        Result pResult, CFAMutator pCfaMutator, MainCFAMutationStatistics pTotalStats) {
      Preconditions.checkNotNull(pCfaMutator);
      Preconditions.checkNotNull(pTotalStats);
      pCfaMutator.collectStatistics(pTotalStats.subStats);
      String desc = getVerdict() == Result.FALSE ? result.getTargetDescription() : "";
      return new CPAcheckerResult(pResult, desc, result.getReached(), result.getCfa(), pTotalStats);
    }

    public Result getVerdict() {
      return result.getResult();
    }

    public MainCPAStatistics getStats() {
      return (MainCPAStatistics) result.getStatistics();
    }

    public CFA getCfa() {
      return result.getCfa();
    }

    public ConfigurableProgramAnalysis getCpa() {
      return result.getReached().getCPA();
    }
  }

  class MainCFAMutationStatistics implements Statistics {
    private Statistics cfaCreatorStats = null;
    private List<Statistics> subStats = new ArrayList<>();

    final StatTimer originalTime = new StatTimer("time for original analysis run");
    final StatTimerWithMoreOutput afterMutations =
        new StatTimerWithMoreOutput("total time for analysis after mutations");
    final StatTimerWithMoreOutput afterRollbacks =
        new StatTimerWithMoreOutput("total time for analysis after rollbacks");
    final StatTimerWithMoreOutput feasibilityCheck =
        new StatTimerWithMoreOutput("total time for feasibility check after verdict FALSE");

    @Override
    public void printStatistics(PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {

      if (cfaCreatorStats != null) {
        pOut.println("CFA creation statistics");
        pOut.println("=======================");
        StatisticsUtils.printStatistics(cfaCreatorStats, pOut, logger, pResult, pReached);
        StatisticsUtils.writeOutputFiles(cfaCreatorStats, logger, pResult, pReached);
        pOut.println();
      }

      if (originalTime.getUpdateCount() > 0) {
        pOut.println("CFA mutation statistics");
        pOut.println("=======================");
        pOut.println("Time for analysis");
        pOut.println("-----------------");
        put(pOut, 1, originalTime);
        put(pOut, 1, afterMutations);
        if (afterRollbacks.getUpdateCount() > 0) {
          put(pOut, 1, afterRollbacks);
        }
        if (feasibilityCheck.getUpdateCount() > 0) {
          put(pOut, 1, feasibilityCheck);
        }
      }

      subStats.forEach(
          s -> {
            StatisticsUtils.printStatistics(s, pOut, logger, pResult, pReached);
            StatisticsUtils.writeOutputFiles(s, logger, pResult, pReached);
          });
    }

    public void setCFACreatorStatistics(Statistics pStats) {
      cfaCreatorStats = pStats;
    }

    @Override
    public @Nullable String getName() {
      return "CFA mutation";
    }

    @Override
    public void writeOutputFiles(Result pResult, UnmodifiableReachedSet pReached) {
      subStats.forEach(s -> StatisticsUtils.writeOutputFiles(s, logger, pResult, pReached));
    }
  }
}
