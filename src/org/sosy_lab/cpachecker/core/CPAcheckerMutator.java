// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Joiner;
import com.google.common.base.VerifyException;
import com.google.common.collect.Iterables;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.LoggingOptions;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.mutation.AnalysisOutcome;
import org.sosy_lab.cpachecker.cfa.mutation.CFAMutator;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm.AlgorithmStatus;
import org.sosy_lab.cpachecker.core.algorithm.NoopAlgorithm;
import org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck.CounterexampleCheckAlgorithm;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.core.specification.Specification;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.InfeasibleCounterexampleException;
import org.sosy_lab.cpachecker.exceptions.ParserException;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.resources.ResourceLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;
import org.sosy_lab.cpachecker.util.statistics.StatTimerWithMoreOutput;
import org.sosy_lab.cpachecker.util.statistics.StatisticsUtils;

public final class CPAcheckerMutator extends CPAchecker {

  private CFAMutationLimits cfaMutationLimits;

  private final LoggingOptions logOptions;
  private final CFAMutator cfaMutator;

  private CounterexampleCheckAlgorithm cexCheckAlgorithm;

  public CPAcheckerMutator(
      Configuration pConfiguration,
      LogManager pLogManager,
      ShutdownManager pShutdownManager,
      ResourceLimitChecker pLimits,
      LoggingOptions pLogOptions)
      throws InvalidConfigurationException {

    super(pConfiguration, pLogManager, pShutdownManager);
    cfaMutationLimits =
        new CFAMutationLimits(pConfiguration, pLogManager, pShutdownManager, pLimits);

    if (getSerializedCfaFile() != null) {
      throw new InvalidConfigurationException(
          "CFA mutation needs source files to be parsed into CFA. Do not specify 'cfaMutation=true' "
              + "and loading CFA with 'analysis.serializedCfaFile' simultaneously.");
    }

    logOptions = pLogOptions;
    cfaMutator = new CFAMutator(config, logger, shutdownNotifier);
  }

  private void setupCexChecker(CFA pCfa)
      throws InvalidConfigurationException, CPAException, InterruptedException {
    ConfigurableProgramAnalysis originalCpa = createCPA(pCfa);
    ConfigurableProgramAnalysis argCpa = CPAs.retrieveCPA(originalCpa, ARGCPA.class);

    if (argCpa == null) {
      argCpa =
        ARGCPA
            .factory()
            .setChild(originalCpa)
            .setConfiguration(config)
            .setLogger(logger)
            .setShutdownNotifier(shutdownNotifier)
            .set(getSpecification(), Specification.class)
            .set(pCfa, CFA.class)
            .createInstance();
    }

    cexCheckAlgorithm =
        new CounterexampleCheckAlgorithm(
            NoopAlgorithm.INSTANCE,
            argCpa,
            config,
            getSpecification(),
            logger,
            shutdownNotifier,
            pCfa);
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
      parse(cfaMutator, programDenotation);
      totalStats.setCFACreatorStatistics(cfaMutator.getStatistics());
      cfaMutator.clearCreatorStats();

      CFA originalCfa = getCfa();
      if (originalCfa == null) {
        // invalid input files
        return produceResult();
      }
      setupCexChecker(originalCfa);

      final AnalysisResult originalResult =
          analysisRound(getCfa(), logger, totalStats.originalTime);

      AnalysisOutcome originalOutcome = originalResult.toAnalysisOutcome(originalResult);
      if (cfaMutator.shouldReturnWithoutMutation(originalOutcome)) {
        return originalResult.result;
      }

      cfaMutationLimits.setOriginalTime(totalStats.originalTime.getConsumedTime(), logger);

      if (cfaMutationLimits.exceedsLimitsForAnalysis(
          "CFA mutation interrupted before it started to mutate the CFA:")) {
        return originalResult.asMutatorResult(Result.NOT_YET_STARTED, cfaMutator, totalStats);
      }

      // CFAMutator stores needed info from #parse,
      // so no need to pass CFA as argument in next calls

      int rollbacksInRow = 0;
      for (int round = 1; cfaMutator.canMutate(); round++) {
        logger.log(Level.INFO, "Mutation round", round);

        CFA mutated = cfaMutator.mutate();
        LogManager roundLogger = cfaMutator.createRoundLogger(logOptions);
        lastResult = analysisRound(mutated, roundLogger, totalStats.afterMutations);
        // TODO export intermediate results
        // XXX it is incorrect to save intermediate stats, as reached is updated?

        AnalysisOutcome lastOutcome = lastResult.toAnalysisOutcome(originalResult);

        if (cfaMutator.shouldCheckFeasibiblity(lastOutcome)) {
          if (cfaMutationLimits.exceedsLimitsForFeasibility()) {
            return lastResult.asMutatorResult(Result.FALSE, cfaMutator, totalStats);
          }

          boolean errorIsFeasible = false;
          try {
            totalStats.feasibilityCheck.start();
            errorIsFeasible = isFeasible(roundLogger, lastResult);
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

        CFA rollbacked = cfaMutator.setResult(lastOutcome);

        if (cfaMutationLimits.exceedsLimitsForAnalysis(
            String.format("CFA mutation interrupted after %s. analysis round", round))) {
          return lastResult.asMutatorResult(Result.DONE, cfaMutator, totalStats);
        }

        // Check that property is still preserved after rollback
        if (rollbacked == null || cfaMutationLimits.getCheckAfterRollbacks() == 0) {
          // options say pass the check this time
          rollbacksInRow = 0;

        } else if (++rollbacksInRow % cfaMutationLimits.getCheckAfterRollbacks() == 0) {
          logger.log(
              Level.INFO, "Running analysis after", rollbacksInRow, "mutation rollback in row");

          LogManager checkLogger = cfaMutator.createRoundLogger(logOptions);
          lastResult = analysisRound(rollbacked, checkLogger, totalStats.afterRollbacks);
          AnalysisOutcome analysisOutcome = lastResult.toAnalysisOutcome(originalResult);
          // If analysis ended because of a global shutdown, the result may be TIMEOUT
          // otherwise, check it is expected one
          if (!shutdownNotifier.shouldShutdown()
              || analysisOutcome != AnalysisOutcome.VERDICT_UNKNOWN_BECAUSE_OF_TIMEOUT) {
            cfaMutator.verifyOutcome(analysisOutcome);
          }

          if (cfaMutationLimits.exceedsLimitsForAnalysis(
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

    } catch (InvalidConfigurationException | CPAException e) {
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

  // run analysis, but for already stored CFA, and catch its errors
  private AnalysisResult analysisRound(CFA pCfa, LogManager pLogger, StatTimer pTimer)
      throws InvalidConfigurationException {
    Timer timer = new Timer();
    timer.start();

    Throwable t = null;
    ShutdownNotifier parentNotifier = shutdownNotifier;
    ShutdownManager roundShutdownManager = ShutdownManager.createWithParent(parentNotifier);
    ResourceLimitChecker limits =
        cfaMutationLimits.getResourceLimitCheckerForAnalysis(roundShutdownManager);
    if (limits.getResourceLimits().isEmpty()) {
      pLogger.log(Level.INFO, "No resource limits for analysis round specified");
    } else {
      pLogger.log(
          Level.INFO,
          "Using",
          Joiner.on(", ")
              .join(Iterables.transform(limits.getResourceLimits(), ResourceLimit::getName)),
          "for analysis round");
    }
    limits.start();
    pTimer.start();

    CPAchecker cpachecker = new CPAchecker(config, pLogger, roundShutdownManager);

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
      assert cpachecker.shutdownNotifier.shouldShutdown();
      // this round was too long
      t = e;
      logger.logf(
          Level.WARNING,
          "Analysis round interrupted (%s)",
          cpachecker.shutdownNotifier.getReason());

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
      if (e.getStackTrace().length == 0) {
        // too many same exceptions were thrown, it's JVM optimization
        logger.log(Level.WARNING, "Recurring error:", e.getClass(), "(no stack trace)");
      } else {
        logger.logUserException(Level.WARNING, e, null);
      }
      t = e;

    } finally {
      limits.cancel();
      cpachecker.closeCPAsIfPossible();
      pTimer.stop();
      timer.stop();
    }

    CPAcheckerResult cur = cpachecker.produceResult();
    if (t == null) {
      logger.log(Level.INFO, cur.getResultString());
    } else {
      // exception was already logged
    }
    pLogger.log(Level.INFO, "Used", timer.getSumTime(), "for analysis round");

    return new AnalysisResult(cur, t);
  }

  private boolean isFeasible(LogManager pLogger, AnalysisResult pResult) {
    Timer timer = new Timer();
    timer.start();

    ShutdownNotifier parentNotifier = shutdownNotifier;
    ShutdownManager feasibilityShutdownManager = ShutdownManager.createWithParent(parentNotifier);
    ResourceLimitChecker limits =
        cfaMutationLimits.getResourceLimitCheckerForFeasibility(feasibilityShutdownManager);
    if (limits.getResourceLimits().isEmpty()) {
      pLogger.log(Level.INFO, "No resource limits for feasibility check specified");
    } else {
      pLogger.log(
          Level.INFO,
          "Using",
          Joiner.on(", ")
              .join(Iterables.transform(limits.getResourceLimits(), ResourceLimit::getName)),
          "for feasibility check");
    }
    limits.start();
    // pTimer.start();

    AlgorithmStatus status;
    try {
      status = cexCheckAlgorithm.run(pResult.result.getReached());
      assert !status.wasPropertyChecked();
      if (status.isPrecise()) {
        // found feasible cex
        return true;
      }

      assert false : "No counterexamples found, but feasibility is checked";

    } catch (InfeasibleCounterexampleException e) {
      pLogger.log(Level.INFO, "Counterexamples are infeasible");

    } catch (CPAException e) {
      pLogger.logUserException(Level.WARNING, e, "while checking counterexample feasibiblity");

    } catch (InterruptedException e) {
      assert feasibilityShutdownManager.getNotifier().shouldShutdown();
      // this feasibility check was too long
      logger.logf(
          Level.INFO,
          "Feasibility check interrupted (%s)",
          feasibilityShutdownManager.getNotifier().getReason());

    } finally {
      limits.cancel();
      timer.stop();
    }

    pLogger.log(Level.INFO, "Used", timer.getSumTime(), "for feasibility check");
    return false;
  }

  // keep last result and thrown error
  private static class AnalysisResult {
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
      if (pTotalStats != null) {
        if (pCfaMutator != null) {
          pCfaMutator.collectStatistics(pTotalStats.subStats);
        }
        pTotalStats.lastMainStats = getStats();
      }
      String desc = getVerdict() == Result.FALSE ? result.getTargetDescription() : "";
      return new CPAcheckerResult(pResult, desc, result.getReached(), result.getCfa(), pTotalStats);
    }

    public Result getVerdict() {
      return result.getResult();
    }

    public MainCPAStatistics getStats() {
      return (MainCPAStatistics) result.getStatistics();
    }
  }

  class MainCFAMutationStatistics implements Statistics {
    private MainCPAStatistics lastMainStats = null;
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

      if (lastMainStats != null) {
        pOut.println("Last analysis statistics");
        pOut.println("========================");
        StatisticsUtils.printStatistics(lastMainStats, pOut, logger, pResult, pReached);
        StatisticsUtils.writeOutputFiles(lastMainStats, logger, pResult, pReached);
        pOut.println();
      }

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
      if (lastMainStats != null) {
        lastMainStats.writeOutputFiles(pResult, pReached);
      }

      subStats.forEach(s -> StatisticsUtils.writeOutputFiles(s, logger, pResult, pReached));
    }
  }
}
